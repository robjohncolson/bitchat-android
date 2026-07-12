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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class DogecoinTrustedPersonalNodeProvisioningTest {
    private val gson = Gson()
    private val origin = "https://athena.tail3f5172.ts.net"
    private val address = DogecoinKeyGenerator.fromPrivateKeyHex(
        "0000000000000000000000000000000000000000000000000000000000000001",
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
    fun `provisioning uses only fixed non-mutating calls and rebinds resolved wallet`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(
            provisioningHttpClient(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"bitchat-watch.dat"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"$address","ismine":false,"iswatchonly":true}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val result = client.probeTrustedPersonalNode(
            origin = origin,
            credentials = credentials,
            requestedWalletId = "",
            boundMainnetAddress = address
        )

        assertEquals(origin, result.origin)
        assertEquals(DogecoinNetwork.MAINNET, result.network)
        assertEquals(address, result.androidAddress)
        assertEquals("bitchat-watch.dat", result.coreWalletId)
        assertEquals(false, result.watchStatus.isMine)
        assertEquals(true, result.watchStatus.isWatchOnly)
        assertEquals(
            listOf("getblockchaininfo", "getwalletinfo", "validateaddress"),
            requests.map { it.method }
        )
        assertEquals(listOf("/", "/", "/wallet/bitchat-watch.dat"), requests.map { it.path })
        assertEquals(listOf("[]", "[]", "[\"$address\"]"), requests.map { it.params })
        assertTrue(requests.all { it.authorization == Credentials.basic("phone-user", "test-secret") })
    }

    @Test
    fun `noncanonical origin and invalid address make zero requests`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(provisioningHttpClient(requests) { error("must not request") })

        val badOrigin = runCatching {
            client.probeTrustedPersonalNode(
                origin = "$origin/",
                credentials = credentials,
                requestedWalletId = "bitchat-watch.dat",
                boundMainnetAddress = address
            )
        }
        val badAddress = runCatching {
            client.probeTrustedPersonalNode(
                origin = origin,
                credentials = credentials,
                requestedWalletId = "bitchat-watch.dat",
                boundMainnetAddress = "not-a-mainnet-address"
            )
        }
        val pathTraversalWallet = runCatching {
            client.probeTrustedPersonalNode(
                origin = origin,
                credentials = credentials,
                requestedWalletId = "..",
                boundMainnetAddress = address
            )
        }

        assertTrue(badOrigin.isFailure)
        assertTrue(badAddress.isFailure)
        assertTrue(pathTraversalWallet.isFailure)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `dot segment wallet returned by Core fails before address disclosure`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(
            provisioningHttpClient(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"."}""")
                    else -> error("Address must not be disclosed")
                }
            }
        )

        val result = runCatching {
            client.probeTrustedPersonalNode(origin, credentials, "", address)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("non-canonical walletname"))
        assertEquals(listOf("getblockchaininfo", "getwalletinfo"), requests.map { it.method })
    }

    @Test
    fun `wrong chain stops before wallet and address disclosure`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(
            provisioningHttpClient(requests) { method ->
                check(method == "getblockchaininfo")
                rpcResponse("""{"chain":"test","initialblockdownload":false}""")
            }
        )

        val result = runCatching {
            client.probeTrustedPersonalNode(origin, credentials, "bitchat-watch.dat", address)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("expected Dogecoin mainnet"))
        assertEquals(listOf("getblockchaininfo"), requests.map { it.method })
    }

    @Test
    fun `wallet mismatch stops before address disclosure`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(
            provisioningHttpClient(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"different-wallet.dat"}""")
                    else -> error("Address must not be disclosed")
                }
            }
        )

        val result = runCatching {
            client.probeTrustedPersonalNode(origin, credentials, "bitchat-watch.dat", address)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("different Core wallet"))
        assertEquals(listOf("getblockchaininfo", "getwalletinfo"), requests.map { it.method })
    }

    @Test
    fun `missing or unsafe watch flags fail closed after the fixed calls`() = runTest {
        val requests = mutableListOf<CapturedRequest>()
        val client = DogecoinRpcClient(
            provisioningHttpClient(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"bitchat-watch.dat"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"$address","ismine":true,"iswatchonly":true}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val result = runCatching {
            client.probeTrustedPersonalNode(origin, credentials, "bitchat-watch.dat", address)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("ismine=true"))
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "validateaddress"), requests.map { it.method })

        val missingFlagRequests = mutableListOf<CapturedRequest>()
        val missingFlagClient = DogecoinRpcClient(
            provisioningHttpClient(missingFlagRequests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"bitchat-watch.dat"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"$address","ismine":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val missingFlagResult = runCatching {
            missingFlagClient.probeTrustedPersonalNode(
                origin,
                credentials,
                "bitchat-watch.dat",
                address
            )
        }

        assertTrue(missingFlagResult.isFailure)
        assertTrue(missingFlagResult.exceptionOrNull()?.message.orEmpty().contains("iswatchonly"))
        assertEquals(
            listOf("getblockchaininfo", "getwalletinfo", "validateaddress"),
            missingFlagRequests.map { it.method }
        )
    }

    @Test
    fun `revoked provisioning generation blocks the next fixed call`() = runTest {
        val generation = AtomicInteger(0)
        val requests = mutableListOf<CapturedRequest>()
        val baseClient = DogecoinRpcClient(
            provisioningHttpClient(requests) { method ->
                generation.incrementAndGet()
                check(method == "getblockchaininfo")
                rpcResponse("""{"chain":"main","initialblockdownload":false}""")
            }
        )
        val guarded = baseClient.guardedBy {
            check(generation.get() == 0) { "provisioning draft changed" }
        }

        val result = runCatching {
            guarded.probeTrustedPersonalNode(origin, credentials, "bitchat-watch.dat", address)
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("draft changed"))
        assertEquals(listOf("getblockchaininfo"), requests.map { it.method })
    }

    private fun provisioningHttpClient(
        requests: MutableList<CapturedRequest>,
        resultForMethod: (String) -> String
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val requestJson = requestJson(chain.request().body)
            val method = requestJson.get("method").asString
            requests += CapturedRequest(
                method = method,
                path = chain.request().url.encodedPath,
                params = requestJson.get("params").asJsonArray.toString(),
                authorization = chain.request().header("Authorization")
            )
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(resultForMethod(method).toResponseBody("application/json".toMediaType()))
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
