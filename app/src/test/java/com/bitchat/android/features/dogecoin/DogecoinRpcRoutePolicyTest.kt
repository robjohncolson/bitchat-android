package com.bitchat.android.features.dogecoin

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * RPC-guardrail slice, client chokepoint: route policy and transport hardening are enforced INSIDE
 * [DogecoinRpcClient], so no caller (wallet UI, broadcast helper, debug console) and no directly
 * constructed client instance can bypass them.
 */
class DogecoinRpcRoutePolicyTest {

    private fun recordingClient(
        requests: MutableList<String>,
        code: Int = 200,
        headers: Map<String, String> = emptyMap(),
        body: () -> String = { """{"result":{"chain":"test"},"error":null,"id":"x"}""" }
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            requests.add(chain.request().url.toString())
            val builder = Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(if (code in 200..299) "OK" else "Err")
                .body(body().toResponseBody("application/json".toMediaType()))
            headers.forEach { (name, value) -> builder.header(name, value) }
            builder.build()
        })
        .build()

    @Test
    fun `unverified public https endpoint is refused with zero network requests`() = runTest {
        val requests = mutableListOf<String>()
        val client = DogecoinRpcClient(recordingClient(requests))

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "https://rpc.example.com:44555", username = "u", password = "p"),
            network = DogecoinNetwork.TESTNET
        )

        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("public HTTPS"))
        assertTrue("no request may be built for an untrusted endpoint", requests.isEmpty())
    }

    @Test
    fun `direct onion endpoint is refused with zero network requests`() = runTest {
        val requests = mutableListOf<String>()
        val client = DogecoinRpcClient(recordingClient(requests))

        val result = runCatching {
            client.listUnspent(
                config = DogecoinRpcConfig(
                    url = "https://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion"
                ),
                address = "nnimSKuWnp5Y6ZowogZtmfm1x91b8k3FQz",
                network = DogecoinNetwork.TESTNET
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains(".onion"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `cleartext public endpoint is refused with zero network requests`() = runTest {
        val requests = mutableListOf<String>()
        val client = DogecoinRpcClient(recordingClient(requests))

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://rpc.example.com:44555"),
            network = DogecoinNetwork.TESTNET
        )

        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().isNotBlank())
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `redirect responses are surfaced as errors and never followed`() = runTest {
        val requests = mutableListOf<String>()
        val client = DogecoinRpcClient(
            recordingClient(
                requests,
                code = 307,
                headers = mapOf("Location" to "https://evil.example.com/steal"),
                body = { """{"result":null,"error":{"code":-1,"message":"misleading node error"}}""" }
            )
        )

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://127.0.0.1:44555", username = "u", password = "p"),
            network = DogecoinNetwork.TESTNET
        )

        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("redirected"))
        assertFalse(status.error.orEmpty().contains("misleading node error"))
        // Exactly one request: the redirect target is never contacted, so credentials never leave the origin.
        assertEquals(1, requests.size)
        assertTrue(requests.single().startsWith("http://127.0.0.1:44555"))
    }

    @Test
    fun `oversized response bodies are refused instead of buffered`() = runTest {
        val requests = mutableListOf<String>()
        val oversize = DogecoinRpcClient.DOGECOIN_RPC_MAX_RESPONSE_BYTES.toInt() + 16
        val client = DogecoinRpcClient(recordingClient(requests, body = { "x".repeat(oversize) }))

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://127.0.0.1:44555"),
            network = DogecoinNetwork.TESTNET
        )

        assertFalse(status.connected)
        assertTrue(status.error.orEmpty().contains("exceeded"))
    }

    @Test
    fun `hardening wrapper disables both redirect modes even on a permissive base client`() {
        val permissive = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val hardened = hardenedDogecoinRpcHttpClient(permissive)

        assertFalse(hardened.followRedirects)
        assertFalse(hardened.followSslRedirects)
    }

    @Test
    fun `trusted local endpoint still works through the chokepoint`() = runTest {
        val requests = mutableListOf<String>()
        val client = DogecoinRpcClient(recordingClient(requests))

        val status = client.getBlockchainStatus(
            config = DogecoinRpcConfig(url = "http://127.0.0.1:44555"),
            network = DogecoinNetwork.TESTNET
        )

        assertTrue(status.connected)
        assertTrue(requests.isNotEmpty())

        // A generation-specific lease is checked again for every RPC in one public client call.
        val generation = AtomicInteger(0)
        val leasedRequests = mutableListOf<String>()
        val baseClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                leasedRequests.add(chain.request().url.toString())
                generation.incrementAndGet() // Simulate Save while the first request is in flight.
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"result":{"chain":"test","initialblockdownload":false},"error":null,"id":"x"}"""
                            .toResponseBody("application/json".toMediaType())
                    )
                    .build()
            })
            .build()
        val leasedClient = DogecoinRpcClient(baseClient).guardedBy {
            check(generation.get() == 0) { "route lease revoked" }
        }

        val result = runCatching {
            leasedClient.listUnspent(
                config = DogecoinRpcConfig(url = "http://127.0.0.1:44555"),
                address = "nnimSKuWnp5Y6ZowogZtmfm1x91b8k3FQz",
                network = DogecoinNetwork.TESTNET
            )
        }
        assertTrue(result.isFailure)
        assertEquals("the second RPC must be blocked before reaching OkHttp", 1, leasedRequests.size)
    }
}
