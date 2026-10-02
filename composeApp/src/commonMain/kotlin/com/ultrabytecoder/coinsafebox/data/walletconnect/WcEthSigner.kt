package com.ultrabytecoder.coinsafebox.data.walletconnect

import com.ultrabytecoder.coinsafebox.data.NetworkConfig
import com.ultrabytecoder.coinsafebox.providers.DerivationPathResolver
import com.ultrabytecoder.coinsafebox.providers.keccak256
import com.ultrabytecoder.coinsafebox.providers.rlpEncode
import com.ultrabytecoder.coinsafebox.providers.rlpEncodeAddress
import com.ultrabytecoder.coinsafebox.providers.rlpEncodeList
import com.ultrabytecoder.coinsafebox.providers.rlpEncodeLong
import fr.acinq.bitcoin.Crypto
import fr.acinq.bitcoin.DeterministicWallet
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Signs ETH transactions on behalf of a WalletConnect session request.
 * Supports both legacy (gasPrice) and EIP-1559 (maxFeePerGas) transactions.
 */
class WcEthSigner(
    private val networkConfig: NetworkConfig,
) {
    /**
     * Signs the transaction described by [txParams] using the key derived at
     * [derivationPath] from [masterSeed]. Returns the signed raw transaction hex.
     */
    fun signTransaction(masterSeed: ByteArray, derivationPath: String, txParams: JsonObject): String {
        val masterKey = DeterministicWallet.generate(masterSeed)
        val segments = DerivationPathResolver.parsePath(derivationPath).map { (index, hardened) ->
            if (hardened) DeterministicWallet.hardened(index) else index
        }
        val fromKey = masterKey.derivePrivateKey(segments)

        val nonce = txParams["nonce"]?.jsonPrimitive?.content?.hexToLong() ?: 0L
        val to = txParams["to"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'to' field")
        val value = txParams["value"]?.jsonPrimitive?.content?.hexToLong() ?: 0L
        val data = txParams["data"]?.jsonPrimitive?.content
            ?.let { if (it == "0x" || it == "0X") byteArrayOf() else Hex.decode(it) }
            ?: byteArrayOf()

        return if (txParams.containsKey("maxFeePerGas")) {
            signEip1559(fromKey, nonce, to, value, data, txParams)
        } else {
            signLegacy(fromKey, nonce, to, value, data, txParams)
        }
    }

    private fun signEip1559(
        fromKey: DeterministicWallet.ExtendedPrivateKey,
        nonce: Long,
        to: String,
        value: Long,
        data: ByteArray,
        params: JsonObject,
    ): String {
        val gasTipCap = params["maxPriorityFeePerGas"]?.jsonPrimitive?.content?.hexToLong() ?: 0L
        val gasFeeCap = params["maxFeePerGas"]?.jsonPrimitive?.content?.hexToLong()
            ?: throw IllegalArgumentException("Missing maxFeePerGas")
        val gasLimit = params["gas"]?.jsonPrimitive?.content?.hexToLong()
            ?: throw IllegalArgumentException("Missing gas limit")

        val emptyAccessList = rlpEncodeList(emptyList())

        val unsignedPayload = rlpEncodeList(
            listOf(
                rlpEncodeLong(networkConfig.ethChainId),
                rlpEncodeLong(nonce),
                rlpEncodeLong(gasTipCap),
                rlpEncodeLong(gasFeeCap),
                rlpEncodeLong(gasLimit),
                rlpEncodeAddress(to),
                rlpEncodeLong(value),
                rlpEncode(data),
                emptyAccessList
            )
        )

        val unsignedTx = byteArrayOf(0x02) + unsignedPayload
        val msgHash = keccak256(unsignedTx)

        val sig = Crypto.sign(msgHash, fromKey.privateKey)
        val recoveryId = findRecoveryId(sig, msgHash, fromKey.publicKey)

        val sigBytes = sig.toByteArray()
        val r = sigBytes.copyOfRange(0, 32)
        val s = sigBytes.copyOfRange(32, 64)

        val signedPayload = rlpEncodeList(
            listOf(
                rlpEncodeLong(networkConfig.ethChainId),
                rlpEncodeLong(nonce),
                rlpEncodeLong(gasTipCap),
                rlpEncodeLong(gasFeeCap),
                rlpEncodeLong(gasLimit),
                rlpEncodeAddress(to),
                rlpEncodeLong(value),
                rlpEncode(data),
                emptyAccessList,
                rlpEncodeLong(recoveryId.toLong()),
                rlpEncode(r),
                rlpEncode(s)
            )
        )

        return "0x02" + Hex.encode(signedPayload)
    }

    private fun signLegacy(
        fromKey: DeterministicWallet.ExtendedPrivateKey,
        nonce: Long,
        to: String,
        value: Long,
        data: ByteArray,
        params: JsonObject,
    ): String {
        val gasPrice = params["gasPrice"]?.jsonPrimitive?.content?.hexToLong()
            ?: throw IllegalArgumentException("Missing gasPrice")
        val gasLimit = params["gas"]?.jsonPrimitive?.content?.hexToLong()
            ?: throw IllegalArgumentException("Missing gas limit")

        val unsignedRlp = rlpEncodeList(
            listOf(
                rlpEncodeLong(nonce),
                rlpEncodeLong(gasPrice),
                rlpEncodeLong(gasLimit),
                rlpEncodeAddress(to),
                rlpEncodeLong(value),
                rlpEncode(data),
                rlpEncodeLong(networkConfig.ethChainId),
                rlpEncode(byteArrayOf()),
                rlpEncode(byteArrayOf())
            )
        )

        val msgHash = keccak256(unsignedRlp)
        val sig = Crypto.sign(msgHash, fromKey.privateKey)
        val recoveryId = findRecoveryId(sig, msgHash, fromKey.publicKey)
        val v = networkConfig.ethChainId * 2 + 35L + recoveryId

        val sigBytes = sig.toByteArray()
        val r = sigBytes.copyOfRange(0, 32)
        val s = sigBytes.copyOfRange(32, 64)

        val signedRlp = rlpEncodeList(
            listOf(
                rlpEncodeLong(nonce),
                rlpEncodeLong(gasPrice),
                rlpEncodeLong(gasLimit),
                rlpEncodeAddress(to),
                rlpEncodeLong(value),
                rlpEncode(data),
                rlpEncodeLong(v),
                rlpEncode(r),
                rlpEncode(s)
            )
        )

        return "0x" + Hex.encode(signedRlp)
    }

    private fun findRecoveryId(
        sig: fr.acinq.bitcoin.ByteVector64,
        msgHash: ByteArray,
        expectedPubKey: PublicKey,
    ): Int {
        for (recId in 0..1) {
            try {
                val recovered = Crypto.recoverPublicKey(sig, msgHash, recId)
                if (recovered == expectedPubKey) return recId
            } catch (_: Exception) {
                continue
            }
        }
        throw IllegalStateException("Could not determine recovery ID")
    }

    private fun String.hexToLong(): Long = removePrefix("0x").removePrefix("0X").toLong(16)
}
