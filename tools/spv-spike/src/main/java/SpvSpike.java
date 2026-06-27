import org.bitcoinj.core.AbstractBlockChain;
import org.bitcoinj.core.BlockChain;
import org.bitcoinj.core.Context;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerGroup;
import org.bitcoinj.core.VersionMessage;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.store.SPVBlockStore;
import org.libdohj.params.DogecoinTestNet3Params;

import java.io.File;
import java.util.Date;
import java.util.List;

/**
 * Feasibility spike: prove bitcoinj + libdohj can header-sync the Dogecoin TESTNET chain.
 *
 * What this decisively tests (no Android, no local node required):
 *   1. libdohj v0.15.9 actually RESOLVES (JitPack) and COMPILES against bitcoinj 0.15.9 on JDK 17.
 *   2. DogecoinTestNet3Params makes bitcoinj validate Scrypt PoW / AuxPoW / DigiShield headers.
 *   3. Public testnet peers (testseed.jrn.me.uk) connect and feed us headers.
 *   4. The chain advances through retarget eras with NO VerificationException
 *      (this is the libdohj issue #15 "stricter-than-consensus AuxPoW" correctness gate).
 *
 * It is HEADER-ONLY (no wallet / no bloom): balance-match against an address is a separate
 * spike that wants the local node as a fast oracle.
 */
public class SpvSpike {

