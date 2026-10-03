package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ionspin.kotlin.bignum.decimal.BigDecimal
import com.ionspin.kotlin.bignum.decimal.DecimalMode
import com.ionspin.kotlin.bignum.decimal.RoundingMode
import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcEthSigner
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcPendingRequest
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcRequestOutcome
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class WcRequestViewModel(
    private val wcController: WcController,
    private val accountRepository: AccountRepository,
    private val keyProvider: KeyProvider,
    private val wcEthSigner: WcEthSigner,
    private val walletId: Long,
) : ViewModel() {

    data class State(
        val isLoading: Boolean = true,
        val request: WcPendingRequest? = null,
        val dAppName: String = "",
        val fromAddress: String = "",
        val toAddress: String = "",
        val valueEth: String = "0",
        val gasLimit: String = "",
        val gasPrice: String = "",
        val nonce: String = "",
        val dataPreview: String = "",
        val hasData: Boolean = false,
        val canApprove: Boolean = true,
        val approveDisabledReason: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
        val done: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    init {
        load()
    }

    private fun load() {
        val request = wcController.pendingRequestHolder.pendingRequest.value
            ?: run {
                _state.value = State(isLoading = false, error = "No pending request.")
                return
            }

        val params = request.requestParams as? JsonObject
            ?: run {
                request.deferred.complete(WcRequestOutcome.Failure(3, "Malformed request params"))
                wcController.pendingRequestHolder.clear()
                _state.value = State(isLoading = false, error = "Malformed request from dApp.")
                return
            }

        val to = params["to"]?.jsonPrimitive?.content
        val toAddress = to ?: ""
        val valueHex = params["value"]?.jsonPrimitive?.content ?: "0x0"
        val valueEth = try {
            val valueWei = BigInteger.parseString(valueHex.removePrefix("0x"), 16)
            BigDecimal.fromBigInteger(valueWei)
                .divide(
                    BigDecimal.fromLong(10).pow(18),
                    decimalMode = DecimalMode(
                        decimalPrecision = 80L,
                        roundingMode = RoundingMode.ROUND_HALF_AWAY_FROM_ZERO,
                    ),
                )
                .toPlainString()
        } catch (_: Exception) { "0" }

        val gas = (params["gas"] ?: params["gasLimit"])?.jsonPrimitive?.content
            ?.removePrefix("0x")?.toLongOrNull(16)?.toString() ?: ""
        val gasPrice = when {
            params.containsKey("maxFeePerGas") ->
                (params["maxFeePerGas"]?.jsonPrimitive?.content ?: "0x0")
                    .removePrefix("0x").toLongOrNull(16)?.let { formatGwei(it) } ?: ""
            params.containsKey("gasPrice") ->
                (params["gasPrice"]?.jsonPrimitive?.content ?: "0x0")
                    .removePrefix("0x").toLongOrNull(16)?.let { formatGwei(it) } ?: ""
            else -> ""
        }
        val nonceHex = params["nonce"]?.jsonPrimitive?.content
        val nonce = nonceHex?.removePrefix("0x")?.toLongOrNull(16)?.toString() ?: ""
        val data = params["data"]?.jsonPrimitive?.content ?: "0x"
        val hasData = data != "0x" && data != "0X"
        val dataPreview = if (hasData) {
            if (data.length <= 24) data else data.take(24) + "…"
        } else ""

        // Check if the wallet has an ETH account to sign with.
        viewModelScope.launch {
            var canApprove = true
            var approveDisabledReason: String? = null
            try {
                val ethAccounts = accountRepository.getAccountsByWalletFlow(walletId).first()
                    .filter { it.type is AccountType.Eth && !it.address.isNullOrBlank() }
                if (ethAccounts.isEmpty()) {
                    canApprove = false
                    approveDisabledReason = "No ETH account available to sign."
                } else {
                    val from = params["from"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    if (from != null && from.isNotBlank()) {
                        val matches = ethAccounts.any { it.address?.equals(from, ignoreCase = true) == true }
                        if (!matches) {
                            canApprove = false
                            approveDisabledReason = "Request is for an address not in this wallet."
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                canApprove = false
                approveDisabledReason = "Could not verify account."
            }

            _state.value = State(
                isLoading = false,
                request = request,
                dAppName = request.dAppName,
                fromAddress = params["from"]?.jsonPrimitive?.content ?: "",
                toAddress = toAddress,
                valueEth = valueEth,
                gasLimit = gas,
                gasPrice = gasPrice,
                nonce = nonce,
                dataPreview = dataPreview,
                hasData = hasData,
                canApprove = canApprove,
                approveDisabledReason = approveDisabledReason,
            )
        }
    }

    // Cross-platform replacement for `String.format("%.3f Gwei", wei / 1e9)`.
    // The int/frac are split so no intermediate multiplies past Long.MAX
    // (`wei * 1000` would overflow for gas prices above ~9.2e15 wei).
    private fun formatGwei(wei: Long): String {
        if (wei < 0) return "0.000 Gwei"
        val intGwei = wei / 1_000_000_000L
        val remainderWei = wei % 1_000_000_000L
        val milliFrac = (remainderWei * 1000L + 500_000_000L) / 1_000_000_000L // 0..1000
        var intPart = intGwei
        var fracPart = milliFrac
        if (fracPart == 1000L) {
            intPart++
            fracPart = 0
        }
        return "$intPart.${fracPart.toString().padStart(3, '0')} Gwei"
    }

    fun approve() {
        val s = _state.value
        if (s.busy || s.done || s.isLoading || !s.canApprove) return
        val request = s.request ?: return
        _state.value = s.copy(busy = true, error = null)

        viewModelScope.launch {
            try {
                val account = accountRepository.getAccountsByWalletFlow(walletId).first()
                    .first { it.type is AccountType.Eth && !it.address.isNullOrBlank() }

                // If 'from' was specified, use the matching account.
                val from = (request.requestParams as? JsonObject)?.get("from")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val signingAccount = if (from != null) {
                    accountRepository.getAccountsByWalletFlow(walletId).first()
                        .firstOrNull { it.type is AccountType.Eth && it.address?.equals(from, ignoreCase = true) == true }
                        ?: throw IllegalStateException("No matching account for from=$from")
                } else account

                val signedTx = keyProvider.withMasterSeed(walletId) { seed ->
                    wcEthSigner.signTransaction(seed, signingAccount.derivationPath, request.requestParams as JsonObject)
                }

                request.deferred.complete(
                    WcRequestOutcome.Success(kotlinx.serialization.json.JsonPrimitive(signedTx))
                )
                _state.value = _state.value.copy(busy = false, done = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                request.deferred.complete(
                    WcRequestOutcome.Failure(5001, "Signing failed")
                )
                _state.value = _state.value.copy(busy = false, error = "Signing failed. Try again.")
            }
        }
    }

    fun reject() {
        val s = _state.value
        if (s.busy || s.done || s.isLoading) return
        val request = s.request ?: return
        _state.value = s.copy(busy = true)

        request.deferred.complete(
            WcRequestOutcome.Failure(4001, "User rejected the request")
        )
        _state.value = _state.value.copy(busy = false, done = true)
    }
}
