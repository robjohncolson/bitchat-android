package com.bitchat.android.model

import java.io.ByteArrayOutputStream

/**
 * Broadcast-over-mesh (Milestone 3b) wire types.
 *
 * A sender that cannot reach its own Dogecoin node ships an already-signed raw transaction inside a
 * NOISE_ENCRYPTED [NoisePayload] (`PAYMENT_BROADCAST_REQUEST` 0x30) to an opt-in helper peer who runs
 * the transaction through its own node's `sendrawtransaction` and replies with the node-verified txid
 * or a structured rejection (`PAYMENT_BROADCAST_RESULT` 0x31).
 *
 * The tx is fully signed before it leaves the sender, so the helper cannot alter outputs or forge a
 * different transaction — the worst it can do is delay or refuse. See the helper (`BroadcastHelperService`)
 * and the sender coordinator (`PaymentBroadcastCoordinator`).
 *
 * Framing: each field is a TLV of `[type:1][len:4 big-endian][value]`. A 4-byte length (vs the 1-byte
 * length used by the announce TLVs) is used because a raw tx can exceed 255 bytes; 4 bytes also avoids
 * the 65 535-byte ceiling a 2-byte length would impose. Decoders tolerantly skip unknown TLV types for
 * forward compatibility. The whole packet is bounded by [MAX_PACKET_BYTES] before parsing.
 */
private const val PB_PROTOCOL_VERSION = 1

/** Hard cap on the raw-tx hex string (~100 KB transaction). Abuse/DoS bound, enforced in decode(). */
const val PAYMENT_BROADCAST_MAX_RAW_TX_HEX = 200_000
private const val MAX_PACKET_BYTES = PAYMENT_BROADCAST_MAX_RAW_TX_HEX + 1024
private const val UUID_SIZE = 16
private const val TXID_HEX_LEN = 64
private const val MAX_NETWORK_ID_BYTES = 32
private const val MAX_REJECT_DETAIL_BYTES = 255

private val hexRegex = Regex("^[0-9a-f]+$")
private val txidHexRegex = Regex("^[0-9a-f]{64}$")

/** Status of a broadcast-over-mesh result. */
enum class PaymentBroadcastStatus(val value: UByte) {
    ACCEPTED(0x01u),   // helper's node accepted the tx; txid present and node-verified
    REJECTED(0x02u),   // helper's node refused the tx (see rejectCode)
    DECLINED(0x03u);   // helper opted out / rate-limited / wrong network / shape-invalid -> try another helper

    companion object {
        fun fromValue(value: UByte): PaymentBroadcastStatus? = values().find { it.value == value }
    }
}

/** Why a helper's node rejected a transaction, mapped from the node's error message. */
enum class PaymentBroadcastRejectCode(val value: UByte) {
    MISSING_INPUTS(0x01u),
    INSUFFICIENT_FEE(0x02u),
    DUST(0x03u),
    ALREADY_KNOWN(0x04u),   // node already has this tx -> the sender treats this as success
    SCRIPT_VERIFY(0x05u),
    NODE_NOT_READY(0x06u),
    SHAPE_INVALID(0x07u),
    OTHER(0xFFu);

    /**
     * True for reject reasons where the SAME signed bytes will be refused by every honest node, so
     * retrying to another helper is pointless. NOTE: a hostile helper can forge any reject code, so
     * the sender coordinator must still require a terminal reason to reproduce from an independent
     * helper before surfacing failure — see [PaymentBroadcastCoordinator].
     */
    val isTerminalForTransaction: Boolean
        get() = this == MISSING_INPUTS || this == INSUFFICIENT_FEE || this == DUST || this == SCRIPT_VERIFY

    companion object {
        fun fromValue(value: UByte): PaymentBroadcastRejectCode? = values().find { it.value == value }

        /**
         * Classify a Dogecoin node `sendrawtransaction` error message into a reject code. Mirrors the
         * substring matching in `DogecoinRpcClient.broadcastRpcErrorMessage`.
         */
        fun classify(nodeMessage: String): PaymentBroadcastRejectCode {
            val m = nodeMessage.lowercase()
            return when {
                m.contains("missing inputs") || m.contains("missingorspent") || m.contains("missing or spent") -> MISSING_INPUTS
                m.contains("insufficient fee") || m.contains("min relay fee") || m.contains("mempool min fee") || m.contains("fee not met") -> INSUFFICIENT_FEE
                m.contains("dust") -> DUST
                m.contains("already in block chain") || m.contains("already in blockchain") ||
                    m.contains("txn-already-in-mempool") || m.contains("already in mempool") || m.contains("already known") -> ALREADY_KNOWN
                m.contains("mandatory-script-verify") || m.contains("non-mandatory-script-verify") || m.contains("script verify") -> SCRIPT_VERIFY
                else -> OTHER
            }
        }
    }
}

