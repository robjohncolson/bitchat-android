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
import org.junit.Assert.fail
import org.junit.Test

class DogecoinRpcClientTest {
    private val gson = Gson()
    private val sampleRawTransactionHex = listOf(
        "01000000",
        "01",
        "1111111111111111111111111111111111111111111111111111111111111111",
        "00000000",
        "01",
        "00",
        "ffffffff",
        "01",
        "00e1f50500000000",
        "19",
        "76a914222222222222222222222222222222222222222288ac",
        "00000000"
    ).joinToString("")
    private val sampleP2shRawTransactionHex = listOf(
        "01000000",
        "01",
        "1111111111111111111111111111111111111111111111111111111111111111",
        "00000000",
        "01",
        "00",
        "ffffffff",
        "01",
        "40420f0000000000",
        "17",
        "a914333333333333333333333333333333333333333387",
        "00000000"
    ).joinToString("")
    private val zeroOutputRawTransactionHex = listOf(
        "01000000",
        "01",
        "1111111111111111111111111111111111111111111111111111111111111111",
        "00000000",
        "01",
        "00",
        "ffffffff",
        "01",
        "0000000000000000",
        "19",
        "76a914222222222222222222222222222222222222222288ac",
        "00000000"
    ).joinToString("")
    private val dustOutputRawTransactionHex = listOf(
        "01000000",
        "01",
        "1111111111111111111111111111111111111111111111111111111111111111",
        "00000000",
        "01",
        "00",
        "ffffffff",
        "01",
        "3f420f0000000000",
        "19",
        "76a914222222222222222222222222222222222222222288ac",
        "00000000"
    ).joinToString("")
    private val nonStandardOutputRawTransactionHex = listOf(
        "01000000",
        "01",
        "1111111111111111111111111111111111111111111111111111111111111111",
        "00000000",
        "01",
        "00",
        "ffffffff",
        "01",
        "40420f0000000000",
        "01",
        "6a",
        "00000000"
    ).joinToString("")
    private val overflowingOutputTotalRawTransactionHex = listOf(
        "01000000",
        "01",
        "1111111111111111111111111111111111111111111111111111111111111111",
        "00000000",
        "01",
        "00",
        "ffffffff",
        "02",
        "ffffffffffffff7f",
        "19",
        "76a914222222222222222222222222222222222222222288ac",
        "40420f0000000000",
        "19",
        "76a914333333333333333333333333333333333333333388ac",
        "00000000"
    ).joinToString("")

    @Test
    fun `blockchain status clamps Core progress overshoot and reports ready mainnet node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false,"verificationprogress":1.000000079}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse(
                        """{"networkactive":true,"connections":8,"relayfee":0.02,"incrementalfee":0.001,"softdustlimit":0.03,"harddustlimit":0.01}"""
                    )
                    "help" -> rpcResponse(rpcString("testmempoolaccept \"rawtx\"\nrescanblockchain (start_height)"))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help", "help"), methods)
        assertEquals(DogecoinNetwork.MAINNET, status.expectedNetwork)
        assertEquals("main", status.chain)
        assertTrue(status.connected)
        assertTrue(status.isUsable)
        assertTrue(status.isReady)
        assertTrue(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(true, status.walletReady)
        assertEquals("main-wallet", status.walletName)
        assertEquals(true, status.relayReady)
        assertEquals(true, status.networkActive)
        assertEquals(8, status.peerCount)
        assertEquals(2_000_000L, status.relayFeePerKbKoinu)
        assertEquals(100_000L, status.incrementalFeePerKbKoinu)
        assertEquals(3_000_000L, status.softDustLimitKoinu)
        assertEquals(1_000_000L, status.hardDustLimitKoinu)
        assertEquals(true, status.policyCheckAvailable)
        assertEquals(null, status.policyCheckError)
        assertEquals(true, status.rescanBlockchainAvailable)
        assertEquals(null, status.rescanBlockchainError)
        assertEquals(5_000_000, status.blocks)
        assertEquals(5_000_000, status.headers)
        assertEquals(1.0, status.verificationProgress)
    }

    @Test
    fun `blockchain status routes wallet rpc through configured dogecoin core wallet endpoint`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithPaths(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8,"relayfee":0.01}""")
                    "help" -> rpcResponse(rpcString("testmempoolaccept \"rawtx\"\nrescanblockchain (start_height)"))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(
                url = "http://dogecoin.local:22555/rpc",
                walletName = "main wallet"
            ),
            network = DogecoinNetwork.MAINNET
        )

        assertTrue(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(
            listOf(
                "getblockchaininfo" to "/rpc",
                "getwalletinfo" to "/rpc/wallet/main%20wallet",
                "getnetworkinfo" to "/rpc",
                "help" to "/rpc",
                "help" to "/rpc"
            ),
            requests
        )
    }

    @Test
    fun `blockchain status sends pasted auth token as basic auth credentials`() = runTest {
        val requests = mutableListOf<Pair<String, String?>>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithAuthHeaders(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8,"relayfee":0.01}""")
                    "help" -> rpcResponse(rpcString("testmempoolaccept \"rawtx\"\nrescanblockchain (start_height)"))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(
                url = "http://dogecoin.local:22555",
                username = " dogeuser:doge-pass ",
                password = ""
            ),
            network = DogecoinNetwork.MAINNET
        )

        val expectedAuthHeader = Credentials.basic("dogeuser", "doge-pass")
        assertTrue(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(
            listOf(
                "getblockchaininfo" to expectedAuthHeader,
                "getwalletinfo" to expectedAuthHeader,
                "getnetworkinfo" to expectedAuthHeader,
                "help" to expectedAuthHeader,
                "help" to expectedAuthHeader
            ),
            requests
        )
    }

    @Test
    fun `blockchain status preserves ordinary progress while keeping IBD unavailable`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":4990000,"headers":5000000,"initialblockdownload":true,"verificationprogress":0.5}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertEquals(DogecoinNetwork.MAINNET, status.expectedNetwork)
        assertTrue(status.connected)
        assertTrue(status.isUsable)
        assertFalse(status.isReady)
        assertEquals(true, status.initialBlockDownload)
        assertEquals(4_990_000, status.blocks)
        assertEquals(5_000_000, status.headers)
        assertEquals(0.5, status.verificationProgress)
    }

    @Test
    fun `blockchain status rejects negative mainnet block height before wallet checks`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":-1,"headers":5000000,"initialblockdownload":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("negative blocks"))
    }

    @Test
    fun `blockchain status rejects string initial block download flag before wallet checks`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":"false"}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("invalid initialblockdownload"))
    }

    @Test
    fun `blockchain status rejects non-string chain before wallet checks`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":123,"blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("invalid chain"))
    }

    @Test
    fun `blockchain status rejects string verification progress before wallet checks`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false,"verificationprogress":"1.0"}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("invalid verificationprogress"))
    }

    @Test
    fun `blockchain status rejects implausible or non-finite verification progress`() = runTest {
        listOf("-0.01", "2.000001", "1e999").forEach { rawProgress ->
            val methods = mutableListOf<String>()
            val client = DogecoinRpcClient(
                httpClient = stubRpcClient(methods) { method ->
                    when (method) {
                        "getblockchaininfo" -> rpcResponse(
                            """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false,"verificationprogress":$rawProgress}"""
                        )
                        else -> error("Unexpected RPC method $method")
                    }
                }
            )

            val status = client.getBlockchainStatus(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                network = DogecoinNetwork.MAINNET
            )

            assertEquals(listOf("getblockchaininfo"), methods)
            assertFalse("progress=$rawProgress must fail status parsing", status.connected)
            assertTrue(status.error.orEmpty().contains("verificationprogress"))
        }
    }

    @Test
    fun `blockchain status rejects headers below block height before wallet checks`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":4999999,"initialblockdownload":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("headers below"))
    }

    @Test
    fun `blockchain status reports pruned mainnet node as ready but not rescan capable`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"pruned":true,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"pruned-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":2}""")
                    "help" -> rpcResponse(rpcString("testmempoolaccept \"rawtx\"\nrescanblockchain (start_height)"))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertEquals(true, status.pruned)
        assertEquals(true, status.walletReady)
        assertEquals(true, status.relayReady)
        assertEquals(true, status.policyCheckAvailable)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.supportsHistoricalRescanFor(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `blockchain status reports unavailable policy precheck without blocking relay ready node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "help" -> rpcError(
                        code = -1,
                        message = "help: unknown command: testmempoolaccept"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help", "help"), methods)
        assertTrue(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.policyCheckAvailable)
        assertTrue(status.policyCheckError.orEmpty().contains("testmempoolaccept is unavailable"))
        assertTrue(status.policyCheckError.orEmpty().contains("extra acknowledgement"))
    }

