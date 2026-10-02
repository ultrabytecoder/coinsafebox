package com.ultrabytecoder.coinsafebox.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcController
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcEthSigner
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcPendingRequest
import com.ultrabytecoder.coinsafebox.data.walletconnect.WcRequestOutcome
import com.ultrabytecoder.coinsafebox.domain.repository.AccountRepository
import com.ultrabytecoder.coinsafebox.domain.service.KeyProvider
import com.ultrabytecoder.coinsafebox.domain.model.AccountType
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
        val toAddress: String = "",
        val valueEth: String = "0",
        val gasLimit: String = "",
        val gasPrice: String = "",
        val dataPreview: String = "",
        val hasData: Boolean = false,
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

        val params = request.requestParams as? JsonObject ?: JsonObject(emptyMap())
        val to = params["to"]?.jsonPrimitive?.content ?: ""
        val valueHex = params["value"]?.jsonPrimitive?.content ?: "0x0"
        val valueWei = valueHex.removePrefix("0x").toLongOrNull(16) ?: 0L
        val valueEth = String.format("%.6f", valueWei / 1e18)
        val gas = params["gas"]?.jsonPrimitive?.content
            ?.removePrefix("0x")?.toLongOrNull(16)?.toString() ?: ""
        val gasPrice = when {
            params.containsKey("maxFeePerGas") ->
                (params["maxFeePerGas"]?.jsonPrimitive?.content ?: "0x0")
                    .removePrefix("0x").toLongOrNull(16)?.let { String.format("%.3f Gwei", it / 1e9) } ?: ""
            params.containsKey("gasPrice") ->
                (params["gasPrice"]?.jsonPrimitive?.content ?: "0x0")
                    .removePrefix("0x").toLongOrNull(16)?.let { String.format("%.3f Gwei", it / 1e9) } ?: ""
            else -> ""
        }
        val data = params["data"]?.jsonPrimitive?.content ?: "0x"
        val hasData = data != "0x" && data != "0X"
        val dataPreview = if (hasData) {
            if (data.length <= 20) data else data.take(20) + "…"
        } else ""

        _state.value = State(
            isLoading = false,
            request = request,
            dAppName = request.dAppName,
            toAddress = to,
            valueEth = valueEth,
            gasLimit = gas,
            gasPrice = gasPrice,
            dataPreview = dataPreview,
            hasData = hasData,
        )
    }

    fun approve() {
        val s = _state.value
        if (s.busy || s.done || s.isLoading) return
        val request = s.request ?: return
        _state.value = s.copy(busy = true, error = null)

        viewModelScope.launch {
            try {
                val account = accountRepository.getAccountsByWalletFlow(walletId).first()
                    .firstOrNull { it.type is AccountType.Eth }
                    ?: throw IllegalStateException("No ETH account found for this wallet")

                val signedTx = keyProvider.withMasterSeed(walletId) { seed ->
                    wcEthSigner.signTransaction(seed, account.derivationPath, request.requestParams as JsonObject)
                }

                request.deferred.complete(
                    WcRequestOutcome.Success(kotlinx.serialization.json.JsonPrimitive(signedTx))
                )
                _state.value = _state.value.copy(busy = false, done = true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                request.deferred.complete(
                    WcRequestOutcome.Failure(5001, e.message ?: "Signing failed")
                )
                _state.value = _state.value.copy(busy = false, error = e.message ?: "Signing failed.")
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
