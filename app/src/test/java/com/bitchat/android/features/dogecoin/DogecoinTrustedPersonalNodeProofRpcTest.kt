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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DogecoinTrustedPersonalNodeProofRpcTest {
    private val gson = Gson()
    private val address = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
        DogecoinNetwork.MAINNET
    ).address
    private val scriptHex = DogecoinHex.encode(
        DogecoinAddress.p2pkhScript(address, DogecoinNetwork.MAINNET)
    )
    private val rawHex = previousTransactionHex(500_000_000L)
    private val txid = DogecoinTransactionBuilder.transactionId(rawHex)
    private val credentials = DogecoinTrustedPersonalNodeCredentials("phone-user", "test-secret")
    private val startHash = "aa".repeat(32)

    private data class CapturedRequest(val method: String, val path: String, val params: String)

    @Test
    fun `complete wallet proof snapshot uses fixed read-only calls and exact bindings`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse(amount = "5.00000000")
                "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                "gettxout" -> txOutResponse(startHash, amount = "5.00000000", confirmations = 6)
                else -> error("Unexpected RPC ${request.method}")
            }
        }

        val snapshot = readProof(client)

        assertEquals(profile().toSessionBinding(), snapshot.binding)
        assertEquals(55L, snapshot.capturedAtMonotonicMillis)
        assertEquals(DogecoinTrustedPersonalNodeBlockTip(100, startHash), snapshot.startTip)
        assertEquals(snapshot.startTip, snapshot.endTip)
        assertEquals(rawHex.length / 2, snapshot.totalProofBytes)
        assertEquals(1, snapshot.verifiedPrevouts.size)
        assertEquals(6, snapshot.proofCandidates.single().finalConfirmations)
        assertEquals(startHash, snapshot.proofCandidates.single().finalBestBlockHash)
        assertEquals(txid, snapshot.verifiedPrevouts.single().txid)
        assertEquals(
            DogecoinTrustedPersonalNodePreviousTransactionSource.WALLET_GETTRANSACTION,
            snapshot.verifiedPrevouts.single().source
        )
        assertEquals(
            listOf(
                "getblockchaininfo",
                "listunspent",
                "gettransaction",
                "gettxout",
                "getblockchaininfo",
                "gettxout"
            ),
            requests.map { it.method }
        )
        assertEquals(
            listOf("/", "/wallet/bitchat-watch.dat", "/wallet/bitchat-watch.dat", "/", "/", "/"),
            requests.map { it.path }
        )
        assertEquals("[6,9999999,[\"$address\"]]", requests[1].params)
        assertEquals("[\"$txid\",true]", requests[2].params)
        assertEquals("[\"$txid\",0,true]", requests[3].params)
        val forbidden = setOf(
            "importaddress", "rescanblockchain", "testmempoolaccept", "sendrawtransaction",
            "signrawtransactionwithwallet", "sendtoaddress"
        )
        assertTrue(requests.none { it.method in forbidden })
    }

    @Test
    fun `non-wallet transaction error narrowly falls back to raw txindex route`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse()
                "gettransaction" ->
                    """{"result":null,"error":{"code":-5,"message":"Invalid or non-wallet transaction id"}}"""
                "getrawtransaction" -> response("\"$rawHex\"")
                "gettxout" -> txOutResponse(startHash)
                else -> error("Unexpected RPC ${request.method}")
            }
        }

        val snapshot = readProof(client)

        assertEquals(
            DogecoinTrustedPersonalNodePreviousTransactionSource.TXINDEX_GETRAWTRANSACTION,
            snapshot.verifiedPrevouts.single().source
        )
        assertEquals(
            listOf("gettransaction", "getrawtransaction"),
            requests.filter { it.method == "gettransaction" || it.method == "getrawtransaction" }
                .map { it.method }
        )
        assertEquals("/", requests.first { it.method == "getrawtransaction" }.path)
        assertEquals("[\"$txid\",false]", requests.first { it.method == "getrawtransaction" }.params)
    }

    @Test
    fun `missing txindex fallback leaves no proof snapshot`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse()
                "gettransaction" ->
                    """{"result":null,"error":{"code":-5,"message":"Invalid or non-wallet transaction id"}}"""
                "getrawtransaction" -> response("null")
                else -> error("Incomplete previous transaction proof must stop before ${request.method}")
            }
        }

        val outcome = runCatching { readProof(client) }

        assertTrue(outcome.isFailure)
        assertEquals(
            listOf("gettransaction", "getrawtransaction"),
            requests.filter { it.method == "gettransaction" || it.method == "getrawtransaction" }
                .map { it.method }
        )
        assertFalse(requests.any { it.method == "gettxout" })
    }

    @Test
    fun `successful malformed wallet result is never masked by raw fallback`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse()
                "gettransaction" -> response("""{"txid":"${"bb".repeat(32)}","hex":"$rawHex"}""")
                else -> error("Must stop at gettransaction")
            }
        }

        val outcome = runCatching {
            readProof(client)
        }

        assertTrue(outcome.isFailure)
        assertFalse(requests.any { it.method == "getrawtransaction" })
        assertEquals("gettransaction", requests.last().method)
    }

    @Test
    fun `ordinary wallet RPC failure is never masked by raw fallback`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse()
                "gettransaction" ->
                    """{"result":null,"error":{"code":-1,"message":"Wallet database unavailable"}}"""
                else -> error("Ordinary wallet failure must stop before ${request.method}")
            }
        }

        val outcome = runCatching { readProof(client) }

        assertTrue(outcome.isFailure)
        assertFalse(requests.any { it.method == "getrawtransaction" })
        assertEquals("gettransaction", requests.last().method)
    }

    @Test
    fun `revoked proof lease stops the next RPC in the fixed workflow`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        var requestCurrent = true
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> {
                    requestCurrent = false
                    listUnspentResponse()
                }
                else -> error("Revoked lease must stop before ${request.method}")
            }
        }

        val outcome = runCatching {
            readProof(client) { requestCurrent }
        }

        assertTrue(outcome.isFailure)
        assertEquals(listOf("getblockchaininfo", "listunspent"), requests.map { it.method })
    }

    @Test
    fun `scalar amount and gettxout lies fail before a snapshot is returned`() = runTest {
        suspend fun failureWith(listAmount: String, txOutAmount: String): Pair<Result<*>, List<String>> {
            val requests = mutableListOf<CapturedRequest>()
            val client = client(requests) { request ->
                when (request.method) {
                    "getblockchaininfo" -> tipResponse(100, startHash)
                    "listunspent" -> listUnspentResponse(amount = listAmount)
                    "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                    "gettxout" -> txOutResponse(startHash, amount = txOutAmount)
                    else -> error("Unexpected RPC ${request.method}")
                }
            }
            return runCatching {
                readProof(client)
            } to requests.map { it.method }
        }

        val scalarLie = failureWith("4.99999999", "5.00000000")
        assertTrue(scalarLie.first.isFailure)
        assertFalse(scalarLie.second.contains("gettxout"))

        val txOutLie = failureWith("5.00000000", "4.99999999")
        assertTrue(txOutLie.first.isFailure)
        assertEquals("gettxout", txOutLie.second.last())
    }

    @Test
    fun `foreign list script and fabricated previous bytes fail without partial proof`() = runTest {
        val foreignAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002",
            DogecoinNetwork.MAINNET
        ).address
        val foreignScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(foreignAddress, DogecoinNetwork.MAINNET)
        )
        val listRequests = mutableListOf<CapturedRequest>()
        val listClient = client(listRequests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> response(
                    """[{"txid":"$txid","vout":0,"amount":5,"confirmations":6,"scriptPubKey":"$foreignScript"}]"""
                )
                else -> error("Must stop at foreign list script")
            }
        }
        assertTrue(
            runCatching {
                readProof(listClient)
            }.isFailure
        )
        assertEquals("listunspent", listRequests.last().method)

        val fabricatedRaw = previousTransactionHex(400_000_000L)
        val proofRequests = mutableListOf<CapturedRequest>()
        val proofClient = client(proofRequests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse()
                "gettransaction" -> response("""{"txid":"$txid","hex":"$fabricatedRaw"}""")
                else -> error("Must stop at fabricated previous transaction")
            }
        }
        assertTrue(
            runCatching {
                readProof(proofClient)
            }.isFailure
        )
        assertEquals("gettransaction", proofRequests.last().method)
    }

    @Test
    fun `snapshot type rejects a proved prevout for a foreign address`() {
        val foreignAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002",
            DogecoinNetwork.MAINNET
        ).address
        val foreignScript = DogecoinAddress.p2pkhScript(foreignAddress, DogecoinNetwork.MAINNET)
        val foreignRawHex = previousTransactionHex(500_000_000L, foreignAddress)
        val foreignTxid = DogecoinTransactionBuilder.transactionId(foreignRawHex)
        val verified = DogecoinVerifiedPrevout.verify(
            rawPreviousTransactionHex = foreignRawHex,
            expectedTxid = foreignTxid,
            vout = 0,
            expectedP2pkhScript = foreignScript,
            source = DogecoinTrustedPersonalNodePreviousTransactionSource.WALLET_GETTRANSACTION
        )
        val candidate = DogecoinTrustedPersonalNodeProofCandidate.verifiedAtTip(
            verifiedPrevout = verified,
            finalConfirmations = DOGECOIN_TPN_MIN_INPUT_CONFIRMATIONS,
            finalBestBlockHash = startHash
        )

        assertThrows(IllegalArgumentException::class.java) {
            DogecoinTrustedPersonalNodeProofSnapshot.complete(
                binding = profile().toSessionBinding(),
                capturedAtMonotonicMillis = 55L,
                startTip = DogecoinTrustedPersonalNodeBlockTip(100, startHash),
                endTip = DogecoinTrustedPersonalNodeBlockTip(100, startHash),
                proofCandidates = listOf(candidate),
                totalProofBytes = verified.previousTransactionByteCount
            )
        }
    }

    @Test
    fun `duplicate and over-cap candidate arrays stop before previous transaction RPC`() = runTest {
        suspend fun methodsFor(rows: String): Pair<Result<*>, List<String>> {
            val requests = mutableListOf<CapturedRequest>()
            val client = client(requests) { request ->
                when (request.method) {
                    "getblockchaininfo" -> tipResponse(100, startHash)
                    "listunspent" -> response(rows)
                    else -> error("Must stop after bounded listunspent")
                }
            }
            return runCatching {
                readProof(client)
            } to requests.map { it.method }
        }
        val row = """{"txid":"$txid","vout":0,"amount":5,"confirmations":6,"scriptPubKey":"$scriptHex"}"""
        val duplicate = methodsFor("[$row,$row]")
        assertTrue(duplicate.first.isFailure)
        assertEquals(listOf("getblockchaininfo", "listunspent"), duplicate.second)

        val overCapRows = (0..DOGECOIN_TPN_MAX_PROOF_CANDIDATES).joinToString(",", "[", "]") { index ->
            val boundedTxid = index.toString(16).padStart(64, '0')
            """{"txid":"$boundedTxid","vout":0,"amount":5,"confirmations":6,"scriptPubKey":"$scriptHex"}"""
        }
        val overCap = methodsFor(overCapRows)
        assertTrue(overCap.first.isFailure)
        assertEquals(listOf("getblockchaininfo", "listunspent"), overCap.second)
    }

    @Test
    fun `proof candidates reject normalized txid and script spellings`() = runTest {
        suspend fun rejects(row: String): Boolean {
            val requests = mutableListOf<CapturedRequest>()
            val client = client(requests) { request ->
                when (request.method) {
                    "getblockchaininfo" -> tipResponse(100, startHash)
                    "listunspent" -> response("[$row]")
                    else -> error("Non-canonical candidate must stop before ${request.method}")
                }
            }
            val failed = runCatching { readProof(client) }.isFailure
            return failed && requests.map { it.method } == listOf("getblockchaininfo", "listunspent")
        }

        assertTrue(
            rejects(
                """{"txid":" $txid","vout":0,"amount":5,"confirmations":6,"scriptPubKey":"$scriptHex"}"""
            )
        )
        assertTrue(
            rejects(
                """{"txid":"$txid","vout":0,"amount":5,"confirmations":6,"scriptPubKey":"${scriptHex.uppercase()}"}"""
            )
        )
    }

    @Test
    fun `bounded tip extension requires literal verbose header ancestry`() = runTest {
        val endHash = "cc".repeat(32)
        val middleHash = "bb".repeat(32)
        var blockInfoCalls = 0
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> {
                    blockInfoCalls += 1
                    if (blockInfoCalls == 1) tipResponse(100, startHash) else tipResponse(102, endHash)
                }
                "listunspent" -> listUnspentResponse(confirmations = 6)
                "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                "getblockheader" -> when (request.params) {
                    "[\"$endHash\",true]" -> headerResponse(endHash, 102, middleHash)
                    "[\"$middleHash\",true]" -> headerResponse(middleHash, 101, startHash)
                    else -> error("Unexpected header ${request.params}")
                }
                "gettxout" -> {
                    val final = requests.count { it.method == "gettxout" } > 1
                    txOutResponse(if (final) endHash else startHash, confirmations = if (final) 8 else 6)
                }
                else -> error("Unexpected RPC ${request.method}")
            }
        }

        val snapshot = readProof(client)

        assertEquals(DogecoinTrustedPersonalNodeBlockTip(102, endHash), snapshot.endTip)
        assertEquals(2, requests.count { it.method == "getblockheader" })
    }

    @Test
    fun `same-height replacement broken ancestry and final spend fail closed`() = runTest {
        suspend fun runScenario(kind: String): Pair<Result<*>, List<String>> {
            val replacementHash = "bb".repeat(32)
            var blockInfoCalls = 0
            var txOutCalls = 0
            val requests = mutableListOf<CapturedRequest>()
            val client = client(requests) { request ->
                when (request.method) {
                    "getblockchaininfo" -> {
                        blockInfoCalls += 1
                        when (kind) {
                            "replace" -> if (blockInfoCalls == 1) tipResponse(100, startHash) else tipResponse(100, replacementHash)
                            "regress" -> if (blockInfoCalls == 1) tipResponse(100, startHash) else tipResponse(99, replacementHash)
                            else -> if (blockInfoCalls == 1) tipResponse(100, startHash) else tipResponse(101, replacementHash)
                        }
                    }
                    "listunspent" -> listUnspentResponse()
                    "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                    "getblockheader" -> headerResponse(replacementHash, 101, "dd".repeat(32))
                    "gettxout" -> {
                        txOutCalls += 1
                        if (kind == "spent" && txOutCalls == 2) response("null") else
                            txOutResponse(if (txOutCalls == 1) startHash else replacementHash)
                    }
                    else -> error("Unexpected RPC ${request.method}")
                }
            }
            return runCatching {
                readProof(client)
            } to requests.map { it.method }
        }

        val replaced = runScenario("replace")
        assertTrue(replaced.first.isFailure)
        assertFalse(replaced.second.contains("getblockheader"))

        val regressed = runScenario("regress")
        assertTrue(regressed.first.isFailure)
        assertFalse(regressed.second.contains("getblockheader"))

        val ancestry = runScenario("ancestry")
        assertTrue(ancestry.first.isFailure)
        assertEquals("getblockheader", ancestry.second.last())

        // Use valid ancestry for the spent-final vector.
        val requests = mutableListOf<CapturedRequest>()
        var blockInfoCalls = 0
        var txOutCalls = 0
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> {
                    blockInfoCalls += 1
                    tipResponse(100, startHash)
                }
                "listunspent" -> listUnspentResponse()
                "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                "gettxout" -> {
                    txOutCalls += 1
                    if (txOutCalls == 2) response("null") else txOutResponse(startHash)
                }
                else -> error("Unexpected RPC ${request.method}")
            }
        }
        val spent = runCatching {
            readProof(client)
        }
        assertTrue(spent.isFailure)
        assertEquals(2, txOutCalls)
    }

    @Test
    fun `final outpoint script change fails the complete snapshot`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        var txOutCalls = 0
        val changedScript = scriptHex.replaceRange(6, 8, "ff")
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse()
                "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                "gettxout" -> {
                    txOutCalls += 1
                    txOutResponse(
                        bestBlock = startHash,
                        script = if (txOutCalls == 1) scriptHex else changedScript
                    )
                }
                else -> error("Unexpected RPC ${request.method}")
            }
        }

        val outcome = runCatching { readProof(client) }

        assertTrue(outcome.isFailure)
        assertEquals(2, txOutCalls)
    }

    @Test
    fun `tip extension above two blocks is refused before ancestry calls`() = runTest {
        var blockInfoCalls = 0
        val requests = mutableListOf<CapturedRequest>()
        val endHash = "ee".repeat(32)
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> {
                    blockInfoCalls += 1
                    if (blockInfoCalls == 1) tipResponse(100, startHash) else tipResponse(103, endHash)
                }
                "listunspent" -> listUnspentResponse()
                "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                "gettxout" -> txOutResponse(startHash)
                else -> error("Must stop before ancestry")
            }
        }

        val outcome = runCatching {
            readProof(client)
        }

        assertTrue(outcome.isFailure)
        assertFalse(requests.any { it.method == "getblockheader" })
    }

    @Test
    fun `post-end-tip change fails the final outpoint binding`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        var txOutCalls = 0
        val changedHash = "bb".repeat(32)
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> listUnspentResponse()
                "gettransaction" -> response("""{"txid":"$txid","hex":"$rawHex"}""")
                "gettxout" -> {
                    txOutCalls += 1
                    txOutResponse(if (txOutCalls == 1) startHash else changedHash)
                }
                else -> error("Unexpected RPC ${request.method}")
            }
        }

        val outcome = runCatching { readProof(client) }

        assertTrue(outcome.isFailure)
        assertEquals(2, txOutCalls)
    }

    @Test
    fun `aggregate previous transaction proofs above four MiB fail closed`() = runTest {
        val rawByTxid = (1..5).associate { salt ->
            val raw = paddedPreviousTransactionHex(salt.toByte())
            DogecoinTransactionBuilder.transactionId(raw) to raw
        }
        val rows = rawByTxid.keys.joinToString(",", "[", "]") { candidateTxid ->
            """{"txid":"$candidateTxid","vout":0,"amount":5,"confirmations":6,"scriptPubKey":"$scriptHex"}"""
        }
        val requests = mutableListOf<CapturedRequest>()
        val client = client(requests) { request ->
            when (request.method) {
                "getblockchaininfo" -> tipResponse(100, startHash)
                "listunspent" -> response(rows)
                "gettransaction" -> {
                    val candidate = rawByTxid.entries.first { request.params.contains(it.key) }
                    response("""{"txid":"${candidate.key}","hex":"${candidate.value}"}""")
                }
                "gettxout" -> txOutResponse(startHash)
                else -> error("Proof byte cap must stop before ${request.method}")
            }
        }

        val outcome = runCatching { readProof(client) }

        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("4 MiB"))
        assertEquals(5, requests.count { it.method == "gettransaction" })
        assertEquals(1, requests.count { it.method == "getblockchaininfo" })
    }

    private fun profile(): DogecoinTrustedPersonalNodeProfile = DogecoinTrustedPersonalNodeProfile(
        origin = "https://dogebox.tail1234.ts.net",
        network = DogecoinNetwork.MAINNET,
        androidAddress = address,
        coreWalletId = "bitchat-watch.dat",
        policyVersion = DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION,
        revision = 7L,
        authorizedAtMillis = 3_000L,
        rescanAttested = true,
        rescanAttestedAtMillis = 2_000L
    )

    private fun token(): DogecoinTrustedPersonalNodeProofRequestToken =
        DogecoinTrustedPersonalNodeProofRequestToken(
            nonce = 1L,
            binding = profile().toSessionBinding(),
            startedAtMonotonicMillis = 55L
        )

    private suspend fun readProof(
        client: DogecoinRpcClient,
        requestIsCurrent: () -> Boolean = { true }
    ): DogecoinTrustedPersonalNodeProofSnapshot =
        client.readTrustedPersonalNodeProofSnapshot(
            profile = profile(),
            credentials = credentials,
            requestToken = token(),
            requestIsCurrent = requestIsCurrent
        )

    private fun client(
        requests: MutableList<CapturedRequest>,
        result: (CapturedRequest) -> String
    ): DogecoinRpcClient = DogecoinRpcClient(
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val body = Buffer().also { chain.request().body?.writeTo(it) }.readUtf8()
            val json = gson.fromJson(body, JsonObject::class.java)
            val captured = CapturedRequest(
                method = json.get("method").asString,
                path = chain.request().url.encodedPath,
                params = json.getAsJsonArray("params").toString()
            )
            requests += captured
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(result(captured).toResponseBody("application/json".toMediaType()))
                .build()
        }).build()
    )

    private fun tipResponse(height: Int, hash: String): String = response(
        """{"chain":"main","initialblockdownload":false,"blocks":$height,"headers":$height,"verificationprogress":1.0,"bestblockhash":"$hash"}"""
    )

    private fun listUnspentResponse(
        amount: String = "5.00000000",
        confirmations: Int = 6
    ): String = response(
        """[{"txid":"$txid","vout":0,"amount":$amount,"confirmations":$confirmations,"scriptPubKey":"$scriptHex"}]"""
    )

    private fun txOutResponse(
        bestBlock: String,
        amount: String = "5.00000000",
        confirmations: Int = 6,
        script: String = scriptHex
    ): String = response(
        """{"bestblock":"$bestBlock","confirmations":$confirmations,"value":$amount,"scriptPubKey":{"hex":"$script"}}"""
    )

    private fun headerResponse(hash: String, height: Int, previous: String): String = response(
        """{"hash":"$hash","height":$height,"previousblockhash":"$previous"}"""
    )

    private fun response(result: String): String = """{"result":$result,"error":null}"""

    private fun previousTransactionHex(
        amountKoinu: Long,
        destinationAddress: String = address
    ): String = DogecoinHex.encode(
        ByteArrayOutputStream().apply {
            write(le(1L, 4))
            write(1)
            write(ByteArray(32) { 0x44 })
            write(le(0L, 4))
            write(0)
            write(le(0xffffffffL, 4))
            write(1)
            write(le(amountKoinu, 8))
            val script = DogecoinAddress.p2pkhScript(destinationAddress, DogecoinNetwork.MAINNET)
            write(script.size)
            write(script)
            write(le(0L, 4))
        }.toByteArray()
    )

    private fun paddedPreviousTransactionHex(salt: Byte): String = DogecoinHex.encode(
        ByteArrayOutputStream().apply {
            write(le(1L, 4))
            write(1)
            write(ByteArray(32) { salt })
            write(le(0L, 4))
            write(0)
            write(le(0xffffffffL, 4))
            write(2)
            write(le(500_000_000L, 8))
            val boundScript = DogecoinAddress.p2pkhScript(address, DogecoinNetwork.MAINNET)
            write(boundScript.size)
            write(boundScript)
            write(le(0L, 8))
            val padding = ByteArray(850_000) { salt }
            write(byteArrayOf(0xfe.toByte()) + le(padding.size.toLong(), 4))
            write(padding)
            write(le(0L, 4))
        }.toByteArray()
    )

    private fun le(value: Long, bytes: Int): ByteArray =
        ByteArray(bytes) { index -> (value ushr (8 * index)).toByte() }
}
