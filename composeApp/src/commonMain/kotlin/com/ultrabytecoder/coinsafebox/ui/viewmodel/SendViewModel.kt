package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.data.SettingsKeys
import com.ultrabytecoder.coinsafebox.data.SettingsStorage
import com.ultrabytecoder.coinsafebox.domain.model.AccountInfo
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.model.CustomFeeParams
import com.ultrabytecoder.coinsafebox.domain.model.FeeEstimation
import com.ultrabytecoder.coinsafebox.domain.model.FeePresets
import com.ultrabytecoder.coinsafebox.domain.model.FeeValidator
import com.ultrabytecoder.coinsafebox.domain.model.FiatCurrency
import com.ultrabytecoder.coinsafebox.domain.model.WalletInfo
import com.ultrabytecoder.coinsafebox.domain.model.feeSymbol
import com.ultrabytecoder.coinsafebox.domain.provider.FiatQuoteProvider
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.repository.TransactionRepository
import com.ultrabytecoder.coinsafebox.domain.repository.UtxoRepository
import com.ultrabytecoder.coinsafebox.domain.repository.WalletRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.domain.usecase.EstimateFeeUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.GetAccountsUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SendUseCase
import com.ultrabytecoder.coinsafebox.domain.usecase.SyncAccountBalanceUseCase
import com.ultrabytecoder.coinsafebox.providers.ProviderFactory
import com.ultrabytecoder.coinsafebox.security.SessionLockNotifier
import com.ultrabytecoder.coinsafebox.ui.util.BalanceSufficiency
import com.ultrabytecoder.coinsafebox.ui.util.SecureTextFieldState
import com.ultrabytecoder.coinsafebox.ui.util.evaluateBalanceSufficiency
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class FeeSelectionMode {
    data object Auto : FeeSelectionMode()
    data object Conservative : FeeSelectionMode()
    data object Balanced : FeeSelectionMode()
    data object Generous : FeeSelectionMode()
    data object Custom : FeeSelectionMode()

    fun name(): String = when (this) {
        is Auto -> "auto"
        is Conservative -> "conservative"
        is Balanced -> "balanced"
        is Generous -> "generous"
        is Custom -> "custom"
    }

    companion object {
        fun fromName(name: String): FeeSelectionMode = when (name) {
            // Backward-compat: map old persisted speed-tier names
            "slow" -> Conservative
            "medium" -> Balanced
            "fast" -> Generous
            "conservative" -> Conservative
            "balanced" -> Balanced
            "generous" -> Generous
            "custom" -> Custom
            else -> Auto
        }
    }
}

enum class SendPhase { ENTER_DETAILS, MNEMONIC_PROMPT, SIGNING }

private object FeePreferenceKeys {
    const val MODE_PREFIX = "fee_mode_"
    const val BTC_RATE_PREFIX = "fee_custom_btc_rate_"     // Append accountId
    const val ETH_PRIORITY_PREFIX = "fee_custom_eth_priority_v2_"   // mGwei
    const val ETH_MAX_PREFIX = "fee_custom_eth_max_v2_"             // mGwei
    const val TRC20_LIMIT_PREFIX = "fee_custom_trc20_limit_"
}

