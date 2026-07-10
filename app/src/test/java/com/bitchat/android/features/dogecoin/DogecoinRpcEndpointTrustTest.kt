package com.bitchat.android.features.dogecoin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RPC-guardrail slice: the central endpoint/trust classifier. URL syntax validity is never a trust
 * decision — these tests pin exactly which endpoint shapes may carry authenticated node RPC at all.
 */
class DogecoinRpcEndpointTrustTest {

    // ---- explicit local / private hosts (the "my own node on my own network" path) ----

    @Test
    fun `loopback, RFC1918, link-local, mDNS, and single-label hosts classify LOCAL_PRIVATE`() {
        listOf(
            "http://localhost:44555",
            "http://127.0.0.1:44555",
            "http://10.0.2.2:44555",
            "http://10.255.255.255:44555",
            "http://192.168.1.44:22555",
            "http://172.16.0.1:44555",
            "http://172.31.9.9:44555",
            "http://169.254.10.10:44555",
            "http://[::1]:44555",
            "http://[fd00::1]:44555",
            "http://[fe80::1]:44555",
            "http://[fe90::1]:44555",
            "http://[febf::1]:44555",
            "http://dogebox.local:44555",
            "http://dogebox:44555",
            // https to a private host is still the local path
            "https://192.168.1.44:44555"
        ).forEach { url ->
            assertEquals(url, DogecoinRpcEndpointClass.LOCAL_PRIVATE, classifyDogecoinRpcEndpoint(url))
            assertTrue(url, classifyDogecoinRpcEndpoint(url).isTrustedRpcRoute)
        }
    }

    @Test
    fun `RFC6598 carrier-NAT space is NOT local - Tailscale ownership of a 100_64 route is never assumed`() {
        // Review correction locked into the continuation prompt: 100.64.0.0/10 is shared carrier-NAT
        // space, not proof that a private tunnel owns the active route.
        assertEquals(DogecoinRpcEndpointClass.INVALID, classifyDogecoinRpcEndpoint("http://100.64.0.7:44555"))
        assertEquals(
            DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            classifyDogecoinRpcEndpoint("https://100.64.0.7:44555")
        )
        assertFalse(classifyDogecoinRpcEndpoint("http://100.64.0.7:44555").isTrustedRpcRoute)
        assertFalse(classifyDogecoinRpcEndpoint("https://100.64.0.7:44555").isTrustedRpcRoute)
    }

    @Test
    fun `routable names that merely start with a private prefix are not local`() {
        assertEquals(DogecoinRpcEndpointClass.INVALID, classifyDogecoinRpcEndpoint("http://10.attacker.com:44555"))
        assertEquals(
            DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            classifyDogecoinRpcEndpoint("https://192.168.evil.example.com")
        )
        listOf(
            "http://134744072:44555", // platform DNS resolves this one-part form to public 8.8.8.8
            "http://0x08080808:44555",
            "http://010.0.0.1:44555",
            // fc::/fd:: are low numeric hextets (00fc/00fd), not fc00::/7.
            "http://[fc::1]:44555",
            "http://[fd::1]:44555"
        ).forEach { url ->
            assertFalse(url, classifyDogecoinRpcEndpoint(url).isTrustedRpcRoute)
        }
        listOf("http://[fc00::1]:44555", "http://[fdff::1]:44555").forEach { url ->
            assertEquals(url, DogecoinRpcEndpointClass.LOCAL_PRIVATE, classifyDogecoinRpcEndpoint(url))
        }
    }

    // ---- exact Tailscale Serve origin ----

    @Test
    fun `exact tailscale serve https origin classifies TAILSCALE_HTTPS and is trusted`() {
        listOf(
            "https://dogelaptop.tail1234.ts.net",
            "https://dogelaptop.tail1234.ts.net/",
            "https://dogelaptop.tail1234.ts.net:443",
            "HTTPS://DOGELAPTOP.TAIL1234.TS.NET"
        ).forEach { url ->
            assertEquals(url, DogecoinRpcEndpointClass.TAILSCALE_HTTPS, classifyDogecoinRpcEndpoint(url))
            assertTrue(url, classifyDogecoinRpcEndpoint(url).isTrustedRpcRoute)
        }
    }

    @Test
    fun `loosened tailscale variants are NOT the provisioned origin`() {
        mapOf(
            // http to a ts.net name is cleartext-public -> INVALID
            "http://dogelaptop.tail1234.ts.net" to DogecoinRpcEndpointClass.INVALID,
            // alternate port, userinfo, path, query, fragment -> not the exact Serve origin
            "https://dogelaptop.tail1234.ts.net:8443" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://user:pw@dogelaptop.tail1234.ts.net" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://@dogelaptop.tail1234.ts.net" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://dogelaptop.tail1234.ts.net/wallet" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://dogelaptop.tail1234.ts.net/?x=1" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://dogelaptop.tail1234.ts.net/#frag" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            // missing the machine or tailnet label / bare suffix
            "https://ts.net" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://tail1234.ts.net" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://sub.dogelaptop.tail1234.ts.net" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://dogelaptop.tail1234.ts.net." to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            // lookalike domains
            "https://dogelaptop.tail1234.ts.net.evil.com" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            "https://evil-ts.net" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED,
            // Cyrillic `е` in the final label is canonicalized to punycode, never mistaken for ts.net.
            "https://dogelaptop.tail1234.ts.nеt" to DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED
        ).forEach { (url, expected) ->
            assertEquals(url, expected, classifyDogecoinRpcEndpoint(url))
            assertFalse(url, classifyDogecoinRpcEndpoint(url).isTrustedRpcRoute)
        }
    }

