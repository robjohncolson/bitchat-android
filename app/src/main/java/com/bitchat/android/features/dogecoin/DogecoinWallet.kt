package com.bitchat.android.features.dogecoin

import org.bouncycastle.crypto.digests.RIPEMD160Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import java.math.BigInteger
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

data class DogecoinWalletKey(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val address: String,
    val wif: String,
    val network: DogecoinNetwork,
    val isCompressed: Boolean = true
)

enum class DogecoinNetwork(
    val id: String,
    val displayName: String,
    val p2pkhAddressHeader: Int,
    val p2shAddressHeader: Int,
    val wifPrivateKeyHeader: Int,
    val defaultRpcUrl: String,
    val chainName: String
) {
    MAINNET(
        id = "mainnet",
        displayName = "mainnet",
        p2pkhAddressHeader = 30,
        p2shAddressHeader = 22,
        wifPrivateKeyHeader = 158,
        defaultRpcUrl = "http://10.0.2.2:22555",
        chainName = "main"
    ),
    TESTNET(
        id = "testnet",
        displayName = "testnet",
        p2pkhAddressHeader = 113,
        p2shAddressHeader = 196,
        wifPrivateKeyHeader = 241,
        defaultRpcUrl = "http://10.0.2.2:44555",
        chainName = "test"
    ),

    /**
     * Local regtest. Not offered in the wallet UI (see DogecoinWalletSheet network list); it
     * exists for the integration harness so the full build/sign/broadcast pipeline can be proven
     * deterministically against a local node. Dogecoin regtest uses its own base58 prefixes
     * (pubkey 111, script 196, secret 239) and RPC port 18332 (P2P 18444); chain is "regtest".
     */
    REGTEST(
        id = "regtest",
        displayName = "regtest",
        p2pkhAddressHeader = 111,
        p2shAddressHeader = 196,
        wifPrivateKeyHeader = 239,
        defaultRpcUrl = "http://10.0.2.2:18332",
        chainName = "regtest"
    );

    companion object {
        val DEFAULT = MAINNET

        fun fromId(id: String?): DogecoinNetwork {
            return values().firstOrNull { it.id == id } ?: DEFAULT
        }
    }
}

object DogecoinProtocol {
    const val KOINU_PER_DOGE = 100_000_000L
    const val DEFAULT_FEE_PER_KB_KOINU = 1_000_000L
    const val MIN_TX_FEE_KOINU = 1_000_000L
    const val MIN_STANDARD_OUTPUT_KOINU = 1_000_000L
    const val DUST_CHANGE_KOINU = MIN_STANDARD_OUTPUT_KOINU
    const val HIGH_FEE_ABSOLUTE_KOINU = KOINU_PER_DOGE
    const val HIGH_FEE_RELATIVE_DENOMINATOR = 10L

    fun createPaymentUri(
        network: DogecoinNetwork,
        address: String,
        amount: String? = null,
        label: String? = null,
        message: String? = null
    ): String {
        require(DogecoinAddress.isValidAddress(address, network)) {
            "Invalid Dogecoin ${network.displayName} address"
        }

        val params = mutableListOf<String>()
        val cleanAmount = amount?.trim().orEmpty()
        if (cleanAmount.isNotEmpty()) {
            require(DogecoinAmount.isValidAmount(cleanAmount)) { "Invalid Dogecoin amount" }
            require(DogecoinAmount.toKoinu(cleanAmount) >= MIN_STANDARD_OUTPUT_KOINU) {
                "Dogecoin amount must be at least ${DogecoinAmount.formatKoinu(MIN_STANDARD_OUTPUT_KOINU)} DOGE"
            }
            params.add("amount=${percentEncode(cleanAmount)}")
        }

        val cleanLabel = label?.trim().orEmpty()
        if (cleanLabel.isNotEmpty()) {
            params.add("label=${percentEncode(cleanLabel)}")
        }

        val cleanMessage = message?.trim().orEmpty()
        if (cleanMessage.isNotEmpty()) {
            params.add("message=${percentEncode(cleanMessage)}")
        }

        return buildString {
            append("dogecoin:")
            append(address)
            if (params.isNotEmpty()) {
                append("?")
                append(params.joinToString("&"))
            }
        }
    }

    private fun percentEncode(value: String): String {
        return URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
    }
}

object DogecoinAmount {
    private val amountRegex = Regex("^[0-9]+(?:\\.[0-9]{1,8})?$")

    fun isValidAmount(value: String): Boolean {
        val trimmed = value.trim()
        if (!amountRegex.matches(trimmed)) return false
        val amount = trimmed.toBigDecimalOrNull() ?: return false
        if (amount.signum() <= 0) return false
        return toKoinuOrNull(amount) != null
    }

    fun isValidFeePerKb(value: String): Boolean {
        if (!isValidAmount(value)) return false
        return toKoinu(value) >= DogecoinProtocol.MIN_TX_FEE_KOINU
    }

    fun isStandardOutputAmount(value: String): Boolean {
        if (!isValidAmount(value)) return false
        return toKoinu(value) >= DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
    }