class SendViewModel(
    private val accountId: String,
    private val getAccounts: GetAccountsUseCase,
    private val send: SendUseCase,
    private val estimateFeeUseCase: EstimateFeeUseCase,
    private val syncAccount: SyncAccountBalanceUseCase,
    private val accountRepository: AccountRepository,
    private val utxoRepository: UtxoRepository,
    private val transactionRepository: TransactionRepository,
    private val keyProvider: KeyProvider,
    private val networkConfig: NetworkConfig,
    private val settingsStorage: SettingsStorage,
    private val quoteProvider: FiatQuoteProvider,
    private val walletRepository: WalletRepository
) : ViewModel() {
    private val _account = MutableStateFlow<AccountInfo?>(null)
    val account: StateFlow<AccountInfo?> = _account.asStateFlow()

    private val _wallet = MutableStateFlow<WalletInfo?>(null)
    val wallet: StateFlow<WalletInfo?> = _wallet.asStateFlow()

    // Plain state (not a stateIn(Lazily) chain): requestSend reads .value without
    // the UI ever collecting the flow, so a Lazily-started upstream would report
    // `false` forever and the read-only branch (mnemonic prompt) would be
    // unreachable. Kept in sync with _wallet below.
    private val _isReadOnly = MutableStateFlow(false)
    val isReadOnly: StateFlow<Boolean> = _isReadOnly.asStateFlow()
    val hasPassphrase: StateFlow<Boolean> = _wallet.map { it?.hasPassphrase ?: false }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val _phase = MutableStateFlow(SendPhase.ENTER_DETAILS)
    val phase: StateFlow<SendPhase> = _phase.asStateFlow()

    val mnemonicField = SecureTextFieldState()
    val passphraseField = SecureTextFieldState()

    private val _sendError = MutableStateFlow<String?>(null)
    val sendError: StateFlow<String?> = _sendError.asStateFlow()

    private val _sendResult = MutableStateFlow<String?>(null)
    val sendResult: StateFlow<String?> = _sendResult.asStateFlow()

    private var pendingAddress: String? = null
    private var pendingAmount: BigDecimal? = null

    private val _fee = MutableStateFlow<FeeEstimation?>(null)
    val fee: StateFlow<FeeEstimation?> = _fee.asStateFlow()

    private val _feeError = MutableStateFlow<String?>(null)
    val feeError: StateFlow<String?> = _feeError.asStateFlow()

    private val _feePresets = MutableStateFlow<FeePresets?>(null)
    val feePresets: StateFlow<FeePresets?> = _feePresets.asStateFlow()

    private val _selectedFeeMode = MutableStateFlow<FeeSelectionMode>(FeeSelectionMode.Auto)
    val selectedFeeMode: StateFlow<FeeSelectionMode> = _selectedFeeMode.asStateFlow()

    // Custom fee inputs
    private val _customBtcFeeRate = MutableStateFlow(10L)
    val customBtcFeeRate: StateFlow<Long> = _customBtcFeeRate.asStateFlow()

    private val _customEthPriorityFee = MutableStateFlow(25_000L)
    val customEthPriorityFee: StateFlow<Long> = _customEthPriorityFee.asStateFlow()

    private val _customEthMaxFee = MutableStateFlow(35_000L)
    val customEthMaxFee: StateFlow<Long> = _customEthMaxFee.asStateFlow()

    private val _customTrc20FeeLimit = MutableStateFlow(35_000_000L)
    val customTrc20FeeLimit: StateFlow<Long> = _customTrc20FeeLimit.asStateFlow()

    private val _validationError = MutableStateFlow<String?>(null)
    val validationError: StateFlow<String?> = _validationError.asStateFlow()

    // Fiat currency conversion of the fee / total shown in the fee card.
    private val _fiatCurrency = MutableStateFlow(
        FiatCurrency.fromStored(settingsStorage.getString(SettingsKeys.FIAT_CURRENCY))
    )
    val fiatCurrency: StateFlow<FiatCurrency> = _fiatCurrency.asStateFlow()

    private val _feeFiat = MutableStateFlow<Double?>(null)
    val feeFiat: StateFlow<Double?> = _feeFiat.asStateFlow()

    private val _totalFiat = MutableStateFlow<Double?>(null)
    val totalFiat: StateFlow<Double?> = _totalFiat.asStateFlow()

    private var lastAmount: BigDecimal? = null

    // Monotonic id for estimation requests (Main-dispatcher confined). Results from
    // superseded requests — e.g. when the user edits the amount while an RPC is still
    // in flight, or clears it mid-flight — must never be applied to the UI state.
    private var estimationSeq: Long = 0

    init {
        // Load saved fee preferences
        val savedMode = settingsStorage.getString(FeePreferenceKeys.MODE_PREFIX + accountId)
        _selectedFeeMode.value = FeeSelectionMode.fromName(savedMode ?: "auto")
        _customBtcFeeRate.value = (settingsStorage.getString(FeePreferenceKeys.BTC_RATE_PREFIX + accountId) ?: "10").toLongOrNull() ?: 10L
        _customEthPriorityFee.value = (settingsStorage.getString(FeePreferenceKeys.ETH_PRIORITY_PREFIX + accountId) ?: "25000").toLongOrNull() ?: 25_000L
        _customEthMaxFee.value = (settingsStorage.getString(FeePreferenceKeys.ETH_MAX_PREFIX + accountId) ?: "35000").toLongOrNull() ?: 35_000L
        _customTrc20FeeLimit.value = (settingsStorage.getString(FeePreferenceKeys.TRC20_LIMIT_PREFIX + accountId) ?: "35000000").toLongOrNull() ?: 35_000_000L

        viewModelScope.launch {
            _account.value = getAccounts.byId(accountId)
            _wallet.value = _account.value?.let { walletRepository.getWallet(it.walletId) }
            _isReadOnly.value = _wallet.value?.isReadOnly ?: false

            // Live collector so a wallet flipped to read-only while this VM is alive is reflected.
            viewModelScope.launch {
                walletRepository.getWalletsFlow().collect { list ->
                    val wid = _account.value?.walletId
                    if (wid != null) {
                        val w = list.find { it.id == wid }
                        _wallet.value = w
                        _isReadOnly.value = w?.isReadOnly ?: false
                    }
                }
            }

            val acc = _account.value
            if (acc != null) {
                try {
                    val provider = if (_wallet.value?.isReadOnly == true) {
                        val xpub = if (acc.type is AccountType.Btc) accountRepository.getXpub(acc.id) else null
                        ProviderFactory.createReadOnly(acc.type, xpub, utxoRepository, accountRepository, transactionRepository, networkConfig, acc.params)
                    } else {
                        keyProvider.withMasterSeed(acc.walletId) { masterSeed ->
                            ProviderFactory.create(
                                acc.type, masterSeed,
                                utxoRepository, accountRepository, transactionRepository,
                                networkConfig, acc.params
                            )
                        }
                    }
                    _feePresets.value = provider.feePresets(acc.id)
                } catch (e: Exception) {
                    // Preset loading failed (e.g. RPC error) — surface it instead of hiding the selector silently.
                    _feeError.value = "Fee presets could not be loaded: ${e.message ?: "network error"}"
                }
                if (_feePresets.value == null && _selectedFeeMode.value !is FeeSelectionMode.Auto) {
                    _selectedFeeMode.value = FeeSelectionMode.Auto
                    settingsStorage.putString(
                        FeePreferenceKeys.MODE_PREFIX + accountId,
                        FeeSelectionMode.Auto.name()
                    )
                }
            }
        }

        viewModelScope.launch {
            SessionLockNotifier.locked.collect {
                clearSensitiveData()
                _phase.value = SendPhase.ENTER_DETAILS
            }
        }
    }

    fun setSelectedFeeMode(mode: FeeSelectionMode) {
        _selectedFeeMode.value = mode
        _validationError.value = null
        settingsStorage.putString(FeePreferenceKeys.MODE_PREFIX + accountId, mode.name())
    }

    /**
     * Copy the values from [params] into the custom-fee state flows *without*
     * persisting to settings storage. Used to pre-fill the editable fields
     * when a preset / Auto chip is selected.
     */
    fun syncCustomFieldsFromParams(params: CustomFeeParams) {
        when (params) {
            is CustomFeeParams.Btc -> _customBtcFeeRate.value = params.feeRateSatVb
            is CustomFeeParams.Eth -> {
                _customEthPriorityFee.value = params.maxPriorityFeePerGasMilliGwei
                _customEthMaxFee.value = params.maxFeePerGasMilliGwei
            }
            is CustomFeeParams.Tron -> _customTrc20FeeLimit.value = params.feeLimitSun
        }
    }

    fun setCustomBtcFeeRate(rate: Long) {
        _customBtcFeeRate.value = rate
        settingsStorage.putString(FeePreferenceKeys.BTC_RATE_PREFIX + accountId, rate.toString())
    }

    fun setCustomEthFees(priorityFee: Long, maxFee: Long) {
        _customEthPriorityFee.value = priorityFee
        _customEthMaxFee.value = maxFee
        settingsStorage.putString(FeePreferenceKeys.ETH_PRIORITY_PREFIX + accountId, priorityFee.toString())
        settingsStorage.putString(FeePreferenceKeys.ETH_MAX_PREFIX + accountId, maxFee.toString())
    }

    fun setCustomTrc20FeeLimit(limit: Long) {
        _customTrc20FeeLimit.value = limit
        settingsStorage.putString(FeePreferenceKeys.TRC20_LIMIT_PREFIX + accountId, limit.toString())
    }

    fun validateCustomFee(): Boolean {
        val accountType = _account.value?.type ?: return true
        val parentChain = accountType.parentChain() ?: accountType

        return when (parentChain) {
            is AccountType.Btc -> {
                val rate = _customBtcFeeRate.value
                if (FeeValidator.validateBtcFeeRate(rate)) {
                    _validationError.value = null
                    true
                } else {
                    _validationError.value = "BTC fee rate must be between 1 and 2000 sat/vB"
                    false
                }
            }
            is AccountType.Eth -> {
                val p = _customEthPriorityFee.value
                val m = _customEthMaxFee.value
                if (FeeValidator.validateEthFeeParams(p, m)) {
                    _validationError.value = null
                    true
                } else {
                    val parts = mutableListOf<String>()
                    if (!FeeValidator.validateEthPriorityFee(p)) parts.add("priority fee: 0.001–1000 Gwei")
                    if (!FeeValidator.validateEthMaxFee(m)) parts.add("max fee: 0.001–10000 Gwei")
                    if (m < p) parts.add("max fee must be >= priority fee")
                    _validationError.value = "Valid ranges — " + parts.joinToString(", ")
                    false
                }
            }
            is AccountType.Trx -> {
                val limit = _customTrc20FeeLimit.value
                if (FeeValidator.validateTrc20FeeLimit(limit)) {
                    _validationError.value = null
                    true
                } else {
                    _validationError.value = "TRC-20 fee limit must be between 1 and 100 TRX"
                    false
                }
            }
            else -> {
                _validationError.value = null
                true
            }
        }
    }

    private fun buildFeeParams(): CustomFeeParams? {
        val mode = _selectedFeeMode.value
        val presets = _feePresets.value
        val accountType = _account.value?.type ?: return null
        val parentChain = accountType.parentChain() ?: accountType

        return when (mode) {
            is FeeSelectionMode.Auto -> null
            is FeeSelectionMode.Conservative -> presets?.slow
            is FeeSelectionMode.Balanced -> presets?.medium
            is FeeSelectionMode.Generous -> presets?.fast
            is FeeSelectionMode.Custom -> when (parentChain) {
                is AccountType.Btc -> CustomFeeParams.Btc(_customBtcFeeRate.value)
                is AccountType.Eth -> CustomFeeParams.Eth(
                    _customEthPriorityFee.value,
                    _customEthMaxFee.value
                )
                is AccountType.Trx -> {
                    // Native TRX has no presets and rejects custom fees.
                    if (presets == null) null
                    else CustomFeeParams.Tron(_customTrc20FeeLimit.value)
                }
                else -> null
            }
        }
    }

    /** Returns true if this account type supports fee selection (has presets). */
    fun supportsFeeSelection(): Boolean {
        val accountType = _account.value?.type ?: return false
        val parentChain = accountType.parentChain() ?: accountType
        return (parentChain is AccountType.Btc ||
            parentChain is AccountType.Eth ||
            parentChain is AccountType.Trx) &&
            _feePresets.value != null
    }

    /**
     * Whether to render the fee section at all — includes native TRX, which
     * has no presets but should show a disabled Auto indicator.
     */
    fun showFeeSection(): Boolean {
        val accountType = _account.value?.type ?: return false
        val parentChain = accountType.parentChain() ?: accountType
        return parentChain is AccountType.Btc ||
            parentChain is AccountType.Eth ||
            parentChain is AccountType.Trx
    }

    fun estimateFee(amount: BigDecimal, recipientAddress: String? = null) {
        val seq = ++estimationSeq
        viewModelScope.launch {
            try {
                _feeError.value = null
                val estimation = estimateFeeUseCase(accountId, amount, recipientAddress, buildFeeParams())
                if (seq != estimationSeq) return@launch
                // The crypto fee and its fiat conversion must update together: drop the
                // old fiat now so the card never pairs a new fee with stale prices while
                // the fresh quotes are being fetched.
                clearFiatValues()
                _fee.value = estimation
                lastAmount = amount
                updateFiat(amount, estimation, seq)
            } catch (e: IllegalArgumentException) {
                if (seq != estimationSeq) return@launch
                _fee.value = null
                _feeError.value = "Fee estimation failed: ${e.message ?: "invalid parameter"}"
                clearFiatValues()
            } catch (e: IllegalStateException) {
                if (seq != estimationSeq) return@launch
                _fee.value = null
                _feeError.value = e.message ?: "Fee estimation failed"
                clearFiatValues()
            } catch (e: Exception) {
                if (seq != estimationSeq) return@launch
                _fee.value = null
                _feeError.value = "Fee estimation failed: ${e.message ?: "network error"}"
                clearFiatValues()
            }
        }
    }

    fun clearFee() {
        // Supersede any in-flight estimation so it can't refill the card after clearing.
        estimationSeq++
        _fee.value = null
        _feeError.value = null
        lastAmount = null
        clearFiatValues()
    }

    /** Re-read the user's fiat currency selection and re-price the current fee/amount. */
    fun refreshFiatCurrency() {
        _fiatCurrency.value = FiatCurrency.fromStored(settingsStorage.getString(SettingsKeys.FIAT_CURRENCY))
        val fee = _fee.value
        val amount = lastAmount
        if (fee != null && amount != null) updateFiat(amount, fee, estimationSeq) else clearFiatValues()
    }

    private fun clearFiatValues() {
        _feeFiat.value = null
        _totalFiat.value = null
    }

    /**
     * Converts the fee (always denominated in the chain's native coin) and the
     * amount (denominated in the account's own asset) to the selected fiat
     * currency. For token accounts these use two different prices.
     *
     * [seq] is the estimation sequence this pricing belongs to; results are only
     * applied while it is still the latest request, so a slow stale quote can
     * never overwrite fresher values. The identity check on [fee] additionally
     * guards against two callers sharing the same sequence number (e.g. a
     * currency refresh racing an in-flight estimation).
     */
    private fun updateFiat(amount: BigDecimal, fee: FeeEstimation, seq: Long) {
        val account = _account.value ?: return
        val currency = _fiatCurrency.value
        viewModelScope.launch {
            try {
                val feeSymbol = account.type.feeSymbol
                val feePrice = quoteProvider.getPrice(feeSymbol, currency)
                val amountPrice = if (account.symbol.equals(feeSymbol, ignoreCase = true)) {
                    feePrice
                } else {
                    quoteProvider.getPrice(account.symbol, currency)
                }
                if (seq != estimationSeq || _fee.value != fee) return@launch
                // A zero rate means the quote is unusable — show "—" rather than
                // implying a free transaction or a worthless asset. (A zero *fee* is
                // legitimate and unaffected: totalCost, not the price, is zero there.)
                if (feePrice <= 0.0 || amountPrice <= 0.0) {
                    clearFiatValues()
                    return@launch
                }
                val feeFiatValue = fee.totalCost.doubleValue(exactRequired = false) * feePrice
                _feeFiat.value = feeFiatValue
                _totalFiat.value = amount.doubleValue(exactRequired = false) * amountPrice + feeFiatValue
            } catch (e: Exception) {
                if (seq == estimationSeq) clearFiatValues()
            }
        }
    }

    suspend fun sendTransaction(address: String, amount: BigDecimal, mnemonic: CharArray? = null, passphrase: CharArray = CharArray(0)): String {
        if (_selectedFeeMode.value is FeeSelectionMode.Custom && !validateCustomFee()) {
            throw IllegalStateException("Invalid custom fee: ${_validationError.value}")
        }
        val txid = send(accountId, address, amount, buildFeeParams(), mnemonic, passphrase)
        viewModelScope.launch { syncAccount(accountId) }
        return txid
    }

    fun requestSend(address: String, amount: BigDecimal) {
        if (address.isBlank() || amount <= BigDecimal.ZERO) {
            _sendError.value = "Please fill in all fields"
            return
        }
        // Hard block for full (non-readonly) wallets when the balance can't cover
        // the send. Read-only wallets intentionally fall through to the mnemonic
        // prompt: the recovery-phrase guard must stay reachable and is verified
        // before any on-chain operation, and the broadcast itself is still guarded.
        val acc = _account.value
        if (acc != null && !isReadOnly.value) {
            val result = evaluateBalanceSufficiency(acc.amount, amount, _fee.value, acc.type.isToken)
            if (result != BalanceSufficiency.Sufficient) {
                _sendError.value = "Insufficient ${acc.symbol} balance"
                return
            }
        }
        if (isReadOnly.value) {
            pendingAddress = address
            pendingAmount = amount
            _sendError.value = null
            _phase.value = SendPhase.MNEMONIC_PROMPT
        } else {
            pendingAddress = address
            pendingAmount = amount
            startSend(null, CharArray(0))
        }
    }

    fun confirmSend() {
        if (mnemonicField.isBlank()) {
            _sendError.value = "Enter your recovery phrase"
            return
        }
        if (hasPassphrase.value && passphraseField.isBlank()) {
            _sendError.value = "Enter your passphrase"
            return
        }
        val a = pendingAddress
        val m = pendingAmount
        if (a == null || m == null) return
        val mnemonic = mnemonicField.toCharArray()
        val passphrase = if (hasPassphrase.value) passphraseField.toCharArray() else CharArray(0)
        startSend(mnemonic, passphrase)
    }

    private fun startSend(mnemonic: CharArray?, passphrase: CharArray) {
        val a = pendingAddress
        val m = pendingAmount
        if (a == null || m == null) return
        _phase.value = SendPhase.SIGNING
        _sendError.value = null
        viewModelScope.launch {
            try {
                val txid = sendTransaction(a, m, mnemonic, passphrase)
                clearSensitiveData()
                _sendResult.value = txid
            } catch (e: CancellationException) {
                clearSensitiveData()
                throw e
            } catch (e: Exception) {
                clearSensitiveData()
                _sendError.value = e.message ?: "Send failed"
                _phase.value = if (isReadOnly.value) SendPhase.MNEMONIC_PROMPT else SendPhase.ENTER_DETAILS
            }
        }
    }

    fun cancelMnemonicPrompt() {
        clearSensitiveData()
        _sendError.value = null
        _phase.value = SendPhase.ENTER_DETAILS
    }

    fun clearSensitiveData() {
        mnemonicField.wipe()
        passphraseField.wipe()
        pendingAddress = null
        pendingAmount = null
    }

    fun onSendResultConsumed() {
        _sendResult.value = null
    }

    override fun onCleared() {
        clearSensitiveData()
        super.onCleared()
    }
}
