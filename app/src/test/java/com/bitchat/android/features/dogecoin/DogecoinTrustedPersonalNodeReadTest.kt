package com.bitchat.android.features.dogecoin

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.test.runTest
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DogecoinTrustedPersonalNodeReadTest {
    private val gson = Gson()
    private val address = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
        DogecoinNetwork.MAINNET
    ).address
    private val otherAddress = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000002",
        DogecoinNetwork.MAINNET
    ).address
    private val credentials = DogecoinTrustedPersonalNodeCredentials("phone-user", "test-secret")

    private data class CapturedRequest(
        val method: String,
        val path: String,
        val params: String,
        val authorization: String?
    )

    @Test
    fun `readiness and display snapshot use only fixed non-mutating calls`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val script = DogecoinHex.encode(DogecoinAddress.p2pkhScript(address, DogecoinNetwork.MAINNET))
        val client = DogecoinRpcClient(
            rpcClient(requests) { request ->
                when (request.get("method").asString) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":102,"verificationprogress":1.000000079}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":4}""")
                    "getwalletinfo" -> rpcResponse(
                        """{"walletname":"bitchat-watch.dat","scanning":false}"""
                    )
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"$address","ismine":false,"iswatchonly":true}"""
                    )
                    "help" -> rpcResponse("\"${request.getAsJsonArray("params")[0].asString} help\"")
                    "listunspent" -> rpcResponse(
                        """[
                            {"txid":"${"11".repeat(32)}","vout":0,"amount":12.34,"confirmations":6,"scriptPubKey":"$script"},
                            {"txid":"${"22".repeat(32)}","vout":1,"amount":0.5,"confirmations":0,"scriptPubKey":"$script"}
                        ]""".trimIndent()
                    )
                    "listtransactions" -> rpcResponse(
                        """[
                            {"txid":"${"33".repeat(32)}","category":"receive","address":"$address","amount":12.34,"confirmations":6,"time":1234,"involvesWatchonly":true},
                            {"txid":"${"44".repeat(32)}","category":"receive","address":"$otherAddress","amount":99,"confirmations":9,"time":1235,"involvesWatchonly":true}
                        ]""".trimIndent()
                    )
                    else -> error("Unexpected RPC method ${request.get("method")}")
                }
            }
        )

        val snapshot = client.readTrustedPersonalNodeDisplaySnapshot(profile(), credentials)

        assertEquals(7L, snapshot.profileRevision)
        assertEquals(100, snapshot.blocks)
        assertEquals(102, snapshot.headers)
        assertEquals(1.0, snapshot.verificationProgress, 0.0)
        assertEquals(4, snapshot.peerCount)
        assertEquals(1_234_000_000L, snapshot.balance.confirmedKoinu)
        assertEquals(50_000_000L, snapshot.balance.unconfirmedKoinu)
        assertEquals(2, snapshot.balance.utxoCount)
        assertEquals(1, snapshot.activity.size)
        assertEquals("33".repeat(32), snapshot.activity.single().txid)
        assertEquals(
            listOf(
                "getblockchaininfo",
                "getnetworkinfo",
                "getwalletinfo",
                "validateaddress",
                "help",
                "help",
                "listunspent",
                "listtransactions"
            ),
            requests.map { it.method }
        )
        assertEquals(
            listOf(
                "/",
                "/",
                "/wallet/bitchat-watch.dat",
                "/wallet/bitchat-watch.dat",
                "/",
                "/",
                "/wallet/bitchat-watch.dat",
                "/wallet/bitchat-watch.dat"
            ),
            requests.map { it.path }
        )
        assertEquals("[\"$address\"]", requests[3].params)
        assertEquals("[\"testmempoolaccept\"]", requests[4].params)
        assertEquals("[\"gettransaction\"]", requests[5].params)
        assertEquals("[0,9999999,[\"$address\"]]", requests[6].params)
        assertEquals("[\"*\",100,0,true]", requests[7].params)
        assertTrue(requests.all { it.authorization == Credentials.basic("phone-user", "test-secret") })
        val forbidden = setOf(
            "importaddress",
            "rescanblockchain",
            "loadwallet",
            "createwallet",
            "dumpprivkey",
            "signrawtransactionwithwallet",
            "sendtoaddress",
            "sendrawtransaction",
            "addnode",
            "generate"
        )
        assertTrue(requests.none { it.method in forbidden })
    }

    @Test
    fun `invalid bound tuple makes zero requests`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(rpcClient(requests) { error("must not request") })

        val badOrigin = runCatching {
            client.readTrustedPersonalNodeDisplaySnapshot(
                profile().copy(origin = "https://dogebox.tail1234.ts.net/"),
                credentials
            )
        }
        val badCredentials = runCatching {
            client.readTrustedPersonalNodeDisplaySnapshot(
                profile(),
                DogecoinTrustedPersonalNodeCredentials("phone-user", "")
            )
        }

        assertTrue(badOrigin.isFailure)
        assertTrue(badCredentials.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `progress lag and peer readiness failures stop before wallet reads`() = runTest {
        suspend fun methodsFor(
            blockchainResult: String,
            networkResult: String = """{"networkactive":true,"connections":4}"""
        ): Pair<Result<DogecoinTrustedPersonalNodeDisplaySnapshot>, List<String>> {
            val requests = mutableListOf<CapturedRequest>()
            val client = DogecoinRpcClient(
                rpcClient(requests) { request ->
                    when (request.get("method").asString) {
                        "getblockchaininfo" -> rpcResponse(blockchainResult)
                        "getnetworkinfo" -> rpcResponse(networkResult)
                        else -> error("must stop before wallet reads")
                    }
                }
            )
            return runCatching {
                client.readTrustedPersonalNodeDisplaySnapshot(profile(), credentials)
            } to requests.map { it.method }
        }

        val lowProgress = methodsFor(
            """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":100,"verificationprogress":0.9}"""
        )
        val excessiveLag = methodsFor(
            """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":103,"verificationprogress":1.0}"""
        )
        val lowPeers = methodsFor(
            """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":102,"verificationprogress":1.0}""",
            """{"networkactive":true,"connections":3}"""
        )

        assertTrue(lowProgress.first.isFailure)
        assertEquals(listOf("getblockchaininfo"), lowProgress.second)
        assertTrue(excessiveLag.first.isFailure)
        assertEquals(listOf("getblockchaininfo"), excessiveLag.second)
        assertTrue(lowPeers.first.isFailure)
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo"), lowPeers.second)
    }

    @Test
    fun `unsafe watch flags stop before capability and balance reads`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(
            rpcClient(requests) { request ->
                when (request.get("method").asString) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":100,"verificationprogress":1.0}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "getwalletinfo" -> rpcResponse("""{"walletname":"bitchat-watch.dat"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"$address","ismine":true,"iswatchonly":true}"""
                    )
                    else -> error("must stop before display reads")
                }
            }
        )

        val result = runCatching {
            client.readTrustedPersonalNodeDisplaySnapshot(profile(), credentials)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("ismine=true"))
        assertEquals(
            listOf("getblockchaininfo", "getnetworkinfo", "getwalletinfo", "validateaddress"),
            requests.map { it.method }
        )
    }

    @Test
    fun `raw transaction help is the fixed fallback when wallet transaction help is unavailable`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(
            rpcClient(requests) { request ->
                val method = request.get("method").asString
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":100,"verificationprogress":1.0}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":4}""")
                    "getwalletinfo" -> rpcResponse("""{"walletname":"bitchat-watch.dat","scanning":false}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"$address","ismine":false,"iswatchonly":true}"""
                    )
                    "help" -> {
                        val capability = request.getAsJsonArray("params")[0].asString
                        rpcResponse(
                            if (capability == "gettransaction") {
                                "\"unknown command: gettransaction\""
                            } else {
                                "\"$capability help\""
                            }
                        )
                    }
                    "listunspent", "listtransactions" -> rpcResponse("[]")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val snapshot = client.readTrustedPersonalNodeDisplaySnapshot(profile(), credentials)

        assertEquals(0L, snapshot.balance.totalKoinu)
        assertEquals(
            listOf("testmempoolaccept", "gettransaction", "getrawtransaction"),
            requests.filter { it.method == "help" }.map {
                gson.fromJson(it.params, com.google.gson.JsonArray::class.java)[0].asString
            }
        )
    }

    @Test
    fun `authentication status is typed and malformed display data is never zero`() = runTest {
        val authClient = DogecoinRpcClient(httpStatusClient(401))
        val authResult = runCatching {
            authClient.readTrustedPersonalNodeDisplaySnapshot(profile(), credentials)
        }
        assertTrue(authResult.isFailure)
        assertTrue(authResult.exceptionOrNull()!!.isDogecoinRpcAuthenticationFailure())
        val forbiddenClient = DogecoinRpcClient(
            httpStatusClient(403, """{"result":null,"error":{"code":-1,"message":"forbidden"}}""")
        )
        val forbiddenResult = runCatching {
            forbiddenClient.readTrustedPersonalNodeDisplaySnapshot(profile(), credentials)
        }
        assertTrue(forbiddenResult.isFailure)
        assertTrue(forbiddenResult.exceptionOrNull()!!.isDogecoinRpcAuthenticationFailure())

        val requests = mutableListOf<CapturedRequest>()
        val malformedClient = DogecoinRpcClient(
            rpcClient(requests) { request ->
                when (request.get("method").asString) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false,"blocks":100,"headers":100,"verificationprogress":1.0}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":4}""")
                    "getwalletinfo" -> rpcResponse("""{"walletname":"bitchat-watch.dat","scanning":false}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"$address","ismine":false,"iswatchonly":true}"""
                    )
                    "help" -> rpcResponse("\"${request.getAsJsonArray("params")[0].asString} help\"")
                    "listunspent" -> rpcResponse("{}")
                    else -> error("must stop at malformed listunspent")
                }
            }
        )

        val malformed = runCatching {
            malformedClient.readTrustedPersonalNodeDisplaySnapshot(profile(), credentials)
        }
        assertTrue(malformed.isFailure)
        assertFalse(malformed.exceptionOrNull()!!.isDogecoinRpcAuthenticationFailure())
        assertTrue(malformed.exceptionOrNull()?.message.orEmpty().contains("not an array"))
        assertEquals("listunspent", requests.last().method)
    }

    private fun profile(): DogecoinTrustedPersonalNodeProfile =
        DogecoinTrustedPersonalNodeProfile(
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

    private fun rpcClient(
        requests: MutableList<CapturedRequest>,
        resultForRequest: (JsonObject) -> String
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val requestJson = requestJson(chain.request().body)
            requests += CapturedRequest(
                method = requestJson.get("method").asString,
                path = chain.request().url.encodedPath,
                params = requestJson.getAsJsonArray("params").toString(),
                authorization = chain.request().header("Authorization")
            )
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(resultForRequest(requestJson).toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    private fun httpStatusClient(status: Int, body: String = ""): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message("refused")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    private fun requestJson(body: okhttp3.RequestBody?): JsonObject {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return gson.fromJson(buffer.readUtf8(), JsonObject::class.java)
    }

    private fun rpcResponse(result: String): String =
        """{"result":$result,"error":null,"id":"bitchat-dogecoin"}"""
}