    fun toKoinu(value: String): Long {
        require(isValidAmount(value)) { "Invalid Dogecoin amount" }
        return toKoinuOrNull(value.trim().toBigDecimal()) ?: error("Validated Dogecoin amount was not representable")
    }

    fun formatKoinu(koinu: Long): String {
        return BigDecimal.valueOf(koinu, 8)
            .stripTrailingZeros()
            .toPlainString()
    }

    private fun toKoinuOrNull(amount: BigDecimal): Long? {
        return runCatching {
            amount
                .movePointRight(8)
                .setScale(0, RoundingMode.UNNECESSARY)
                .longValueExact()
        }.getOrNull()
    }
}

enum class DogecoinAddressType {
    P2PKH,
    P2SH
}

object DogecoinAddress {
    fun isValidAddress(address: String, network: DogecoinNetwork): Boolean {
        return addressType(address, network) != null
    }

    fun isValidP2pkhAddress(address: String, network: DogecoinNetwork): Boolean {
        return p2pkhHash(address, network) != null
    }

    fun addressType(address: String, network: DogecoinNetwork): DogecoinAddressType? {
        val decoded = decodeAddress(address) ?: return null
        return when (decoded[0].toInt() and 0xff) {
            network.p2pkhAddressHeader -> DogecoinAddressType.P2PKH
            network.p2shAddressHeader -> DogecoinAddressType.P2SH
            else -> null
        }
    }

    fun p2pkhHash(address: String, network: DogecoinNetwork): ByteArray? {
        val decoded = decodeAddress(address) ?: return null
        if (decoded[0].toInt() and 0xff != network.p2pkhAddressHeader) return null
        return decoded.copyOfRange(1, decoded.size)
    }

    fun p2shHash(address: String, network: DogecoinNetwork): ByteArray? {
        val decoded = decodeAddress(address) ?: return null
        if (decoded[0].toInt() and 0xff != network.p2shAddressHeader) return null
        return decoded.copyOfRange(1, decoded.size)
    }

    fun p2pkhScript(address: String, network: DogecoinNetwork): ByteArray {
        val pubKeyHash = p2pkhHash(address, network)
            ?: throw IllegalArgumentException("Invalid Dogecoin ${network.displayName} P2PKH address")
        return byteArrayOf(0x76, 0xa9.toByte(), 0x14) + pubKeyHash + byteArrayOf(0x88.toByte(), 0xac.toByte())
    }

    fun scriptPubKey(address: String, network: DogecoinNetwork): ByteArray {
        p2pkhHash(address, network)?.let { pubKeyHash ->
            return byteArrayOf(0x76, 0xa9.toByte(), 0x14) +
                pubKeyHash +
                byteArrayOf(0x88.toByte(), 0xac.toByte())
        }

        p2shHash(address, network)?.let { scriptHash ->
            return byteArrayOf(0xa9.toByte(), 0x14) +
                scriptHash +
                byteArrayOf(0x87.toByte())
        }

        throw IllegalArgumentException("Invalid Dogecoin ${network.displayName} address")
    }

    private fun decodeAddress(address: String): ByteArray? {
        val decoded = runCatching { DogecoinBase58.decodeChecked(address) }.getOrNull() ?: return null
        if (decoded.size != 21) return null
        return decoded
    }
}

object DogecoinKeyGenerator {
    private val secureRandom = SecureRandom()
    private val curve = CustomNamedCurves.getByName("secp256k1")
    private val params = ECDomainParameters(curve.curve, curve.g, curve.n, curve.h)

    fun generate(network: DogecoinNetwork = DogecoinNetwork.DEFAULT): DogecoinWalletKey {
        val privateKey = generatePrivateKeyBytes()
        return fromPrivateKey(privateKey, network, compressed = true)
    }

    fun fromPrivateKeyHex(
        privateKeyHex: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT,
        compressed: Boolean = true
    ): DogecoinWalletKey {
        val privateKey = DogecoinHex.decode(privateKeyHex)
        require(privateKey.size == 32) { "Private key must be 32 bytes" }
        require(isValidPrivateKey(privateKey)) { "Invalid secp256k1 private key" }
        return fromPrivateKey(privateKey, network, compressed)
    }

    fun fromWif(wif: String, expectedNetwork: DogecoinNetwork? = null): DogecoinWalletKey {
        val decoded = DogecoinBase58.decodeChecked(wif.trim())
        require(decoded.size == 33 || decoded.size == 34) { "Invalid Dogecoin WIF key length" }

        val version = decoded[0].toInt() and 0xff
        val network = DogecoinNetwork.values().firstOrNull { it.wifPrivateKeyHeader == version }
            ?: throw IllegalArgumentException("Unsupported Dogecoin WIF network")
        if (expectedNetwork != null) {
            require(network == expectedNetwork) {
                "WIF belongs to Dogecoin ${network.displayName}, not ${expectedNetwork.displayName}"
            }
        }

        val compressed = decoded.size == 34
        if (compressed) {
            require((decoded.last().toInt() and 0xff) == 0x01) {
                "Invalid compressed Dogecoin WIF marker"
            }
        }

        val privateKey = decoded.copyOfRange(1, 33)
        require(isValidPrivateKey(privateKey)) { "Invalid secp256k1 private key" }
        return fromPrivateKey(privateKey, network, compressed)
    }

