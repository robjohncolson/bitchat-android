package com.bitchat.android.features.dogecoin

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

class DogecoinTrustedPersonalNodeSubmissionRpcTest {
    private val gson = Gson()
    private val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
        DogecoinNetwork.MAINNET
    )
    private val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000002",
        DogecoinNetwork.MAINNET
    ).address
    private val profile = DogecoinTrustedPersonalNodeProfile(
        origin = "https://dogebox.tail1234.ts.net",
        network = DogecoinNetwork.MAINNET,
        androidAddress = wallet.address,
        coreWalletId = "bitchat-watch.dat",
        policyVersion = DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION,
        revision = 7L,
        authorizedAtMillis = 3_000L,
        rescanAttested = true,
        rescanAttestedAtMillis = 2_000L
    )
    private val credentials = DogecoinTrustedPersonalNodeCredentials("phone-user", "test-secret")
    private val tipHash = "aa".repeat(32)
    private val previousRawHex = previousTransactionHex(500_000_000L)
    private val previousTxid = DogecoinTransactionBuilder.transactionId(previousRawHex)
    private val scriptHex = DogecoinHex.encode(
        DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
    )

    private data class CapturedRequest(
        val method: String,
        val host: String,
        val path: String,
        val params: String
    )

    @Test
    fun `typed TPN submission rechecks then persists before one-route exact-byte disclosure`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request -> successfulResponse(request, review) }

        val result = client.submitTrustedPersonalNodeTransaction(
            profile = profile,
            credentials = credentials,
            authorization = review.authorization,
            review = review,
            requestIsCurrent = { true },
            hasPositiveIndependentSpendEvidence = { false },
            persistAndReserveBeforeDisclosure = { events += "persist" },
            markSignedBytesDisclosed = { events += "disclosed" }
        )

        assertEquals(review.txid, result.txid)
        assertEquals(
            listOf(
                "rpc:getnetworkinfo",
                "rpc:getwalletinfo",
                "rpc:validateaddress",
                "rpc:help",
                "rpc:getblockchaininfo",
                "rpc:gettxout",
                "rpc:getblockchaininfo",
                "persist",
                "disclosed",
                "rpc:testmempoolaccept",
                "rpc:sendrawtransaction"
            ),
            events
        )
        assertTrue(requests.all { it.host == "dogebox.tail1234.ts.net" })
        assertEquals(listOf(review.rawTransactionHex), rawTransactionParams(requests, "testmempoolaccept"))
        assertEquals(listOf(review.rawTransactionHex), rawTransactionParams(requests, "sendrawtransaction"))
        assertEquals("/wallet/bitchat-watch.dat", requests.first { it.method == "getwalletinfo" }.path)
        assertEquals("/", requests.first { it.method == "sendrawtransaction" }.path)
    }

    @Test
    fun `unknown reconciliation accepts exact wallet transaction bytes without disclosure`() = runTest {
        val review = frozenReview()
        val attempt = unknownAttempt(review)
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request ->
            if (request.method == "gettransaction") {
                response("""{"txid":"${review.txid}","hex":"${review.rawTransactionHex}"}""")
            } else {
                successfulResponse(request, review)
            }
        }

        val result = client.reconcileTrustedPersonalNodeTransaction(
            profile = profile,
            credentials = credentials,
            requestToken = reconciliationToken(),
            attempt = attempt,
            requestIsCurrent = { true }
        )

        assertEquals(review.txid, result?.txid)
        assertEquals(
            listOf(
                "rpc:getnetworkinfo",
                "rpc:getwalletinfo",
                "rpc:validateaddress",
                "rpc:help",
                "rpc:gettransaction"
            ),
            events
        )
        assertReadOnlyReconciliation(requests, review)
        assertEquals("/wallet/bitchat-watch.dat", requests.last().path)
    }

    @Test
    fun `unknown reconciliation accepts exact getrawtransaction fallback bytes`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request ->
            when (request.method) {
                "gettransaction" -> rpcError(-5)
                "getrawtransaction" -> response("\"${review.rawTransactionHex}\"")
                else -> successfulResponse(request, review)
            }
        }

        val result = client.reconcileTrustedPersonalNodeTransaction(
            profile,
            credentials,
            reconciliationToken(),
            unknownAttempt(review),
            requestIsCurrent = { true }
        )

        assertEquals(review.txid, result?.txid)
        assertEquals(
            listOf("gettransaction", "getrawtransaction"),
            requests.takeLast(2).map { it.method }
        )
        assertEquals("/", requests.last().path)
        assertReadOnlyReconciliation(requests, review)
    }

    @Test
    fun `unknown reconciliation treats structured code minus five absence as inconclusive`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request ->
            when (request.method) {
                "gettransaction", "getrawtransaction" -> rpcError(-5)
                else -> successfulResponse(request, review)
            }
        }

        val result = client.reconcileTrustedPersonalNodeTransaction(
            profile,
            credentials,
            reconciliationToken(),
            unknownAttempt(review),
            requestIsCurrent = { true }
        )

        assertNull(result)
        assertEquals(
            listOf("gettransaction", "getrawtransaction"),
            requests.takeLast(2).map { it.method }
        )
        assertReadOnlyReconciliation(requests, review)
    }

    @Test
    fun `unknown reconciliation rejects different returned transaction bytes`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request ->
            if (request.method == "gettransaction") {
                response("""{"txid":"${review.txid}","hex":"$previousRawHex"}""")
            } else {
                successfulResponse(request, review)
            }
        }

        val outcome = runCatching {
            client.reconcileTrustedPersonalNodeTransaction(
                profile,
                credentials,
                reconciliationToken(),
                unknownAttempt(review),
                requestIsCurrent = { true }
            )
        }

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("different transaction bytes"))
        assertReadOnlyReconciliation(requests, review)
    }

    @Test
    fun `mempool rejection occurs after durable disclosure marker and never cascades to send`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request ->
            if (request.method == "testmempoolaccept") {
                response(
                    """[{"txid":"${review.txid}","allowed":false,"reject-reason":"bad-txns-inputs-missingorspent"}]"""
                )
            } else {
                successfulResponse(request, review)
            }
        }

        val outcome = runCatching {
            client.submitTrustedPersonalNodeTransaction(
                profile,
                credentials,
                review.authorization,
                review,
                requestIsCurrent = { true },
                hasPositiveIndependentSpendEvidence = { false },
                persistAndReserveBeforeDisclosure = { events += "persist" },
                markSignedBytesDisclosed = { events += "disclosed" }
            )
        }

        assertTrue(outcome.isFailure)
        assertTrue(events.indexOf("persist") < events.indexOf("rpc:testmempoolaccept"))
        assertTrue(events.indexOf("disclosed") < events.indexOf("rpc:testmempoolaccept"))
        assertFalse(requests.any { it.method == "sendrawtransaction" })
    }

    @Test
    fun `changed selected outpoint fails before persistence or signed-byte RPC`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request ->
            if (request.method == "gettxout") {
                txOutResponse(amount = "4.99999999")
            } else {
                successfulResponse(request, review)
            }
        }

        val outcome = runCatching {
            client.submitTrustedPersonalNodeTransaction(
                profile,
                credentials,
                review.authorization,
                review,
                requestIsCurrent = { true },
                hasPositiveIndependentSpendEvidence = { false },
                persistAndReserveBeforeDisclosure = { events += "persist" },
                markSignedBytesDisclosed = { events += "disclosed" }
            )
        }

        assertTrue(outcome.isFailure)
        assertFalse(events.contains("persist"))
        assertFalse(events.contains("disclosed"))
        assertFalse(requests.any { it.method == "testmempoolaccept" || it.method == "sendrawtransaction" })
    }

    @Test
    fun `independent spent-input evidence vetoes after final checks and before disclosure`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests, events) { request -> successfulResponse(request, review) }

        val outcome = runCatching {
            client.submitTrustedPersonalNodeTransaction(
                profile = profile,
                credentials = credentials,
                authorization = review.authorization,
                review = review,
                requestIsCurrent = { true },
                hasPositiveIndependentSpendEvidence = { true },
                persistAndReserveBeforeDisclosure = { events += "persist" },
                markSignedBytesDisclosed = { events += "disclosed" }
            )
        }

        assertTrue(outcome.isFailure)
        assertTrue(
            outcome.exceptionOrNull()?.message.orEmpty()
                .contains("independently observed a selected trusted-node input being spent")
        )
        assertEquals("rpc:getblockchaininfo", events.last())
        assertFalse(events.contains("persist"))
        assertFalse(events.contains("disclosed"))
        assertFalse(requests.any { it.method == "testmempoolaccept" || it.method == "sendrawtransaction" })
    }

    @Test
    fun `lease revocation at disclosure cannot fall through to any HTTP broadcast route`() = runTest {
        val review = frozenReview()
        val events = mutableListOf<String>()
        val requests = mutableListOf<CapturedRequest>()
        var current = true
        val client = client(requests, events) { request -> successfulResponse(request, review) }

        val outcome = runCatching {
            client.submitTrustedPersonalNodeTransaction(
                profile,
                credentials,
                review.authorization,
                review,
                requestIsCurrent = { current },
                hasPositiveIndependentSpendEvidence = { false },
                persistAndReserveBeforeDisclosure = { events += "persist" },
                markSignedBytesDisclosed = {
                    events += "disclosed"
                    current = false
                }
            )
        }

        assertTrue(outcome.isFailure)
        assertTrue(events.takeLast(2) == listOf("persist", "disclosed"))
        assertFalse(requests.any { it.method == "testmempoolaccept" || it.method == "sendrawtransaction" })
    }

    @Test
    fun `generic mainnet RPC guard remains closed without issuing a request`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val events = mutableListOf<String>()
        val client = client(requests, events) { error("Generic mainnet RPC must not issue HTTP") }

        try {
            client.sendRawTransaction(
                DogecoinRpcConfig(url = profile.origin),
                frozenReview().rawTransactionHex,
                DogecoinNetwork.MAINNET
            )
            fail("Expected generic mainnet RPC refusal")
        } catch (error: IllegalStateException) {
            assertTrue(error.message.orEmpty().contains("Trusted personal node"))
        }
        assertTrue(requests.isEmpty())
    }

    private fun frozenReview(): DogecoinTrustedPersonalNodeFrozenReview {
        val expectedScript = DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        val verified = DogecoinVerifiedPrevout.verify(
            rawPreviousTransactionHex = previousRawHex,
            expectedTxid = previousTxid,
            vout = 0,
            expectedP2pkhScript = expectedScript,
            source = DogecoinTrustedPersonalNodePreviousTransactionSource.WALLET_GETTRANSACTION
        )
        val candidate = DogecoinTrustedPersonalNodeProofCandidate.verifiedAtTip(
            verifiedPrevout = verified,
            finalConfirmations = DOGECOIN_TPN_MIN_INPUT_CONFIRMATIONS,
            finalBestBlockHash = tipHash
        )
        val proof = DogecoinTrustedPersonalNodeProofSnapshot.complete(
            binding = profile.toSessionBinding(),
            capturedAtMonotonicMillis = 55L,
            startTip = DogecoinTrustedPersonalNodeBlockTip(100, tipHash),
            endTip = DogecoinTrustedPersonalNodeBlockTip(100, tipHash),
            proofCandidates = listOf(candidate),
            totalProofBytes = verified.previousTransactionByteCount
        )
        val holder = DogecoinTrustedPersonalNodeSessionHolder(
            savedState = DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            savedProfile = profile
        )
        val activation = requireNotNull(holder.beginActivation(50L))
        check(holder.recordSuccessfulReadSnapshot(activation, displaySnapshot(), 50L))
        val proofToken = requireNotNull(holder.beginProofSnapshot(55L))
        check(holder.recordSuccessfulProofSnapshot(proofToken, proof, 55L))
        val authorization = requireNotNull(holder.beginSpendAuthorization(55L))
        return DogecoinTrustedPersonalNodeTransactionBuilder.createFrozenReview(
            wallet = wallet,
            sessionHolder = holder,
            authorization = authorization,
            nowMonotonicMillis = 55L,
            recipientAddress = recipient,
            amount = "1.00000000"
        )
    }

    private fun unknownAttempt(
        review: DogecoinTrustedPersonalNodeFrozenReview
    ): DogecoinTrustedPersonalNodeAttempt {
        val record = DogecoinTrustedPersonalNodeAttemptReviewRecord.fromFrozenReview(
            review,
            mainnetAcknowledged = true,
            personalNodeOracleAcknowledged = true
        )
        return DogecoinTrustedPersonalNodeAttempt.create("ab".repeat(16), record)
            .withState(DogecoinTrustedPersonalNodeAttemptState.SUBMISSION_UNKNOWN)
    }

    private fun reconciliationToken(): DogecoinTrustedPersonalNodeProofRequestToken =
        DogecoinTrustedPersonalNodeProofRequestToken(
            nonce = 99L,
            binding = profile.toSessionBinding(),
            startedAtMonotonicMillis = 55L
        )

    private fun assertReadOnlyReconciliation(
        requests: List<CapturedRequest>,
        review: DogecoinTrustedPersonalNodeFrozenReview
    ) {
        assertTrue(requests.all { it.host == "dogebox.tail1234.ts.net" })
        assertFalse(requests.any { it.method == "testmempoolaccept" || it.method == "sendrawtransaction" })
        assertFalse(requests.any { it.params.contains(review.rawTransactionHex) })
    }

    private fun displaySnapshot(): DogecoinTrustedPersonalNodeDisplaySnapshot =
        DogecoinTrustedPersonalNodeDisplaySnapshot(
            profileRevision = profile.revision,
            origin = profile.origin,
            androidAddress = profile.androidAddress,
            coreWalletId = profile.coreWalletId,
            blocks = 100,
            headers = 100,
            verificationProgress = 1.0,
            peerCount = DogecoinRpcClient.DOGECOIN_TPN_MIN_MAINNET_PEERS,
            balance = DogecoinTrustedPersonalNodeDisplayBalance(
                confirmedKoinu = 500_000_000L,
                unconfirmedKoinu = 0L,
                utxoCount = 1
            ),
            activity = emptyList()
        )

    private fun client(
        requests: MutableList<CapturedRequest>,
        events: MutableList<String>,
        result: (CapturedRequest) -> String
    ): DogecoinRpcClient = DogecoinRpcClient(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val body = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            val json = gson.fromJson(body, JsonObject::class.java)
            val captured = CapturedRequest(
                method = json.get("method").asString,
                host = chain.request().url.host,
                path = chain.request().url.encodedPath,
                params = json.getAsJsonArray("params").toString()
            )
            requests += captured
            events += "rpc:${captured.method}"
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(result(captured).toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    )

    private fun successfulResponse(
        request: CapturedRequest,
        review: DogecoinTrustedPersonalNodeFrozenReview
    ): String = when (request.method) {
        "getnetworkinfo" -> response("""{"networkactive":true,"connections":4}""")
        "getwalletinfo" -> response("""{"walletname":"bitchat-watch.dat","scanning":false}""")
        "validateaddress" -> response(
            """{"isvalid":true,"address":"${wallet.address}","ismine":false,"iswatchonly":true}"""
        )
        "help" -> response("\"testmempoolaccept \\\"rawtx\\\"\"")
        "getblockchaininfo" -> tipResponse()
        "gettxout" -> txOutResponse()
        "testmempoolaccept" -> response("""[{"txid":"${review.txid}","allowed":true}]""")
        "sendrawtransaction" -> response("\"${review.txid}\"")
        else -> error("Unexpected RPC ${request.method}")
    }

    private fun rawTransactionParams(requests: List<CapturedRequest>, method: String): List<String> {
        val params = gson.fromJson(requests.single { it.method == method }.params, Array<Any>::class.java)
        return if (method == "testmempoolaccept") {
            @Suppress("UNCHECKED_CAST")
            (params.single() as List<String>)
        } else {
            listOf(params.single() as String)
        }
    }

    private fun tipResponse(): String = response(
        """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":100,"verificationprogress":1.0,"bestblockhash":"$tipHash"}"""
    )

    private fun txOutResponse(amount: String = "5.00000000"): String = response(
        """{"bestblock":"$tipHash","confirmations":6,"value":$amount,"scriptPubKey":{"hex":"$scriptHex"}}"""
    )

    private fun response(result: String): String = """{"result":$result,"error":null}"""

    private fun rpcError(code: Int): String =
        """{"result":null,"error":{"code":$code,"message":"fixture RPC error"}}"""

    private fun previousTransactionHex(amountKoinu: Long): String = DogecoinHex.encode(
        ByteArrayOutputStream().apply {
            write(le(1L, 4))
            write(1)
            write(ByteArray(32) { 0x44 })
            write(le(0L, 4))
            write(0)
            write(le(0xffffffffL, 4))
            write(1)
            write(le(amountKoinu, 8))
            val script = DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
            write(script.size)
            write(script)
            write(le(0L, 4))
        }.toByteArray()
    )

    private fun le(value: Long, bytes: Int): ByteArray =
        ByteArray(bytes) { index -> (value ushr (8 * index)).toByte() }
}