private fun ByteArrayOutputStream.writeTlv(type: Int, value: ByteArray) {
    write(type and 0xff)
    val len = value.size
    write((len ushr 24) and 0xff)
    write((len ushr 16) and 0xff)
    write((len ushr 8) and 0xff)
    write(len and 0xff)
    write(value)
}

/** Parse `[type:1][len:4 BE][value]` TLVs, keeping the FIRST occurrence of each type. Null on malformed. */
private fun parseTlvs(data: ByteArray): Map<Int, ByteArray>? {
    val out = HashMap<Int, ByteArray>()
    var offset = 0
    while (offset < data.size) {
        if (offset + 5 > data.size) return null
        val type = data[offset].toInt() and 0xff
        // Read the length as a Long so the bounds arithmetic can never wrap a 32-bit Int: a crafted
        // 0x40000000..0x7FFFFFFF length is positive yet would overflow `offset + len` negative and slip
        // past an Int overrun check. The Long compare + the MAX_PACKET_BYTES cap reject it cleanly so
        // decode() honors its "null on malformed" contract (no throw, no oversized allocation).
        val len = ((data[offset + 1].toLong() and 0xff) shl 24) or
            ((data[offset + 2].toLong() and 0xff) shl 16) or
            ((data[offset + 3].toLong() and 0xff) shl 8) or
            (data[offset + 4].toLong() and 0xff)
        offset += 5
        if (len < 0L || len > MAX_PACKET_BYTES.toLong() || offset.toLong() + len > data.size.toLong()) return null
        val value = data.copyOfRange(offset, offset + len.toInt())
        offset += len.toInt()
        out.putIfAbsent(type, value)
    }
    return out
}

/**
 * `PAYMENT_BROADCAST_REQUEST` (0x30): sender -> helper. A signed raw tx to broadcast.
 */
data class PaymentBroadcastRequest(
    val requestUuid: ByteArray,       // 16 random bytes (SecureRandom), correlation + dedup key
    val networkId: String,            // DogecoinNetwork.id
    val rawTransactionHex: String,    // lowercase hex of the fully-signed transaction
    val expectedTxid: String,         // sender's locally-computed 64-hex txid (anti-substitution cross-check)
    val protocolVersion: Int = PB_PROTOCOL_VERSION
) {
    private enum class T(val v: Int) { UUID(0x00), NETWORK_ID(0x01), RAW_TX_HEX(0x02), EXPECTED_TXID(0x03), VERSION(0x04) }

    fun encode(): ByteArray? {
        if (requestUuid.size != UUID_SIZE) return null
        val network = networkId.trim().lowercase()
        val rawHex = rawTransactionHex.trim().lowercase()
        val txid = expectedTxid.trim().lowercase()
        val networkBytes = network.toByteArray(Charsets.UTF_8)
        if (network.isEmpty() || networkBytes.size > MAX_NETWORK_ID_BYTES) return null
        if (rawHex.isEmpty() || rawHex.length > PAYMENT_BROADCAST_MAX_RAW_TX_HEX) return null
        if (rawHex.length % 2 != 0 || !hexRegex.matches(rawHex)) return null
        if (!txidHexRegex.matches(txid)) return null

        val out = ByteArrayOutputStream()
        out.writeTlv(T.UUID.v, requestUuid)
        out.writeTlv(T.NETWORK_ID.v, networkBytes)
        out.writeTlv(T.RAW_TX_HEX.v, rawHex.toByteArray(Charsets.UTF_8))
        out.writeTlv(T.EXPECTED_TXID.v, txid.toByteArray(Charsets.UTF_8))
        out.writeTlv(T.VERSION.v, byteArrayOf((protocolVersion and 0xff).toByte()))
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentBroadcastRequest) return false
        return requestUuid.contentEquals(other.requestUuid) &&
            networkId == other.networkId &&
            rawTransactionHex == other.rawTransactionHex &&
            expectedTxid == other.expectedTxid &&
            protocolVersion == other.protocolVersion
    }

    override fun hashCode(): Int {
        var result = requestUuid.contentHashCode()
        result = 31 * result + networkId.hashCode()
        result = 31 * result + rawTransactionHex.hashCode()
        result = 31 * result + expectedTxid.hashCode()
        result = 31 * result + protocolVersion
        return result
    }

    companion object {
        fun decode(data: ByteArray): PaymentBroadcastRequest? {
            if (data.isEmpty() || data.size > MAX_PACKET_BYTES) return null
            val tlvs = parseTlvs(data) ?: return null

            val uuid = tlvs[T.UUID.v] ?: return null
            if (uuid.size != UUID_SIZE) return null
            val network = (tlvs[T.NETWORK_ID.v] ?: return null).toString(Charsets.UTF_8).trim().lowercase()
            if (network.isEmpty() || network.length > MAX_NETWORK_ID_BYTES) return null
            val rawHexBytes = tlvs[T.RAW_TX_HEX.v] ?: return null
            if (rawHexBytes.size > PAYMENT_BROADCAST_MAX_RAW_TX_HEX) return null
            val rawHex = rawHexBytes.toString(Charsets.UTF_8).trim().lowercase()
            if (rawHex.isEmpty() || rawHex.length % 2 != 0 || !hexRegex.matches(rawHex)) return null
            val txid = (tlvs[T.EXPECTED_TXID.v] ?: return null).toString(Charsets.UTF_8).trim().lowercase()
            if (!txidHexRegex.matches(txid)) return null
            val version = tlvs[T.VERSION.v]?.takeIf { it.size == 1 }?.let { it[0].toInt() and 0xff } ?: PB_PROTOCOL_VERSION

            return PaymentBroadcastRequest(uuid, network, rawHex, txid, version)
        }
    }
}