    private fun fromPrivateKey(
        privateKey: ByteArray,
        network: DogecoinNetwork,
        compressed: Boolean
    ): DogecoinWalletKey {
        val publicKey = publicKey(privateKey, compressed)
        val publicKeyHash = hash160(publicKey)
        val address = DogecoinBase58.encodeChecked(
            version = network.p2pkhAddressHeader,
            payload = publicKeyHash
        )
        val wif = DogecoinBase58.encodeChecked(
            version = network.wifPrivateKeyHeader,
            payload = if (compressed) privateKey + byteArrayOf(0x01) else privateKey
        )
        return DogecoinWalletKey(
            privateKeyHex = DogecoinHex.encode(privateKey),
            publicKeyHex = DogecoinHex.encode(publicKey),
            address = address,
            wif = wif,
            network = network,
            isCompressed = compressed
        )
    }

    private fun generatePrivateKeyBytes(): ByteArray {
        while (true) {
            val candidate = ByteArray(32)
            secureRandom.nextBytes(candidate)
            if (isValidPrivateKey(candidate)) return candidate
        }
    }

    private fun isValidPrivateKey(privateKey: ByteArray): Boolean {
        val value = BigInteger(1, privateKey)
        return value > BigInteger.ZERO && value < params.n
    }

    private fun publicKey(privateKey: ByteArray, compressed: Boolean): ByteArray {
        val privateKeyBigInt = BigInteger(1, privateKey)
        val point = params.g.multiply(privateKeyBigInt).normalize()
        val x = point.xCoord.encoded.padTo32Bytes()
        val y = point.yCoord.toBigInteger()
        if (!compressed) {
            return byteArrayOf(0x04) + x + point.yCoord.encoded.padTo32Bytes()
        }

        val prefix = if (y.testBit(0)) 0x03.toByte() else 0x02.toByte()
        return byteArrayOf(prefix) + x
    }

    private fun hash160(input: ByteArray): ByteArray {
        val sha256 = MessageDigest.getInstance("SHA-256").digest(input)
        val digest = RIPEMD160Digest()
        digest.update(sha256, 0, sha256.size)
        val out = ByteArray(20)
        digest.doFinal(out, 0)
        return out
    }
}

object DogecoinHex {
    fun encode(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }

    fun decode(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Hex value must have an even length" }
        require(clean.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Hex value contains non-hex characters"
        }
        return clean.chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}

object DogecoinBase58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val alphabetIndexes = IntArray(128) { -1 }.apply {
        ALPHABET.forEachIndexed { index, char -> this[char.code] = index }
    }

    fun encodeChecked(version: Int, payload: ByteArray): String {
        require(version in 0..255) { "Version must fit in one byte" }
        val versionedPayload = byteArrayOf(version.toByte()) + payload
        val checksum = checksum(versionedPayload)
        return encode(versionedPayload + checksum)
    }

    fun decodeChecked(value: String): ByteArray {
        val decoded = decode(value)
        require(decoded.size >= 5) { "Base58Check value is too short" }
        val payload = decoded.copyOfRange(0, decoded.size - 4)
        val checksum = decoded.copyOfRange(decoded.size - 4, decoded.size)
        require(checksum.contentEquals(checksum(payload))) { "Invalid Base58Check checksum" }
        return payload
    }

    private fun encode(input: ByteArray): String {
        if (input.isEmpty()) return ""

        var zeros = 0
        while (zeros < input.size && input[zeros].toInt() == 0) zeros++

        var value = BigInteger(1, input)
        val encoded = StringBuilder()
        val base = BigInteger.valueOf(58)
        while (value > BigInteger.ZERO) {
            val divRem = value.divideAndRemainder(base)
            value = divRem[0]
            encoded.append(ALPHABET[divRem[1].toInt()])
        }

        repeat(zeros) { encoded.append(ALPHABET[0]) }
        return encoded.reverse().toString()
    }

    private fun decode(input: String): ByteArray {
        require(input.isNotEmpty()) { "Base58 value is empty" }

        var value = BigInteger.ZERO
        val base = BigInteger.valueOf(58)
        input.forEach { char ->
            require(char.code < 128 && alphabetIndexes[char.code] >= 0) {
                "Invalid Base58 character: $char"
            }
            value = value.multiply(base).add(BigInteger.valueOf(alphabetIndexes[char.code].toLong()))
        }

        var decoded = value.toByteArray()
        if (decoded.size > 1 && decoded[0].toInt() == 0) {
            decoded = decoded.copyOfRange(1, decoded.size)
        }

        val leadingZeros = input.takeWhile { it == ALPHABET[0] }.length
        return ByteArray(leadingZeros) + decoded
    }

    private fun checksum(payload: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val first = digest.digest(payload)
        val second = digest.digest(first)
        return second.copyOfRange(0, 4)
    }
}

private fun ByteArray.padTo32Bytes(): ByteArray {
    if (size == 32) return this
    require(size <= 32) { "Value is larger than 32 bytes" }
    return ByteArray(32 - size) + this
}
