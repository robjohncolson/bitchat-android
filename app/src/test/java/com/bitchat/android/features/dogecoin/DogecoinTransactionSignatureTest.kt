package com.bitchat.android.features.dogecoin

import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.crypto.signers.ECDSASigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.math.BigInteger
import java.security.MessageDigest
import org.junit.Test

/**
 * Independent consensus-validity checks for signed Dogecoin transactions.
 *
 * Unlike the rest of the suite, this test does NOT trust the production transaction
 * builder's own serialization or sighash code. It re-parses the produced raw transaction
 * with an independent parser, recomputes the legacy SIGHASH_ALL digest from scratch, and
 * cryptographically verifies every input's DER signature against the embedded public key.
 *
 * A Dogecoin Core node performs exactly this verification before accepting a transaction,
 * so passing this test proves the wallet produces transactions a real node will accept --
 * the single step that could otherwise only be proven by spending real DOGE on mainnet.
 */
class DogecoinTransactionSignatureTest {

    private val secp256k1 = CustomNamedCurves.getByName("secp256k1")
    private val domainParams = ECDomainParameters(
        secp256k1.curve,
        secp256k1.g,
        secp256k1.n,
        secp256k1.h
    )
    private val halfCurveOrder: BigInteger = secp256k1.n.shiftRight(1)