    @Test
    fun `blockchain status treats unknown command help text as unavailable policy precheck`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "help" -> rpcResponse(rpcString("help: unknown command: testmempoolaccept"))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help", "help"), methods)
        assertTrue(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.policyCheckAvailable)
        assertTrue(status.policyCheckError.orEmpty().contains("testmempoolaccept is unavailable"))
        assertTrue(status.policyCheckError.orEmpty().contains("extra acknowledgement"))
    }

    @Test
    fun `blockchain status reports unavailable rescanblockchain without blocking ready node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "help" -> rpcResponse(rpcString("testmempoolaccept \"rawtx\""))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help", "help"), methods)
        assertTrue(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertTrue(status.supportsHistoricalRescanFor(DogecoinNetwork.MAINNET))
        assertEquals(true, status.policyCheckAvailable)
        assertEquals(false, status.rescanBlockchainAvailable)
        assertTrue(status.rescanBlockchainError.orEmpty().contains("rescanblockchain is unavailable"))
        assertTrue(status.rescanBlockchainError.orEmpty().contains("before the first balance refresh"))
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when node has no relay peers`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":0}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertEquals(0, status.peerCount)
        assertTrue(status.relayError.orEmpty().contains("no connected peers"))
    }

    @Test
    fun `blockchain status allows broadcast on peerless regtest node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"regtest","blocks":150,"headers":150,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"regtest-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":0}""")
                    "help" -> rpcResponse(rpcString("testmempoolaccept \"rawtx\"\nrescanblockchain (start_height)"))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.REGTEST
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help", "help"), methods)
        assertEquals("regtest", status.chain)
        assertTrue(status.isReadyFor(DogecoinNetwork.REGTEST))
        // Regtest is peerless by design, yet broadcast/relay must be considered ready there.
        assertTrue(status.canBroadcastFor(DogecoinNetwork.REGTEST))
        assertEquals(true, status.relayReady)
        assertEquals(0, status.peerCount)
        // The peerless relaxation must not leak to the real networks.
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
    }

    @Test
    fun `rpc error returned with http 500 body is parsed instead of collapsing to a status code`() = runTest {
        // Dogecoin Core delivers JSON-RPC errors as HTTP 500 with the structured error in the body.
        // getBlockchainStatus must surface the node's actual message, not a bare "HTTP 500".
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithStatus(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> 500 to rpcError(-28, "Loading block index...")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("Loading block index"))
        assertTrue(status.error.orEmpty().contains("-28"))
    }

    @Test
    fun `broadcast surfaces node policy guidance when sendrawtransaction fails with http 500`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithStatus(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> 200 to rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> 200 to rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> 200 to rpcResponse("""[{"allowed":true}]""")
                    "sendrawtransaction" -> 500 to rpcError(-26, "min relay fee not met")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = sampleRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected broadcast to fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("fee is too low"))
            assertTrue(e.message.orEmpty().contains("min relay fee not met"))
        }
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when node omits peer count`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertEquals(null, status.peerCount)
        assertTrue(status.relayError.orEmpty().contains("did not report connected peers"))
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when relay peer count is invalid`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":-1}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertTrue(status.relayError.orEmpty().contains("negative connections"))
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when relay fee is invalid`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8,"relayfee":0}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertTrue(status.relayError.orEmpty().contains("non-positive relayfee"))
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when relay fee is string`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8,"relayfee":"0.02"}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertTrue(status.relayError.orEmpty().contains("invalid relayfee"))
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when node soft dust is string`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8,"softdustlimit":"0.01"}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertTrue(status.relayError.orEmpty().contains("invalid softdustlimit"))
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when soft dust is below hard dust`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse(
                        """{"networkactive":true,"connections":8,"softdustlimit":0.001,"harddustlimit":0.01}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertTrue(status.relayError.orEmpty().contains("softdustlimit below harddustlimit"))
    }

    @Test
    fun `blockchain status keeps broadcast unavailable when relay networkactive is not boolean`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":"true","connections":8}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "getnetworkinfo", "help"), methods)
        assertTrue(status.isReadyFor(DogecoinNetwork.MAINNET))
        assertFalse(status.canBroadcastFor(DogecoinNetwork.MAINNET))
        assertEquals(false, status.relayReady)
        assertTrue(status.relayError.orEmpty().contains("invalid networkactive"))
    }

    @Test
    fun `blockchain status keeps synced node unavailable when wallet rpc endpoint is not ready`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcError(
                        code = -18,
                        message = "Requested wallet does not exist or is not loaded"
                    )
                    "listwallets" -> rpcResponse("""["main-wallet","archive wallet"]""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "listwallets"), methods)
        assertTrue(status.connected)
        assertTrue(status.isUsable)
        assertFalse(status.isReady)
        assertEquals(false, status.walletReady)
        assertEquals(listOf("main-wallet", "archive wallet"), status.loadedWalletNames)
        assertTrue(status.walletError.orEmpty().contains("wallet is not loaded"))
        assertTrue(status.walletError.orEmpty().contains("main-wallet"))
        assertTrue(status.walletError.orEmpty().contains("Wallet name field"))
    }

    @Test
    fun `blockchain status keeps wallet name guidance when loaded wallet discovery is unavailable`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","blocks":5000000,"headers":5000000,"initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcError(
                        code = -18,
                        message = "Requested wallet does not exist or is not loaded"
                    )
                    "listwallets" -> rpcError(
                        code = -32601,
                        message = "Method not found"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "listwallets"), methods)
        assertEquals(false, status.walletReady)
        assertTrue(status.loadedWalletNames.isEmpty())
        assertTrue(status.walletError.orEmpty().contains("Wallet name field"))
        assertTrue(status.walletError.orEmpty().contains("node base endpoint"))
        assertFalse(status.walletError.orEmpty().contains("did not report any loaded wallets"))
    }

    @Test
    fun `blockchain status maps rpc authentication failure to setup message`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubHttpStatusClient(
                methods = methods,
                code = 401,
                message = "Unauthorized"
            )
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("authentication failed"))
        assertTrue(status.error.orEmpty().contains("username and password"))
    }

    @Test
    fun `blockchain status maps missing wallet endpoint to setup message`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubHttpStatusClient(
                methods = methods,
                code = 404,
                message = "Not Found"
            )
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo"), methods)
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("endpoint was not found"))
        assertTrue(status.error.orEmpty().contains("/wallet/<name>"))
    }

    @Test
    fun `blockchain status rejects public cleartext rpc url before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for invalid public cleartext URL")
            }
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.example.com:22555"),
            network = DogecoinNetwork.MAINNET
        )

        assertTrue(methods.isEmpty())
        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("valid Dogecoin RPC URL"))
        assertTrue(status.error.orEmpty().contains("HTTPS"))
    }

    @Test
    fun `rescan wallet history imports address then rescans blockchain`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcResponse(rpcString("rescanblockchain (start_height)"))
                    "importaddress" -> rpcResponse("null")
                    "rescanblockchain" -> rpcResponse("""{"start_height":0,"stop_height":123}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        val result = client.rescanWalletHistory(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress", "rescanblockchain"), methods)
        assertEquals(0, result.startHeight)
        assertEquals(123, result.stopHeight)
    }

    @Test
    fun `rescan wallet history passes optional start height to dogecoin core`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithParams(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcResponse(rpcString("rescanblockchain (start_height)"))
                    "importaddress" -> rpcResponse("null")
                    "rescanblockchain" -> rpcResponse("""{"start_height":5000000,"stop_height":5000123}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        val result = client.rescanWalletHistory(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = address,
            network = DogecoinNetwork.MAINNET,
            startHeight = 5_000_000
        )

        assertEquals(5_000_000, result.startHeight)
        assertEquals(5_000_123, result.stopHeight)
        assertEquals("rescanblockchain" to "[5000000]", requests.last())
    }

    @Test
    fun `rescan wallet history falls back to importaddress rescan when rescanblockchain is unavailable`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithParams(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcError(
                        code = -1,
                        message = "help: unknown command: rescanblockchain"
                    )
                    "importaddress" -> rpcResponse("null")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        val result = client.rescanWalletHistory(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = address,
            network = DogecoinNetwork.MAINNET,
            startHeight = 5_000_000
        )

        assertEquals(5_000_000, result.startHeight)
        assertEquals(null, result.stopHeight)
        assertEquals(
            listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress"),
            requests.map { it.first }
        )
        assertEquals(
            "importaddress" to """["$address","bitchat-dogecoin-mainnet",true,false,5000000]""",
            requests.last()
        )
    }

    @Test
    fun `rescan wallet history treats unknown command help text as unavailable rescanblockchain`() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithParams(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcResponse(rpcString("help: unknown command: rescanblockchain"))
                    "importaddress" -> rpcResponse("null")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        val result = client.rescanWalletHistory(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = address,
            network = DogecoinNetwork.MAINNET,
            startHeight = 5_000_000
        )

        assertEquals(5_000_000, result.startHeight)
        assertEquals(null, result.stopHeight)
        assertEquals(
            listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress"),
            requests.map { it.first }
        )
        assertEquals(
            "importaddress" to """["$address","bitchat-dogecoin-mainnet",true,false,5000000]""",
            requests.last()
        )
    }

    @Test
    fun `rescan wallet history reports already imported older core fallback clearly`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcError(
                        code = -1,
                        message = "help: unknown command: rescanblockchain"
                    )
                    "importaddress" -> rpcError(
                        code = -4,
                        message = "The wallet already contains the private key for this address or script"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.rescanWalletHistory(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected older Core already-imported rescan fallback rejection")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("rescanblockchain is unavailable"))
            assertTrue(message.contains("already imported"))
            assertTrue(message.contains("fresh Core wallet"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress"), methods)
    }

    @Test
    fun `rescan wallet history rejects negative start height before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for invalid rescan start height")
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.rescanWalletHistory(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET,
                startHeight = -1
            )
            fail("Expected negative start height rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("start height"))
            assertTrue(e.message.orEmpty().contains("non-negative"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `rescan wallet history accepts omitted rescan heights from node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcResponse(rpcString("rescanblockchain (start_height)"))
                    "importaddress" -> rpcResponse("null")
                    "rescanblockchain" -> rpcResponse("{}")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        val result = client.rescanWalletHistory(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress", "rescanblockchain"), methods)
        assertEquals(null, result.startHeight)
        assertEquals(null, result.stopHeight)
    }

    @Test
    fun `rescan wallet history rejects fractional rescan height from node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcResponse(rpcString("rescanblockchain (start_height)"))
                    "importaddress" -> rpcResponse("null")
                    "rescanblockchain" -> rpcResponse("""{"start_height":0.5,"stop_height":123}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.rescanWalletHistory(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected fractional rescan height rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid start_height"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress", "rescanblockchain"), methods)
    }

    @Test
    fun `rescan wallet history rejects string rescan height from node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcResponse(rpcString("rescanblockchain (start_height)"))
                    "importaddress" -> rpcResponse("null")
                    "rescanblockchain" -> rpcResponse("""{"start_height":"0","stop_height":123}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.rescanWalletHistory(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected string rescan height rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid start_height"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress", "rescanblockchain"), methods)
    }

    @Test
    fun `rescan wallet history rejects inverted rescan range from node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "help" -> rpcResponse(rpcString("rescanblockchain (start_height)"))
                    "importaddress" -> rpcResponse("null")
                    "rescanblockchain" -> rpcResponse("""{"start_height":124,"stop_height":123}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.rescanWalletHistory(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected inverted rescan range rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("stop height before the start height"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "help", "importaddress", "rescanblockchain"), methods)
    }

    @Test
    fun `rescan wallet history rejects pruned node before importing address`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","pruned":true,"initialblockdownload":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.rescanWalletHistory(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected pruned-node rescan rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("pruned"))
        }
        assertEquals(listOf("getblockchaininfo"), methods)
    }

    @Test
    fun `rescan wallet history rejects wrong node chain before importing address`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"test","initialblockdownload":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.rescanWalletHistory(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected wrong-chain rescan rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("expected mainnet"))
        }
        assertEquals(listOf("getblockchaininfo"), methods)
    }

    @Test
    fun `address watch status reports unimported wallet address`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"${wallet.address}","ismine":false,"iswatchonly":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getAddressWatchStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = wallet.address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "validateaddress"), methods)
        assertEquals(wallet.address, status.address)
        assertFalse(status.isMine)
        assertFalse(status.isWatchOnly)
        assertFalse(status.isImported)
    }

    @Test
    fun `address watch status reports imported watch only address`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"${wallet.address}","ismine":false,"iswatchonly":true}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val status = client.getAddressWatchStatus(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = wallet.address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "validateaddress"), methods)
        assertEquals(false, status.isMine)
        assertEquals(true, status.isWatchOnly)
        assertEquals(true, status.isImported)
    }

    @Test
    fun `address watch status rejects mismatched validateaddress response`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val otherWallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"${otherWallet.address}","ismine":false,"iswatchonly":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getAddressWatchStatus(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected mismatched validateaddress rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("different Dogecoin address"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "validateaddress"), methods)
    }

    @Test
    fun `address watch status rejects malformed watch only flag`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "validateaddress" -> rpcResponse(
                        """{"isvalid":true,"address":"${wallet.address}","ismine":false,"iswatchonly":"true"}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getAddressWatchStatus(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected malformed watch-only flag rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid iswatchonly"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "validateaddress"), methods)
    }

    @Test
    fun `wallet balance retains sorted utxo details`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val walletScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listunspent" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                            "vout":2,
                            "amount":1.0,
                            "scriptPubKey":"$walletScript",
                            "confirmations":0
                          },
                          {
                            "txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "vout":0,
                            "amount":2.0,
                            "scriptPubKey":"$walletScript",
                            "confirmations":6
                          },
                          {
                            "txid":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                            "vout":1,
                            "amount":3.0,
                            "scriptPubKey":"$walletScript",
                            "confirmations":2
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val balance = client.getWalletBalance(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = wallet.address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
        assertEquals(5L * DogecoinProtocol.KOINU_PER_DOGE, balance.confirmedKoinu)
        assertEquals(DogecoinProtocol.KOINU_PER_DOGE, balance.unconfirmedKoinu)
        assertEquals(3, balance.utxoCount)
        assertEquals(2, balance.confirmedUtxos.size)
        assertEquals(1, balance.unconfirmedUtxos.size)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", balance.utxos[0].txid)
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", balance.utxos[1].txid)
        assertEquals("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", balance.utxos[2].txid)
    }

    @Test
    fun `wallet balance rejects overflowing confirmed total from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val walletScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listunspent" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                            "vout":0,
                            "amount":92233720368.54775807,
                            "scriptPubKey":"$walletScript",
                            "confirmations":6
                          },
                          {
                            "txid":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                            "vout":1,
                            "amount":0.00000001,
                            "scriptPubKey":"$walletScript",
                            "confirmations":6
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected overflowing confirmed balance rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("confirmed Dogecoin balance total"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
    }

    @Test
    fun `wallet balance display total does not wrap for extreme copied values`() {
        val balance = DogecoinWalletBalance(
            confirmedKoinu = Long.MAX_VALUE,
            unconfirmedKoinu = 1L,
            utxoCount = 0
        )

        assertEquals(Long.MAX_VALUE, balance.totalKoinu)
    }

    @Test
    fun `wallet balance rejects malformed utxo txid from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val walletScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listunspent" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"not-a-txid",
                            "vout":0,
                            "amount":1.0,
                            "scriptPubKey":"$walletScript",
                            "confirmations":6
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected malformed UTXO txid rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid Dogecoin txid"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
    }

    @Test
    fun `wallet balance rejects string utxo amount from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val walletScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listunspent" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "vout":0,
                            "amount":"1.0",
                            "scriptPubKey":"$walletScript",
                            "confirmations":6
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected string UTXO amount rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid amount"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
    }

    @Test
    fun `wallet balance rejects fractional utxo vout from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val walletScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listunspent" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "vout":0.5,
                            "amount":1.0,
                            "scriptPubKey":"$walletScript",
                            "confirmations":6
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected fractional UTXO vout rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid vout"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
    }

    @Test
    fun `wallet balance rejects fractional utxo confirmations from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val walletScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(wallet.address, DogecoinNetwork.MAINNET)
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listunspent" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "vout":0,
                            "amount":1.0,
                            "scriptPubKey":"$walletScript",
                            "confirmations":6.5
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected fractional UTXO confirmations rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid confirmations"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
    }

    @Test
    fun `wallet balance rejects utxo script that does not match wallet address`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val otherWallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val otherScript = DogecoinHex.encode(
            DogecoinAddress.p2pkhScript(otherWallet.address, DogecoinNetwork.MAINNET)
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listunspent" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                            "vout":0,
                            "amount":1.0,
                            "scriptPubKey":"$otherScript",
                            "confirmations":6
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected mismatched UTXO script rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("does not match this wallet address"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
    }

    @Test
    fun `wallet activity parses listtransactions entries`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002"
        )
        val requests = mutableListOf<Pair<String, String>>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithParams(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                            "category":"receive",
                            "address":"${wallet.address}",
                            "amount":4.25,
                            "confirmations":12,
                            "time":1700000000,
                            "involvesWatchonly":true
                          },
                          {
                            "txid":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                            "category":"send",
                            "address":"${recipient.address}",
                            "amount":-1.5,
                            "fee":-0.01,
                            "confirmations":3,
                            "time":1700000100
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val activity = client.getWalletActivity(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = wallet.address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(
            listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"),
            requests.map { it.first }
        )
        assertEquals("listtransactions" to """["*",100,0,true]""", requests.last())
        assertEquals(1, activity.size)
        assertEquals("receive", activity[0].category)
        assertEquals(wallet.address, activity[0].address)
        assertEquals(425_000_000L, activity[0].amountKoinu)
        assertEquals(true, activity[0].involvesWatchOnly)
    }

    @Test
    fun `transaction confirmation lookup follows wallet route and parses zero to two`() = runTest {
        val txid = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        val requests = mutableListOf<Triple<String, String, String>>()
        var nextDepth = 0
        val client = DogecoinRpcClient(
            httpClient = stubRpcClientWithPathAndParams(requests) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"test","initialblockdownload":false}"""
                    )
                    "gettransaction" -> rpcResponse(
                        """{"txid":"$txid","confirmations":${nextDepth++}}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val config = DogecoinRpcConfig(
            url = "http://dogecoin.local:44555/rpc",
            walletName = "bitchat watch"
        )

        val depths = List(3) {
            client.getTransactionConfirmations(config, txid, DogecoinNetwork.TESTNET)
        }

        assertEquals(listOf(0, 1, 2), depths)
        assertEquals(
            listOf(
                Triple("getblockchaininfo", "/rpc", "[]"),
                Triple("gettransaction", "/rpc/wallet/bitchat%20watch", "[\"$txid\",true]"),
                Triple("getblockchaininfo", "/rpc", "[]"),
                Triple("gettransaction", "/rpc/wallet/bitchat%20watch", "[\"$txid\",true]"),
                Triple("getblockchaininfo", "/rpc", "[]"),
                Triple("gettransaction", "/rpc/wallet/bitchat%20watch", "[\"$txid\",true]")
            ),
            requests
        )
    }

    @Test
    fun `transaction confirmation lookup rejects negative node depth`() = runTest {
        val txid = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"test","initialblockdownload":false}"""
                    )
                    "gettransaction" -> rpcResponse(
                        """{"txid":"$txid","confirmations":-1}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getTransactionConfirmations(
                DogecoinRpcConfig(url = "http://dogecoin.local:44555"),
                txid,
                DogecoinNetwork.TESTNET
            )
            fail("Expected negative confirmations rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("negative confirmations"))
        }
        assertEquals(listOf("getblockchaininfo", "gettransaction"), methods)
    }

    @Test
    fun `transaction confirmation lookup rejects a different returned txid`() = runTest {
        val requestedTxid = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"test","initialblockdownload":false}"""
                    )
                    "gettransaction" -> rpcResponse(
                        """{"txid":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","confirmations":1}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getTransactionConfirmations(
                DogecoinRpcConfig(url = "http://dogecoin.local:44555"),
                requestedTxid,
                DogecoinNetwork.TESTNET
            )
            fail("Expected mismatched transaction id rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("different txid"))
        }
        assertEquals(listOf("getblockchaininfo", "gettransaction"), methods)
    }

    @Test
    fun `wallet activity rejects malformed transaction id from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"not-a-txid",
                            "category":"receive",
                            "address":"${wallet.address}",
                            "amount":1.0,
                            "confirmations":1
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletActivity(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected malformed wallet activity txid rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid Dogecoin txid"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"), methods)
    }

    @Test
    fun `wallet activity rejects string amount from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                            "category":"receive",
                            "address":"${wallet.address}",
                            "amount":"1.0",
                            "confirmations":1
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletActivity(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected string wallet activity amount rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid amount"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"), methods)
    }

    @Test
    fun `wallet activity rejects string fee from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
                            "category":"send",
                            "address":"${wallet.address}",
                            "amount":-1.0,
                            "fee":"-0.01",
                            "confirmations":1
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletActivity(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected string wallet activity fee rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid fee"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"), methods)
    }

    @Test
    fun `wallet activity ignores unrelated malformed activity rows from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001",
            DogecoinNetwork.MAINNET
        )
        val recipient = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000002",
            DogecoinNetwork.MAINNET
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"not-a-txid",
                            "category":"send",
                            "address":"${recipient.address}",
                            "amount":"bad",
                            "fee":"bad",
                            "confirmations":1.5
                          },
                          {
                            "txid":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                            "category":"receive",
                            "address":"${wallet.address}",
                            "amount":1.0,
                            "confirmations":1,
                            "time":1700000000
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val activity = client.getWalletActivity(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = wallet.address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(1, activity.size)
        assertEquals(wallet.address, activity.single().address)
        assertEquals(100_000_000L, activity.single().amountKoinu)
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"), methods)
    }

    @Test
    fun `wallet activity rejects fractional confirmations from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                            "category":"receive",
                            "address":"${wallet.address}",
                            "amount":1.0,
                            "confirmations":1.5
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletActivity(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected fractional activity confirmations rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid confirmations"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"), methods)
    }

    @Test
    fun `wallet activity rejects fractional time from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                            "category":"receive",
                            "address":"${wallet.address}",
                            "amount":1.0,
                            "confirmations":1,
                            "time":1700000000.5
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletActivity(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected fractional activity time rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid time"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"), methods)
    }

    @Test
    fun `wallet activity rejects string involves watchonly flag from node`() = runTest {
        val wallet = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        )
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcResponse("null")
                    "listtransactions" -> rpcResponse(
                        """
                        [
                          {
                            "txid":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                            "category":"receive",
                            "address":"${wallet.address}",
                            "amount":1.0,
                            "confirmations":1,
                            "involvesWatchonly":"true"
                          }
                        ]
                        """.trimIndent()
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.getWalletActivity(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = wallet.address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected string involvesWatchonly rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid involvesWatchonly"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listtransactions"), methods)
    }

    @Test
    fun `wallet balance maps unloaded wallet rpc error to actionable wallet name message`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcError(
                        code = -18,
                        message = "Requested wallet does not exist or is not loaded"
                    )
                    "listwallets" -> rpcResponse("""["main-wallet"]""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected unloaded wallet RPC rejection")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("wallet is not loaded"))
            assertTrue(message.contains("Wallet name field"))
            assertTrue(message.contains("node base endpoint"))
            assertTrue(message.contains("main-wallet"))
            assertTrue(message.contains("getwalletinfo"))
            assertTrue(message.contains("Requested wallet does not exist or is not loaded"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "listwallets"), methods)
    }

    @Test
    fun `wallet balance rejects string rpc error code from node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcErrorObject(
                        """{"code":"-18","message":"Requested wallet does not exist or is not loaded"}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected malformed RPC error code rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("invalid error code"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo"), methods)
    }

    @Test
    fun `wallet balance rejects non-string rpc error message from node`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcErrorObject("""{"code":-18,"message":123}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected malformed RPC error message rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("invalid error message"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo"), methods)
    }

    @Test
    fun `wallet balance tolerates already imported watch address`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcError(
                        code = -4,
                        message = "The wallet already contains the private key for this address or script"
                    )
                    "listunspent" -> rpcResponse("[]")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        val balance = client.getWalletBalance(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            address = address,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(0, balance.utxoCount)
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress", "listunspent"), methods)
    }

    @Test
    fun `wallet balance does not hide unrelated already import errors`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcError(
                        code = -4,
                        message = "Wallet already has a rescan in progress"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected unrelated importaddress error to remain visible")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("importaddress"))
            assertTrue(message.contains("rescan in progress"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress"), methods)
    }

    @Test
    fun `wallet balance maps unavailable wallet rpc method to actionable wallet support message`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getwalletinfo" -> rpcResponse("""{"walletname":"main-wallet"}""")
                    "importaddress" -> rpcError(
                        code = -32601,
                        message = "Method not found"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )
        val address = DogecoinKeyGenerator.fromPrivateKeyHex(
            "0000000000000000000000000000000000000000000000000000000000000001"
        ).address

        try {
            client.getWalletBalance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                address = address,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected missing wallet RPC method rejection")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("wallet RPC method importaddress is unavailable"))
            assertTrue(message.contains("wallet support"))
            assertTrue(message.contains("watch-only imports"))
            assertTrue(message.contains("Method not found"))
        }
        assertEquals(listOf("getblockchaininfo", "getwalletinfo", "importaddress"), methods)
    }

    @Test
    fun `mempool acceptance reports accepted signed transaction`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid.uppercase())},"allowed":true}]"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val acceptance = client.testMempoolAcceptance(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            rawTransactionHex = rawTransactionHex,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "testmempoolaccept"), methods)
        assertTrue(acceptance.checked)
        assertTrue(acceptance.isAllowed)
        assertEquals(true, acceptance.allowed)
        assertEquals(expectedTxid, acceptance.txid)
        assertEquals(null, acceptance.rejectReason)
        assertEquals(null, acceptance.error)
    }

    @Test
    fun `mempool acceptance reports policy rejection with guidance`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":false,"reject-reason":"min relay fee not met"}]"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val acceptance = client.testMempoolAcceptance(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            rawTransactionHex = rawTransactionHex,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "testmempoolaccept"), methods)
        assertTrue(acceptance.checked)
        assertFalse(acceptance.isAllowed)
        assertEquals(false, acceptance.allowed)
        assertEquals("min relay fee not met", acceptance.rejectReason)
        assertTrue(acceptance.error.orEmpty().contains("fee is too low"))
        assertTrue(acceptance.error.orEmpty().contains("Increase the DOGE/kB fee rate"))
    }

    @Test
    fun `mempool acceptance rejects string allowed flag from node`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":"true"}]"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.testMempoolAcceptance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected string allowed flag rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid allowed"))
        }
        assertEquals(listOf("getblockchaininfo", "testmempoolaccept"), methods)
    }

    @Test
    fun `mempool acceptance rejects non-string node txid`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":123,"allowed":true}]"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.testMempoolAcceptance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected non-string mempool txid rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid txid"))
        }
        assertEquals(listOf("getblockchaininfo", "testmempoolaccept"), methods)
    }

    @Test
    fun `mempool acceptance rejects non-string reject reason from node`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":false,"reject-reason":123}]"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.testMempoolAcceptance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected non-string mempool reject reason rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid reject-reason"))
        }
        assertEquals(listOf("getblockchaininfo", "testmempoolaccept"), methods)
    }

    @Test
    fun `mempool acceptance permits standard p2sh raw transaction output shape`() = runTest {
        val rawTransactionHex = sampleP2shRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val acceptance = client.testMempoolAcceptance(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            rawTransactionHex = rawTransactionHex,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "testmempoolaccept"), methods)
        assertTrue(acceptance.isAllowed)
        assertEquals(expectedTxid, acceptance.txid)
    }

    @Test
    fun `mempool acceptance falls back when node does not support policy check`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "testmempoolaccept" -> rpcError(
                        code = -32601,
                        message = "Method not found"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val acceptance = client.testMempoolAcceptance(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            rawTransactionHex = sampleRawTransactionHex,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "testmempoolaccept"), methods)
        assertFalse(acceptance.checked)
        assertFalse(acceptance.isAllowed)
        assertEquals(null, acceptance.allowed)
        assertTrue(acceptance.error.orEmpty().contains("testmempoolaccept is unavailable"))
    }

    @Test
    fun `mempool acceptance rejects no-input raw transaction before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for malformed raw transaction")
            }
        )

        try {
            client.testMempoolAcceptance(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = "01000000000000000000",
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected no-input raw transaction rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("at least one input"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `send raw transaction verifies returned txid against signed bytes`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    "sendrawtransaction" -> rpcResponse(rpcString(expectedTxid.uppercase()))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val txid = client.sendRawTransaction(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            rawTransactionHex = rawTransactionHex,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
        assertEquals(expectedTxid, txid)
    }

    @Test
    fun `send raw transaction rejects string initial block download flag before relay check`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":"false"}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = sampleRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected invalid initial block download flag rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid initialblockdownload"))
        }
        assertEquals(listOf("getblockchaininfo"), methods)
    }

    @Test
    fun `send raw transaction rejects non-string chain before relay check`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":123,"initialblockdownload":false}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = sampleRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected invalid chain rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid chain"))
        }
        assertEquals(listOf("getblockchaininfo"), methods)
    }

    @Test
    fun `send raw transaction falls back to broadcast when final policy check is unsupported`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcError(
                        code = -32601,
                        message = "Method not found"
                    )
                    "sendrawtransaction" -> rpcResponse(rpcString(expectedTxid))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        val txid = client.sendRawTransaction(
            config = DogecoinRpcConfig(url = "http://dogecoin.local"),
            rawTransactionHex = rawTransactionHex,
            network = DogecoinNetwork.MAINNET
        )

        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
        assertEquals(expectedTxid, txid)
    }

    @Test
    fun `send raw transaction rejects output below node soft dust before mempool fallback`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse(
                        """{"networkactive":true,"connections":8,"softdustlimit":2.0}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = sampleRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected node soft dust raw transaction rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("node soft dust limit"))
            assertTrue(e.message.orEmpty().contains("2 DOGE"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo"), methods)
    }

    @Test
    fun `send raw transaction rejects final mempool policy failure before broadcast`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":false,"reject-reason":"bad-txns-inputs-missingorspent"}]"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected final mempool policy rejection")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("missing or already spent"))
            assertTrue(message.contains("Refresh wallet balance"))
            assertTrue(message.contains("bad-txns-inputs-missingorspent"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept"), methods)
    }

    @Test
    fun `send raw transaction rejects mismatched node txid`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val mismatchedTxid = expectedTxid.replaceFirstChar { if (it == '0') '1' else '0' }
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    "sendrawtransaction" -> rpcResponse(rpcString(mismatchedTxid))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected mismatched broadcast txid rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("did not match"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
    }

    @Test
    fun `send raw transaction rejects malformed node txid`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    "sendrawtransaction" -> rpcResponse(rpcString("not-a-txid"))
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected malformed broadcast txid rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid Dogecoin txid"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
    }

    @Test
    fun `send raw transaction rejects non-string broadcast txid result`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    "sendrawtransaction" -> rpcResponse("123")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected non-string broadcast txid rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid txid"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
    }

    @Test
    fun `send raw transaction rejects node with no relay peers before broadcast`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":0}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = sampleRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected no-peer broadcast readiness rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("no connected peers"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo"), methods)
    }

    @Test
    fun `send raw transaction rejects missing relay peer count before broadcast`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true}""")
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = sampleRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected missing-peer-count broadcast readiness rejection")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("did not report connected peers"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo"), methods)
    }

    @Test
    fun `send raw transaction rejects public cleartext rpc url before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for invalid public cleartext URL")
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.example.com:22555"),
                rawTransactionHex = sampleRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected public cleartext RPC URL rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("valid Dogecoin RPC URL"))
            assertTrue(e.message.orEmpty().contains("HTTPS"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `send raw transaction rejects non-hex raw transaction before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for malformed raw transaction")
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = "zz",
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected non-hex raw transaction rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("only hex characters"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `send raw transaction rejects zero output amount before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for malformed raw transaction")
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = zeroOutputRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected zero output raw transaction rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("output 0 amount must be positive"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `send raw transaction rejects dust output amount before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for malformed raw transaction")
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = dustOutputRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected dust output raw transaction rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("standard output minimum"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `send raw transaction rejects non-standard output script before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for non-standard raw transaction")
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = nonStandardOutputRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected non-standard output script rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("standard P2PKH or P2SH"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `send raw transaction rejects overflowing output total before node call`() = runTest {
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) {
                error("RPC should not be called for malformed raw transaction")
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = overflowingOutputTotalRawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected overflowing output total rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("output total is too large"))
        }
        assertTrue(methods.isEmpty())
    }

    @Test
    fun `send raw transaction maps missing inputs rejection to refresh guidance`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    "sendrawtransaction" -> rpcError(
                        code = -25,
                        message = "bad-txns-inputs-missingorspent"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected missing-input broadcast rejection")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("missing or already spent"))
            assertTrue(message.contains("Refresh wallet balance"))
            assertTrue(message.contains("bad-txns-inputs-missingorspent"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
    }

    @Test
    fun `send raw transaction maps low fee rejection to fee rate guidance`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    "sendrawtransaction" -> rpcError(
                        code = -26,
                        message = "min relay fee not met"
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected low-fee broadcast rejection")
        } catch (e: IllegalStateException) {
            val message = e.message.orEmpty()
            assertTrue(message.contains("fee is too low"))
            assertTrue(message.contains("Increase the DOGE/kB fee rate"))
            assertTrue(message.contains("min relay fee not met"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
    }

    @Test
    fun `send raw transaction rejects string broadcast rpc error code from node`() = runTest {
        val rawTransactionHex = sampleRawTransactionHex
        val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
        val methods = mutableListOf<String>()
        val client = DogecoinRpcClient(
            httpClient = stubRpcClient(methods) { method ->
                when (method) {
                    "getblockchaininfo" -> rpcResponse(
                        """{"chain":"main","initialblockdownload":false}"""
                    )
                    "getnetworkinfo" -> rpcResponse("""{"networkactive":true,"connections":8}""")
                    "testmempoolaccept" -> rpcResponse(
                        """[{"txid":${rpcString(expectedTxid)},"allowed":true}]"""
                    )
                    "sendrawtransaction" -> rpcErrorObject(
                        """{"code":"-26","message":"min relay fee not met"}"""
                    )
                    else -> error("Unexpected RPC method $method")
                }
            }
        )

        try {
            client.sendRawTransaction(
                config = DogecoinRpcConfig(url = "http://dogecoin.local"),
                rawTransactionHex = rawTransactionHex,
                network = DogecoinNetwork.MAINNET
            )
            fail("Expected malformed broadcast RPC error code rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("invalid error code"))
        }
        assertEquals(listOf("getblockchaininfo", "getnetworkinfo", "testmempoolaccept", "sendrawtransaction"), methods)
    }

    private fun stubRpcClient(
        methods: MutableList<String>,
        resultForMethod: (String) -> String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val method = rpcMethod(chain.request().body)
                methods.add(method)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(resultForMethod(method).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun stubRpcClientWithPaths(
        requests: MutableList<Pair<String, String>>,
        resultForMethod: (String) -> String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val method = rpcMethod(chain.request().body)
                requests.add(method to chain.request().url.encodedPath)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(resultForMethod(method).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun stubRpcClientWithParams(
        requests: MutableList<Pair<String, String>>,
        resultForMethod: (String) -> String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val body = chain.request().body
                val method = rpcMethod(body)
                requests.add(method to rpcParams(body))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(resultForMethod(method).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun stubRpcClientWithPathAndParams(
        requests: MutableList<Triple<String, String, String>>,
        resultForMethod: (String) -> String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val body = chain.request().body
                val method = rpcMethod(body)
                requests.add(Triple(method, chain.request().url.encodedPath, rpcParams(body)))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(resultForMethod(method).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun stubRpcClientWithAuthHeaders(
        requests: MutableList<Pair<String, String?>>,
        resultForMethod: (String) -> String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val method = rpcMethod(chain.request().body)
                requests.add(method to chain.request().header("Authorization"))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(resultForMethod(method).toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun stubHttpStatusClient(
        methods: MutableList<String>,
        code: Int,
        message: String
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                methods.add(rpcMethod(chain.request().body))
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(message)
                    .body("".toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun stubRpcClientWithStatus(
        methods: MutableList<String>,
        handler: (String) -> Pair<Int, String>
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val method = rpcMethod(chain.request().body)
                methods.add(method)
                val (code, body) = handler(method)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(code)
                    .message(if (code in 200..299) "OK" else "Error")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            })
            .build()
    }

    private fun rpcMethod(body: okhttp3.RequestBody?): String {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return gson.fromJson(buffer.readUtf8(), JsonObject::class.java)
            .get("method")
            .asString
    }

    private fun rpcParams(body: okhttp3.RequestBody?): String {
        val buffer = Buffer()
        requireNotNull(body).writeTo(buffer)
        return gson.fromJson(buffer.readUtf8(), JsonObject::class.java)
            .get("params")
            .asJsonArray
            .toString()
    }

    private fun rpcResponse(result: String): String {
        return """{"result":$result,"error":null,"id":"bitchat-dogecoin"}"""
    }

    private fun rpcError(code: Int, message: String): String {
        return """{"result":null,"error":{"code":$code,"message":${rpcString(message)}},"id":"bitchat-dogecoin"}"""
    }

    private fun rpcErrorObject(errorObject: String): String {
        return """{"result":null,"error":$errorObject,"id":"bitchat-dogecoin"}"""
    }

    private fun rpcString(value: String): String {
        return gson.toJson(value)
    }
}
