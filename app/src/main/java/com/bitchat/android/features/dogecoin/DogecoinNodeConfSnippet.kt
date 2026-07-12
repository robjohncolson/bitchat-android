package com.bitchat.android.features.dogecoin

/**
 * The one-tap `dogecoin.conf` help snippet shown in the wallet's node settings.
 *
 * SECURITY: this must never suggest exposing RPC beyond the node machine. RPC binds loopback only —
 * no `rpcbind=0.0.0.0`, no RFC1918 `rpcallowip` ranges. Reaching loopback RPC from the phone is done
 * with a private tunnel (Tailscale Serve) or, for experts on testnet/regtest, a manually bound and
 * firewalled exact LAN interface — both described as comments inside the snippet, not as defaults.
 */
internal fun dogecoinConfSnippet(
    network: DogecoinNetwork,
    username: String,
    password: String
): String {
    val rpcUser = dogecoinConfValue(username, "bitchat")
    val rpcPassword = dogecoinConfValue(password, "choose-a-long-password")
    val networkLine = when (network) {
        DogecoinNetwork.MAINNET -> null
        DogecoinNetwork.TESTNET -> "testnet=1"
        DogecoinNetwork.REGTEST -> "regtest=1"
    }
    return buildList {
        networkLine?.let { add(it) }
        add("server=1")
        add("rpcuser=$rpcUser")
        add("rpcpassword=$rpcPassword")
        add("# RPC answers only on this machine. Do NOT open it to your network or the internet.")
        add("rpcbind=127.0.0.1")
        add("rpcallowip=127.0.0.1")
        add("rpcport=${network.rpcPort}")
        add("# To reach it from your phone, publish it privately on your tailnet:")
        add("#   tailscale serve --bg http://127.0.0.1:${network.rpcPort}")
        add("# then use the exact https://<machine>.<tailnet>.ts.net address in the app.")
        if (network != DogecoinNetwork.MAINNET) {
            add("# Expert alternative on a trusted LAN (${network.displayName} only): bind the node's exact")
            add("# LAN address with rpcbind/rpcallowip and firewall TCP ${network.rpcPort} yourself.")
        }
    }.joinToString("\n")
}

private fun dogecoinConfValue(value: String, fallback: String): String {
    return value.trim().replace(Regex("\\s+"), "-").ifEmpty { fallback }
}