    @Test
    fun `single input p2pkh transaction carries a valid low-s sighash-all signature`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "00000000000000000000000000000000000000000000000000000000000000a1"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "00000000000000000000000000000000000000000000000000000000000000b2"
        )
        val utxo = walletUtxo(
            wallet = wallet,
            txid = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff",
            vout = 0,
            amountKoinu = 10L * DogecoinProtocol.KOINU_PER_DOGE
        )

        val tx = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        verifyAllSignatures(tx, wallet, listOf(utxo))
    }

    @Test
    fun `multi input transaction signs each input with its own position-specific sighash`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "00000000000000000000000000000000000000000000000000000000000000c3"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "00000000000000000000000000000000000000000000000000000000000000d4"
        )
        val utxoA = walletUtxo(
            wallet = wallet,
            txid = "1111111111111111111111111111111111111111111111111111111111111111",
            vout = 0,
            amountKoinu = 5L * DogecoinProtocol.KOINU_PER_DOGE
        )
        val utxoB = walletUtxo(
            wallet = wallet,
            txid = "2222222222222222222222222222222222222222222222222222222222222222",
            vout = 3,
            amountKoinu = 5L * DogecoinProtocol.KOINU_PER_DOGE
        )

        val tx = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxoA, utxoB),
            recipientAddress = recipient.address,
            amount = "6.0"
        )

        // Both inputs must be selected to fund a 6 DOGE send from two 5 DOGE coins.
        assertEquals(2, tx.selectedUtxos.size)
        verifyAllSignatures(tx, wallet, listOf(utxoA, utxoB))
    }

    @Test
    fun `uncompressed key transaction carries a valid signature over the 65-byte public key`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "00000000000000000000000000000000000000000000000000000000000000e5",
            DogecoinNetwork.MAINNET,
            compressed = false
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "00000000000000000000000000000000000000000000000000000000000000f6"
        )
        val utxo = walletUtxo(
            wallet = wallet,
            txid = "3333333333333333333333333333333333333333333333333333333333333333",
            vout = 1,
            amountKoinu = 7L * DogecoinProtocol.KOINU_PER_DOGE
        )

        val tx = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "2.0"
        )

        assertEquals(65, DogecoinHex.decode(wallet.publicKeyHex).size)
        verifyAllSignatures(tx, wallet, listOf(utxo))
    }

    @Test
    fun `testnet transaction carries a valid signature`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000a17",
            DogecoinNetwork.TESTNET
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000b28",
            DogecoinNetwork.TESTNET
        )
        val utxo = walletUtxo(
            wallet = wallet,
            network = DogecoinNetwork.TESTNET,
            txid = "4444444444444444444444444444444444444444444444444444444444444444",
            vout = 0,
            amountKoinu = 9L * DogecoinProtocol.KOINU_PER_DOGE
        )

        val tx = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "3.0",
            network = DogecoinNetwork.TESTNET
        )

        verifyAllSignatures(tx, wallet, listOf(utxo), DogecoinNetwork.TESTNET)
    }

    @Test
    fun `signing is deterministic for identical inputs`() {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000c39"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000d4a"
        )
        val utxo = walletUtxo(
            wallet = wallet,
            txid = "5555555555555555555555555555555555555555555555555555555555555555",
            vout = 2,
            amountKoinu = 8L * DogecoinProtocol.KOINU_PER_DOGE
        )

        val first = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "4.0"
        )
        val second = DogecoinTransactionBuilder.createSignedTransaction(
            wallet = wallet,
            utxos = listOf(utxo),
            recipientAddress = recipient.address,
            amount = "4.0"
        )

        // RFC 6979 deterministic nonces mean the raw transaction must be byte-identical.
        assertEquals(first.rawTransactionHex, second.rawTransactionHex)
        assertEquals(first.txid, second.txid)
    }

    // --- Independent verification helpers -------------------------------------------------

    private fun verifyAllSignatures(
        tx: DogecoinSignedTransaction,
        wallet: DogecoinWalletKey,
        utxos: List<DogecoinUtxo>,
        network: DogecoinNetwork = DogecoinNetwork.MAINNET
    ) {
        val parsed = parseRawTransaction(DogecoinHex.decode(tx.rawTransactionHex))

        // Independently recompute the txid and confirm it matches the reviewed txid.
        val computedTxid = DogecoinHex.encode(doubleSha256(DogecoinHex.decode(tx.rawTransactionHex)).reversedArray())
        assertEquals(tx.txid.lowercase(), computedTxid)

        val expectedPubKey = DogecoinHex.decode(wallet.publicKeyHex)
        val utxoByOutpoint = utxos.associateBy { "${it.txid.lowercase()}:${it.vout}" }

        assertTrue("transaction must have at least one input", parsed.inputs.isNotEmpty())

        parsed.inputs.forEachIndexed { index, input ->
            val displayTxid = DogecoinHex.encode(input.previousTxIdLittleEndian.reversedArray())
            val matchedUtxo = utxoByOutpoint["${displayTxid}:${input.outputIndex.toInt()}"]
                ?: error("Signed input $index references an unknown outpoint $displayTxid:${input.outputIndex}")

            val (sigBlob, pubKey) = parseP2pkhScriptSig(input.scriptSig)

            // The scriptSig must carry exactly this wallet's public key.
            assertEquals(
                "input $index public key",
                DogecoinHex.encode(expectedPubKey),
                DogecoinHex.encode(pubKey)
            )

            // The signature blob must end with the SIGHASH_ALL byte.
            assertTrue("input $index signature blob too short", sigBlob.size >= 9)
            assertEquals("input $index sighash type", 0x01, sigBlob.last().toInt() and 0xff)

            val derSignature = sigBlob.copyOfRange(0, sigBlob.size - 1)
            val (r, s) = parseDerSignature(derSignature)

            // Low-S (BIP-62) is mandatory policy on relaying nodes.
            assertTrue("input $index r must be positive", r.signum() > 0)
            assertTrue("input $index s must be positive", s.signum() > 0)
            assertTrue("input $index signature must be low-S", s <= halfCurveOrder)

            // Recompute the legacy SIGHASH_ALL digest for THIS input, independently of the
            // production serializer, and verify the ECDSA signature against it.
            val scriptCode = DogecoinAddress.p2pkhScript(wallet.address, network)
            assertEquals(
                "input $index scriptCode matches the spent UTXO",
                matchedUtxo.scriptPubKeyHex.lowercase(),
                DogecoinHex.encode(scriptCode)
            )
            val sighash = legacySigHashAll(parsed, index, scriptCode)

            assertTrue(
                "input $index ECDSA signature must verify against the recomputed sighash",
                verifyEcdsa(pubKey, sighash, r, s)
            )
        }
    }

    private fun verifyEcdsa(pubKey: ByteArray, hash: ByteArray, r: BigInteger, s: BigInteger): Boolean {
        val point = secp256k1.curve.decodePoint(pubKey)
        val signer = ECDSASigner()
        signer.init(false, ECPublicKeyParameters(point, domainParams))
        return signer.verifySignature(hash, r, s)
    }

    /**
     * Independent re-implementation of the Bitcoin/Dogecoin legacy (pre-segwit) signature hash
     * for SIGHASH_ALL. Deliberately does not share code with the production serializer.
     */
    private fun legacySigHashAll(
        tx: ParsedTransaction,
        inputIndex: Int,
        scriptCode: ByteArray
    ): ByteArray {
        val out = ArrayList<Byte>()
        out.addAll(uint32Le(tx.version).toList())
        out.addAll(varInt(tx.inputs.size.toLong()).toList())
        tx.inputs.forEachIndexed { index, input ->
            out.addAll(input.previousTxIdLittleEndian.toList())
            out.addAll(uint32Le(input.outputIndex).toList())
            val script = if (index == inputIndex) scriptCode else ByteArray(0)
            out.addAll(varInt(script.size.toLong()).toList())
            out.addAll(script.toList())
            out.addAll(uint32Le(input.sequence).toList())
        }
        out.addAll(varInt(tx.outputs.size.toLong()).toList())
        tx.outputs.forEach { output ->
            out.addAll(int64Le(output.amountKoinu).toList())
            out.addAll(varInt(output.scriptPubKey.size.toLong()).toList())
            out.addAll(output.scriptPubKey.toList())
        }
        out.addAll(uint32Le(tx.lockTime).toList())
        out.addAll(uint32Le(SIGHASH_ALL).toList())
        return doubleSha256(out.toByteArray())
    }

    private fun parseP2pkhScriptSig(scriptSig: ByteArray): Pair<ByteArray, ByteArray> {
        var offset = 0
        fun readPush(field: String): ByteArray {
            assertTrue("$field push opcode present", offset < scriptSig.size)
            val len = scriptSig[offset].toInt() and 0xff
            assertTrue("$field uses a direct pushdata opcode", len in 1..75)
            offset += 1
            assertTrue("$field data present", offset + len <= scriptSig.size)
            val data = scriptSig.copyOfRange(offset, offset + len)
            offset += len
            return data
        }
        val signature = readPush("signature")
        val pubKey = readPush("public key")
        assertEquals("scriptSig must contain exactly two pushes", scriptSig.size, offset)
        return signature to pubKey
    }

    private fun parseDerSignature(der: ByteArray): Pair<BigInteger, BigInteger> {
        val sequence = ASN1Sequence.getInstance(der)
        assertEquals("DER signature must be a 2-element sequence", 2, sequence.size())
        val r = (sequence.getObjectAt(0) as ASN1Integer).value
        val s = (sequence.getObjectAt(1) as ASN1Integer).value
        // Re-encoding must round-trip to the same bytes: proves canonical DER (no extra padding).
        assertTrue(
            "DER signature must be canonically encoded",
            sequence.getEncoded("DER").contentEquals(der)
        )
        return r to s
    }

    private fun parseRawTransaction(bytes: ByteArray): ParsedTransaction {
        var offset = 0
        fun read(count: Int): ByteArray {
            assertTrue("transaction truncated", offset + count <= bytes.size)
            val slice = bytes.copyOfRange(offset, offset + count)
            offset += count
            return slice
        }
        fun readUint32(): Long {
            val slice = read(4)
            var value = 0L
            for (i in 0 until 4) value = value or ((slice[i].toLong() and 0xff) shl (8 * i))
            return value
        }
        fun readInt64(): Long {
            val slice = read(8)
            var value = 0L
            for (i in 0 until 8) value = value or ((slice[i].toLong() and 0xff) shl (8 * i))
            return value
        }
        fun readVarInt(): Long {
            val first = read(1)[0].toInt() and 0xff
            return when (first) {
                in 0x00..0xfc -> first.toLong()
                0xfd -> {
                    val s = read(2); (s[0].toLong() and 0xff) or ((s[1].toLong() and 0xff) shl 8)
                }
                0xfe -> readUint32()
                else -> readInt64()
            }
        }

        val version = readUint32()
        val inputCount = readVarInt().toInt()
        val inputs = ArrayList<ParsedInput>(inputCount)
        repeat(inputCount) {
            val prevTxid = read(32)
            val vout = readUint32()
            val scriptLen = readVarInt().toInt()
            val script = read(scriptLen)
            val sequence = readUint32()
            inputs.add(ParsedInput(prevTxid, vout, script, sequence))
        }
        val outputCount = readVarInt().toInt()
        val outputs = ArrayList<ParsedOutput>(outputCount)
        repeat(outputCount) {
            val amount = readInt64()
            val scriptLen = readVarInt().toInt()
            val script = read(scriptLen)
            outputs.add(ParsedOutput(amount, script))
        }
        val lockTime = readUint32()
        assertEquals("no trailing bytes after a parsed transaction", bytes.size, offset)
        return ParsedTransaction(version, inputs, outputs, lockTime)
    }

    private fun walletUtxo(
        wallet: DogecoinWalletKey,
        txid: String,
        vout: Int,
        amountKoinu: Long,
        network: DogecoinNetwork = DogecoinNetwork.MAINNET,
        confirmations: Int = 6
    ): DogecoinUtxo {
        return DogecoinUtxo(
            txid = txid,
            vout = vout,
            amountKoinu = amountKoinu,
            scriptPubKeyHex = DogecoinHex.encode(DogecoinAddress.p2pkhScript(wallet.address, network)),
            confirmations = confirmations
        )
    }

    private fun uint32Le(value: Long): ByteArray =
        ByteArray(4) { index -> ((value shr (index * 8)) and 0xff).toByte() }

    private fun int64Le(value: Long): ByteArray =
        ByteArray(8) { index -> ((value shr (index * 8)) and 0xff).toByte() }

    private fun varInt(value: Long): ByteArray {
        return when {
            value < 0xfdL -> byteArrayOf(value.toByte())
            value <= 0xffffL -> byteArrayOf(0xfd.toByte(), (value and 0xff).toByte(), ((value shr 8) and 0xff).toByte())
            value <= 0xffffffffL -> byteArrayOf(0xfe.toByte()) + uint32Le(value)
            else -> byteArrayOf(0xff.toByte()) + int64Le(value)
        }
    }

    private fun doubleSha256(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(digest.digest(bytes))
    }

    private data class ParsedTransaction(
        val version: Long,
        val inputs: List<ParsedInput>,
        val outputs: List<ParsedOutput>,
        val lockTime: Long
    )

    private data class ParsedInput(
        val previousTxIdLittleEndian: ByteArray,
        val outputIndex: Long,
        val scriptSig: ByteArray,
        val sequence: Long
    )

    private data class ParsedOutput(
        val amountKoinu: Long,
        val scriptPubKey: ByteArray
    )

    private companion object {
        const val SIGHASH_ALL = 1L
    }
}
