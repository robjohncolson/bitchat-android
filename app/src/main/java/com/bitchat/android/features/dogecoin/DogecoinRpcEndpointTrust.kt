package com.bitchat.android.features.dogecoin

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Central endpoint/trust classifier for Dogecoin node RPC routes (spec: dogecoin-remote-node-access,
 * RPC-guardrail slice). Every decision about whether the app may perform authenticated RPC I/O against a
 * configured node URL goes through this classification — URL *syntax* validity ([DogecoinRpcConfig.hasValidUrl])
 * is never a trust decision.
 *
 * Trust ladder (most to least trusted):
 *  - [LOCAL_PRIVATE]: loopback / RFC1918 / link-local / ULA / `.local` / single-label hosts — the explicit
 *    "my own node on my own network" path (adb reverse, LAN, emulator).
 *  - [TAILSCALE_HTTPS]: an exact `https://<machine>.<tailnet>.ts.net` origin on the default port with no
 *    userinfo/path/query/fragment — the provisioned private-tunnel path (Tailscale Serve). Platform TLS +
 *    tailnet device identity are the trust boundary; an extra-label/lookalike host, alternate port, or
 *    `http://` variant does not qualify. (A future pin store can tighten this to one provisioned hostname.)
 *  - [PUBLIC_HTTPS_UNVERIFIED]: syntactically fine public HTTPS. NOT trusted: it is never probed, never
 *    assist-eligible, and never receives RPC credentials. Note this includes `https://100.64.x.x` — RFC 6598
 *    shared carrier-NAT space is NOT proof that a private tunnel owns the route.
 *  - [ONION_DIRECT]: any `.onion` host. Invalid for this client — attempting it directly would leak the
 *    onion name to DNS/the local network instead of resolving through Tor.
 *  - [INVALID]: blank, unparseable, cleartext HTTP to a public host, or anything else.
 */
enum class DogecoinRpcEndpointClass {
    LOCAL_PRIVATE,
    TAILSCALE_HTTPS,
    PUBLIC_HTTPS_UNVERIFIED,
    ONION_DIRECT,
    INVALID;

    /**
     * May the app perform node RPC I/O (reads, probes, broadcast) against this endpoint at all?
     * Untrusted classes fail closed everywhere — including explicit user actions — so a UI-only check
     * can never be bypassed by a path that constructs its own client.
     */
    val isTrustedRpcRoute: Boolean
        get() = this == LOCAL_PRIVATE || this == TAILSCALE_HTTPS
}

/** Classify a configured Dogecoin node RPC URL. Pure; safe to call from composition. */
fun classifyDogecoinRpcEndpoint(url: String): DogecoinRpcEndpointClass {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) return DogecoinRpcEndpointClass.INVALID
    val parsed = trimmed.toHttpUrlOrNull() ?: return DogecoinRpcEndpointClass.INVALID
    val parsedHost = parsed.host.trim().lowercase()
    val host = parsedHost.trimEnd('.')

    if (host.endsWith(".onion")) return DogecoinRpcEndpointClass.ONION_DIRECT
    if (isPrivateOrLocalDogecoinRpcHost(host)) return DogecoinRpcEndpointClass.LOCAL_PRIVATE
    if (parsed.isHttps) {
        return if (isExactTailscaleServeOrigin(parsed, parsedHost, trimmed)) {
            DogecoinRpcEndpointClass.TAILSCALE_HTTPS
        } else {
            DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED
        }
    }
    // Cleartext HTTP to a public host: never a usable RPC endpoint.
    return DogecoinRpcEndpointClass.INVALID
}

/**
 * Home-node assist (spec R-C3) eligibility: only a trusted endpoint, and never on mainnet — mainnet node
 * reads stay behind the explicit pin flow / the future TRUSTED_PERSONAL_NODE decision.
 */
fun dogecoinNodeAssistEligible(network: DogecoinNetwork, endpointClass: DogecoinRpcEndpointClass): Boolean =
    network != DogecoinNetwork.MAINNET && endpointClass.isTrustedRpcRoute

/**
 * Fail-closed route-policy chokepoint: called by [DogecoinRpcClient] before ANY request is built, so
 * helper, console, and UI paths all inherit it and direct client construction cannot bypass it.
 */