    public static void main(String[] args) {
        int minutes = Integer.getInteger("spike.minutes", 8);
        try {
            String verifyCp = System.getProperty("spike.verifyCheckpoint");
            if (verifyCp != null) { verifyCheckpoint(verifyCp); System.exit(0); }
            run(minutes);
            System.out.println("[spike] RESULT: clean exit, no exception propagated to main.");
            System.exit(0);
        } catch (Throwable t) {
            System.out.println("[spike] RESULT: FAILED with " + t.getClass().getName() + ": " + t.getMessage());
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    /** Load a generated checkpoint file the way DogecoinSpvService does, and print the seeded store head
     *  (compare its height + hash to the node's getblockhash to confirm the checkpoint is correct). */
    private static void verifyCheckpoint(String path) throws Exception {
        final NetworkParameters params = DogecoinTestNet3Params.get();
        final Context ctx = new Context(params);
        Context.propagate(ctx);
        File tmp = File.createTempFile("cp-verify", ".chain");
        tmp.delete();
        SPVBlockStore store = new SPVBlockStore(params, tmp);
        long nowSecs = System.currentTimeMillis() / 1000L;
        try (java.io.InputStream in = new java.io.FileInputStream(path)) {
            org.bitcoinj.core.CheckpointManager.checkpoint(params, in, store, nowSecs);
        }
        org.bitcoinj.core.StoredBlock head = store.getChainHead();
        System.out.println("[verify] checkpoint -> SPVBlockStore head: height=" + head.getHeight()
                + " time=" + new Date(head.getHeader().getTimeSeconds() * 1000L)
                + " hash=" + head.getHeader().getHashAsString());
        // store.close() hits WindowsMMapHack (sun.nio.ch) on JDK17/Windows — Android never runs it; swallow.
        try { store.close(); } catch (Throwable ignored) {}
        System.out.println("[verify] OK: checkpoint loads + seeds the store (compare height/hash to the node).");
    }

    private static void run(int minutes) throws Exception {
        final NetworkParameters params = DogecoinTestNet3Params.get();
        final Context context = new Context(params);
        System.out.println("[spike] params id=" + params.getId()
                + " p2pPort=" + params.getPort()
                + " packetMagic=0x" + Long.toHexString(params.getPacketMagic()));

        final File chainFile = new File("spv-spike-testnet.chain");
        if (chainFile.exists() && !chainFile.delete()) {
            System.out.println("[spike] WARN could not delete old store " + chainFile);
        }
        final SPVBlockStore store = new SPVBlockStore(params, chainFile);
        final BlockChain chain = new BlockChain(context, store);
        final PeerGroup peerGroup = new HighestHeightDownloadPeerGroup(context, chain);
        peerGroup.setUserAgent("bitchat-spv-spike", "0.1");
        peerGroup.setBloomFilteringEnabled(false); // header-only correctness test

        // -Dspike.noLocalhost=1 forces the public-network path (bitcoinj otherwise auto-prefers a
        // detected 127.0.0.1 node), so we can validate the true no-node goal vs the public DNS seed.
        if (System.getProperty("spike.noLocalhost") != null) {
            peerGroup.setUseLocalhostPeerWhenPossible(false);
            System.out.println("[spike] localhost-peer preference DISABLED (public network only)");
        }

        String peerHost = System.getProperty("spike.peerHost");
        if (peerHost != null && !peerHost.isEmpty()) {
            // Isolation mode: talk ONLY to a controlled node (e.g. the local testnet node) so the
            // test measures "does bitcoinj+libdohj work", not "does the public net cooperate".
            java.net.InetAddress addr = java.net.InetAddress.getByName(peerHost);
            peerGroup.setMaxConnections(1);
            peerGroup.addAddress(new org.bitcoinj.core.PeerAddress(params, addr, params.getPort()));
            System.out.println("[spike] FIXED-PEER mode -> " + peerHost + ":" + params.getPort() + " (no DNS discovery)");
        } else {
            peerGroup.setMaxConnections(6);
            peerGroup.addPeerDiscovery(new DnsDiscovery(params));
            System.out.println("[spike] PUBLIC mode -> DNS discovery via " + params.getDnsSeeds().length + " seed(s)");
        }

        final long startHeight = chain.getBestChainHeight();
        System.out.println("[spike] store start height=" + startHeight);

        peerGroup.startAsync();
        System.out.println("[spike] discovery started; waiting for >=1 peer ...");
        List<Peer> peers = peerGroup.waitForPeers(1).get();
        System.out.println("[spike] connected peers=" + peerGroup.numConnectedPeers());
        for (Peer p : peerGroup.getConnectedPeers()) {
            VersionMessage vm = p.getPeerVersionMessage();
            long NODE_BLOOM = 1L << 2; // BIP111 service bit (not a named constant in bitcoinj 0.14.7)
            boolean nodeBloom = (vm.localServices & NODE_BLOOM) == NODE_BLOOM;
            System.out.println("    peer " + p.getAddress()
                    + " proto=" + vm.clientVersion
                    + " subver=" + vm.subVer
                    + " services=0x" + Long.toHexString(vm.localServices)
                    + " NODE_BLOOM=" + nodeBloom
                    + " bestHeight=" + vm.bestHeight);
        }

        final long deadlineMs = System.currentTimeMillis() + minutes * 60_000L;

        DownloadProgressTracker tracker = new DownloadProgressTracker() {
            private int lastLogged = (int) startHeight;
            @Override
            protected void progress(double pct, int blocksSoFar, Date date) {
                int h = chain.getBestChainHeight();
                if (h - lastLogged >= 10_000) {
                    lastLogged = h;
                    System.out.println(String.format("[spike] height=%d  remaining=%d  blockDate=%s",
                            h, blocksSoFar, date));
                }
            }
            @Override
            protected void doneDownload() {
                System.out.println("[spike] *** doneDownload: fully synced to tip, height=" + chain.getBestChainHeight());
            }
        };
        peerGroup.startBlockChainDownload(tracker);

        // Heartbeat so liveness/stall is visible even if no milestone fires.
        long lastHeartbeatHeight = startHeight;
        long lastProgressMs = System.currentTimeMillis();
        while (System.currentTimeMillis() < deadlineMs) {
            Thread.sleep(15_000);
            int h = chain.getBestChainHeight();
            int peerCount = peerGroup.numConnectedPeers();
            long bestPeerHeight = 0;
            for (Peer p : peerGroup.getConnectedPeers()) bestPeerHeight = Math.max(bestPeerHeight, p.getBestHeight());
            boolean advanced = h > lastHeartbeatHeight;
            if (advanced) lastProgressMs = System.currentTimeMillis();
            long stalledSec = (System.currentTimeMillis() - lastProgressMs) / 1000;
            System.out.println(String.format("[hb] height=%d  peers=%d  bestPeerHeight=%d  %s%s",
                    h, peerCount, bestPeerHeight,
                    advanced ? "(+" + (h - lastHeartbeatHeight) + ")" : "(no advance)",
                    stalledSec > 30 ? "  STALLED " + stalledSec + "s" : ""));
            lastHeartbeatHeight = h;
            // If we're fully synced (caught the tip), stop early.
            if (bestPeerHeight > 0 && h >= bestPeerHeight) {
                System.out.println("[spike] reached peer best height; tip caught.");
                break;
            }
        }

        long finalHeight = chain.getBestChainHeight();
        System.out.println("[spike] ===== SUMMARY =====");
        System.out.println("[spike] start height   : " + startHeight);
        System.out.println("[spike] final height   : " + finalHeight);
        System.out.println("[spike] headers synced : " + (finalHeight - startHeight) + " in <=" + minutes + " min");
        System.out.println("[spike] connected peers: " + peerGroup.numConnectedPeers());
        // Stop peers FIRST, then close the store, so peer threads don't touch a closed store on teardown.
        peerGroup.stopAsync();
        try { Thread.sleep(1500); } catch (InterruptedException ignored) {}
        try { store.close(); } catch (Exception ignored) {}
    }

    /**
     * bitcoinj's default download-peer selection hard-prefers a NODE_WITNESS peer, which Dogecoin
     * nodes (no segwit) do not advertise — so the default can refuse to pick a download peer and
     * the chain never advances. The langerhans wallet works around this with NonWitnessPeerGroup;
     * we do the minimal public-API version: pick the highest-height peer that has a chain.
     */
    static final class HighestHeightDownloadPeerGroup extends PeerGroup {
        HighestHeightDownloadPeerGroup(Context context, AbstractBlockChain chain) {
            super(context, chain);
        }

        @Override
        protected Peer selectDownloadPeer(List<Peer> peers) {
            Peer best = null;
            long bestHeight = -1;
            for (Peer peer : peers) {
                VersionMessage vm = peer.getPeerVersionMessage();
                if (vm == null || !vm.hasBlockChain()) continue;
                long h = peer.getBestHeight();
                if (h > bestHeight) {
                    bestHeight = h;
                    best = peer;
                }
            }
            return best;
        }
    }
}