    // ---- unverified public HTTPS / onion / invalid ----

    @Test
    fun `public https is classified unverified and untrusted`() {
        val endpointClass = classifyDogecoinRpcEndpoint("https://rpc.example.com:44555")
        assertEquals(DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED, endpointClass)
        assertFalse(endpointClass.isTrustedRpcRoute)
    }

    @Test
    fun `onion hosts are ONION_DIRECT regardless of scheme`() {
        listOf(
            "https://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion",
            "http://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion:44555"
        ).forEach { url ->
            assertEquals(url, DogecoinRpcEndpointClass.ONION_DIRECT, classifyDogecoinRpcEndpoint(url))
            assertFalse(url, classifyDogecoinRpcEndpoint(url).isTrustedRpcRoute)
        }
    }

    @Test
    fun `blank, malformed, and cleartext-public urls are INVALID`() {
        listOf(
            "",
            "   ",
            "not a url",
            "ftp://192.168.1.44",
            "http://rpc.example.com:44555",
            "http://8.8.8.8:44555"
        ).forEach { url ->
            assertEquals(url, DogecoinRpcEndpointClass.INVALID, classifyDogecoinRpcEndpoint(url))
            assertFalse(url, classifyDogecoinRpcEndpoint(url).isTrustedRpcRoute)
        }
    }

    // ---- purpose-specific gates ----

    @Test
    fun `node assist is eligible only off-mainnet and only for trusted endpoints`() {
        val local = classifyDogecoinRpcEndpoint("http://192.168.1.44:44555")
        val tailscale = classifyDogecoinRpcEndpoint("https://dogelaptop.tail1234.ts.net")
        val public = classifyDogecoinRpcEndpoint("https://rpc.example.com")

        assertTrue(dogecoinNodeAssistEligible(DogecoinNetwork.TESTNET, local))
        assertTrue(dogecoinNodeAssistEligible(DogecoinNetwork.TESTNET, tailscale))
        assertTrue(dogecoinNodeAssistEligible(DogecoinNetwork.REGTEST, local))
        // Mainnet node reads stay behind the explicit pin flow — never assist-eligible.
        assertFalse(dogecoinNodeAssistEligible(DogecoinNetwork.MAINNET, local))
        assertFalse(dogecoinNodeAssistEligible(DogecoinNetwork.MAINNET, tailscale))
        // An unverified public URL never appears assist-ready on any network.
        assertFalse(dogecoinNodeAssistEligible(DogecoinNetwork.TESTNET, public))
    }

    @Test
    fun `url syntax validity is not a trust decision`() {
        // hasValidUrl accepts any well-formed HTTPS URL (syntax check), including ones the trust
        // classifier refuses — pinning that the two are distinct layers.
        val publicHttps = "https://rpc.example.com:44555"
        assertTrue(DogecoinRpcConfig(url = publicHttps).hasValidUrl(DogecoinNetwork.TESTNET))
        assertFalse(classifyDogecoinRpcEndpoint(publicHttps).isTrustedRpcRoute)

        val onionHttps = "https://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion"
        assertTrue(DogecoinRpcConfig(url = onionHttps).hasValidUrl(DogecoinNetwork.TESTNET))
        assertFalse(classifyDogecoinRpcEndpoint(onionHttps).isTrustedRpcRoute)
    }

    @Test
    fun `requireTrustedDogecoinRpcRoute passes trusted and fails closed with a specific reason`() {
        // Trusted routes pass silently.
        requireTrustedDogecoinRpcRoute(DogecoinRpcConfig(url = "http://127.0.0.1:44555"))
        requireTrustedDogecoinRpcRoute(DogecoinRpcConfig(url = "https://dogelaptop.tail1234.ts.net"))

        fun messageFor(url: String): String = runCatching {
            requireTrustedDogecoinRpcRoute(DogecoinRpcConfig(url = url))
        }.exceptionOrNull()?.message ?: ""

        assertTrue(messageFor("https://rpc.example.com").contains("public HTTPS"))
        assertTrue(
            messageFor("https://vww6ybal4bd7szmgncyruucpgfkqahzddi37ktceo3ah7ngmcopnpyyd.onion")
                .contains(".onion")
        )
        assertTrue(messageFor("http://8.8.8.8:44555").isNotBlank())
        assertTrue(messageFor("").isNotBlank())
    }
}