internal fun requireTrustedDogecoinRpcRoute(config: DogecoinRpcConfig) {
    when (classifyDogecoinRpcEndpoint(config.url)) {
        DogecoinRpcEndpointClass.LOCAL_PRIVATE,
        DogecoinRpcEndpointClass.TAILSCALE_HTTPS -> Unit
        DogecoinRpcEndpointClass.ONION_DIRECT -> throw IllegalStateException(
            "Direct .onion node addresses are not supported: contacting one outside Tor would leak the " +
                "onion name. Publish the node privately instead (e.g. Tailscale Serve)."
        )
        DogecoinRpcEndpointClass.PUBLIC_HTTPS_UNVERIFIED -> throw IllegalStateException(
            "This node address is an unverified public HTTPS endpoint; bitchat will not send RPC " +
                "credentials to it. Use your own node on a private network, or its exact Tailscale " +
                "address (https://<machine>.<tailnet>.ts.net)."
        )
        DogecoinRpcEndpointClass.INVALID -> throw IllegalStateException(
            "This is not a usable Dogecoin node RPC address. Use http(s) to a local/private host or an " +
                "exact Tailscale HTTPS address."
        )
    }
}

/**
 * Exact Tailscale Serve origin shape: HTTPS, exactly `<machine>.<tailnet>.ts.net` (four labels), default
 * port 443, and no userinfo, extra path, query, or fragment. Anything looser (alternate port, extra-label
 * name, redirect target with a path) must not be treated as a provisioned host.
 */
private fun isExactTailscaleServeOrigin(parsed: HttpUrl, host: String, rawUrl: String): Boolean {
    if (!parsed.isHttps) return false
    // DNS may treat a trailing dot as equivalent, but it is not the exact provisioned HTTPS origin.
    if (host.endsWith('.')) return false
    if (host == "ts.net" || !host.endsWith(".ts.net")) return false
    val labels = host.split(".")
    if (labels.size != 4 || labels.any { it.isEmpty() }) return false
    if (parsed.port != 443) return false
    if (parsed.encodedUsername.isNotEmpty() || parsed.encodedPassword.isNotEmpty()) return false
    // HttpUrl normalizes an explicitly empty userinfo marker (`https://@host`) away. Inspect the
    // original authority too so the literal no-userinfo contract stays exact.
    if (rawUrlHasUserInfo(rawUrl)) return false
    if (parsed.query != null || parsed.fragment != null) return false
    // toHttpUrlOrNull normalizes "https://host" to a single empty path segment.
    return parsed.pathSegments.isEmpty() || parsed.pathSegments == listOf("")
}

private fun rawUrlHasUserInfo(url: String): Boolean {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return false
    val authorityStart = schemeEnd + 3
    val authorityEnd = url.indexOfAny(charArrayOf('/', '\\', '?', '#'), startIndex = authorityStart)
        .let { if (it < 0) url.length else it }
    return url.substring(authorityStart, authorityEnd).contains('@')
}

/**
 * True for hosts that can only be (or are overwhelmingly likely to be) on the device's own network:
 * loopback, RFC1918, IPv4 link-local, IPv6 loopback/ULA/link-local, `localhost`, mDNS `.local`, and
 * single-label names. RFC 6598 (100.64.0.0/10) is deliberately NOT here: shared carrier-NAT space is
 * not proof of a private route.
 */
internal fun isPrivateOrLocalDogecoinRpcHost(host: String): Boolean {
    val normalizedHost = host.trim().lowercase().trimEnd('.')
    if (normalizedHost.isEmpty()) return false
    if (normalizedHost == "localhost" || normalizedHost.endsWith(".local")) return true
    if (!normalizedHost.contains(".") && !normalizedHost.contains(":")) {
        // Platform DNS accepts one-part numeric IPv4 forms. For example, 134744072 resolves to the
        // public address 8.8.8.8, so treating every dotless token as LAN-local can leak Basic auth.
        if (normalizedHost.all { it in '0'..'9' }) return false
        if (
            normalizedHost.startsWith("0x") &&
            normalizedHost.drop(2).isNotEmpty() &&
            normalizedHost.drop(2).all { it in '0'..'9' || it in 'a'..'f' }
        ) return false
        return true
    }
    return isPrivateDogecoinIpv4Host(normalizedHost) || isPrivateDogecoinIpv6Host(normalizedHost)
}

private fun isPrivateDogecoinIpv4Host(host: String): Boolean {
    val parts = host.split(".")
    if (
        parts.size != 4 ||
        parts.any { part ->
            part.isEmpty() ||
                part.any { it !in '0'..'9' } ||
                (part.length > 1 && part.startsWith('0'))
        }
    ) return false
    val octets = parts.map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return when {
        octets[0] == 10 -> true
        octets[0] == 127 -> true
        octets[0] == 169 && octets[1] == 254 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        else -> false
    }
}

private fun isPrivateDogecoinIpv6Host(host: String): Boolean {
    val normalizedHost = host.removeSurrounding("[", "]")
    if (normalizedHost == "::1") return true
    val firstHextet = normalizedHost.substringBefore(':').toIntOrNull(16) ?: return false
    val isUniqueLocal = (firstHextet and 0xfe00) == 0xfc00 // fc00::/7
    val isLinkLocal = (firstHextet and 0xffc0) == 0xfe80 // fe80::/10
    return isUniqueLocal || isLinkLocal
}
