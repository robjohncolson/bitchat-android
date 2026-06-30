package com.bitchat.android.features.dogecoin

import org.bitcoinj.net.BlockingClientManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

/**
 * Pins the SPV-over-Tor TRANSPORT decision and the SOCKS socket factory — the two pieces that, if they
 * silently regress, would either leak the user's IP to Dogecoin peers (clearnet while Tor is on) or break
 * every Tor connection. Pure-JVM: no PeerGroup start, no network, no Android.
 *
 * Money/privacy-safety note: these only exercise transport selection. bitcoinj never signs (Option B) and
 * the broadcast verifier / mainnet gates are covered elsewhere; nothing here can build or broadcast a tx.
 */
class DogecoinSpvTorTransportTest {

    /** No-silent-fallback: a null Arti endpoint (Tor OFF, or not configured) must yield NO SOCKS manager, so
     *  the service uses the default clearnet NioClientManager. */
    @Test
    fun `torConnectionManager is null when there is no socks endpoint`() {
        assertNull(DogecoinSpvService.torConnectionManager(null))
    }

    /** No-silent-fallback: the instant a SOCKS endpoint is present (set when Tor mode flips ON, before
     *  bootstrap completes), the transport MUST be a SOCKS-routing BlockingClientManager — never clearnet. */
    @Test
    fun `torConnectionManager routes over SOCKS when an endpoint is present`() {
        val mgr: BlockingClientManager? =
            DogecoinSpvService.torConnectionManager(InetSocketAddress("127.0.0.1", 9060))
        assertNotNull("Tor ON must produce a SOCKS BlockingClientManager, never a clearnet fallback", mgr)
    }

    /** The load-bearing override: javax.net.SocketFactory's no-arg createSocket() throws by default, and it is
     *  the ONLY method bitcoinj's BlockingClient calls. It must return a real, UNCONNECTED socket (bitcoinj
     *  connects it itself through the SOCKS proxy with the resolved peer address). If this regresses, every
     *  Tor peer connection fails at creation. */
    @Test
    fun `socks factory no-arg createSocket returns an unconnected socket without throwing`() {
        val factory = DogecoinSpvService.SocksProxySocketFactory("127.0.0.1", 9050)
        val socket = factory.createSocket()
        try {
            assertNotNull(socket)
            assertFalse("the socket must be unconnected; BlockingClient connects it itself", socket.isConnected)
        } finally {
            socket.close()
        }
    }

    /** The status surfaces the ACTUAL transport (so the UI/console can be honest), defaulting to clearnet. */
    @Test
    fun `status reports the actual transport via overTor`() {
        assertFalse(DogecoinSpvStatus(network = DogecoinNetwork.TESTNET).overTor)
        assertFalse(DogecoinSpvStatus(network = DogecoinNetwork.TESTNET, overTor = false).overTor)
        assertTrue(DogecoinSpvStatus(network = DogecoinNetwork.TESTNET, overTor = true).overTor)
    }
}
