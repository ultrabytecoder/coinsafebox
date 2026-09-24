package com.ultrabytecoder.coinsafebox.providers

import com.ionspin.kotlin.bignum.decimal.BigDecimal
import fr.acinq.bitcoin.Base58Check
import fr.acinq.bitcoin.PublicKey
import fr.acinq.secp256k1.Hex

internal fun trxAddressFromPublicKey(publicKey: PublicKey): String {
    val uncompressed = publicKey.toUncompressedBin()
    val pubKeyNoPrefix = uncompressed.copyOfRange(1, 65)
    val hash = keccak256(pubKeyNoPrefix)
    val addressBytes = hash.copyOfRange(12, 32)
    return Base58Check.encode(0x41.toByte(), addressBytes)
}

internal fun trxToSun(trxAmount: BigDecimal): Long {
    return trxAmount.multiply(BigDecimal.fromLong(1_000_000)).longValue()
}

internal fun sunToTrx(sunAmount: BigDecimal): BigDecimal {
    return sunAmount.divide(BigDecimal.fromLong(1_000_000))
}

internal fun base58ToHexAddress(base58Address: String): String {
    val (prefix, payload) = Base58Check.decode(base58Address)
    require(prefix == 0x41.toByte()) { "Invalid TRX base58 address: prefix must be 0x41" }
    return Hex.encode(byteArrayOf(prefix) + payload)
}

internal fun hexToBase58Address(hexAddress: String): String {
    val bytes = Hex.decode(hexAddress)
    require(bytes.size == 21 && bytes[0] == 0x41.toByte()) {
        "Invalid TRX hex address: must be 21 bytes starting with 0x41"
    }
    return Base58Check.encode(bytes[0], bytes.copyOfRange(1, 21))
}