/**
 * `PAYMENT_BROADCAST_RESULT` (0x31): helper -> sender. The outcome of a broadcast request.
 */
data class PaymentBroadcastResult(
    val requestUuid: ByteArray,
    val status: PaymentBroadcastStatus,
    val txid: String? = null,                       // present iff ACCEPTED; node-verified
    val rejectCode: PaymentBroadcastRejectCode? = null, // present iff REJECTED
    val rejectDetail: String? = null,               // optional, truncated, attacker-controlled (never trust as authoritative)
    val protocolVersion: Int = PB_PROTOCOL_VERSION
) {
    private enum class T(val v: Int) { UUID(0x00), STATUS(0x01), TXID(0x02), REJECT_CODE(0x03), REJECT_DETAIL(0x04), VERSION(0x05) }

    fun encode(): ByteArray? {
        if (requestUuid.size != UUID_SIZE) return null
        if (status == PaymentBroadcastStatus.ACCEPTED && (txid == null || !txidHexRegex.matches(txid.lowercase()))) return null

        val out = ByteArrayOutputStream()
        out.writeTlv(T.UUID.v, requestUuid)
        out.writeTlv(T.STATUS.v, byteArrayOf(status.value.toByte()))
        if (status == PaymentBroadcastStatus.ACCEPTED && txid != null) {
            out.writeTlv(T.TXID.v, txid.lowercase().toByteArray(Charsets.UTF_8))
        }
        if (status == PaymentBroadcastStatus.REJECTED && rejectCode != null) {
            out.writeTlv(T.REJECT_CODE.v, byteArrayOf(rejectCode.value.toByte()))
        }
        rejectDetail?.takeIf { it.isNotBlank() }?.let {
            val bytes = it.toByteArray(Charsets.UTF_8)
            val capped = if (bytes.size > MAX_REJECT_DETAIL_BYTES) bytes.copyOfRange(0, MAX_REJECT_DETAIL_BYTES) else bytes
            out.writeTlv(T.REJECT_DETAIL.v, capped)
        }
        out.writeTlv(T.VERSION.v, byteArrayOf((protocolVersion and 0xff).toByte()))
        return out.toByteArray()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PaymentBroadcastResult) return false
        return requestUuid.contentEquals(other.requestUuid) &&
            status == other.status &&
            txid == other.txid &&
            rejectCode == other.rejectCode &&
            rejectDetail == other.rejectDetail &&
            protocolVersion == other.protocolVersion
    }

    override fun hashCode(): Int {
        var result = requestUuid.contentHashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + (txid?.hashCode() ?: 0)
        result = 31 * result + (rejectCode?.hashCode() ?: 0)
        result = 31 * result + (rejectDetail?.hashCode() ?: 0)
        result = 31 * result + protocolVersion
        return result
    }

    companion object {
        fun decode(data: ByteArray): PaymentBroadcastResult? {
            if (data.isEmpty() || data.size > MAX_PACKET_BYTES) return null
            val tlvs = parseTlvs(data) ?: return null

            val uuid = tlvs[T.UUID.v] ?: return null
            if (uuid.size != UUID_SIZE) return null
            val statusByte = tlvs[T.STATUS.v]?.takeIf { it.size == 1 } ?: return null
            val status = PaymentBroadcastStatus.fromValue(statusByte[0].toUByte()) ?: return null
            val txid = tlvs[T.TXID.v]?.toString(Charsets.UTF_8)?.trim()?.lowercase()?.takeIf { txidHexRegex.matches(it) }
            val rejectCode = tlvs[T.REJECT_CODE.v]?.takeIf { it.size == 1 }?.let { PaymentBroadcastRejectCode.fromValue(it[0].toUByte()) }
            val rejectDetail = tlvs[T.REJECT_DETAIL.v]?.toString(Charsets.UTF_8)?.trim()?.takeIf { it.isNotBlank() }
            val version = tlvs[T.VERSION.v]?.takeIf { it.size == 1 }?.let { it[0].toInt() and 0xff } ?: PB_PROTOCOL_VERSION

            // An ACCEPTED result without a valid txid is meaningless; reject it.
            if (status == PaymentBroadcastStatus.ACCEPTED && txid == null) return null

            return PaymentBroadcastResult(uuid, status, txid, rejectCode, rejectDetail, version)
        }
    }
}
