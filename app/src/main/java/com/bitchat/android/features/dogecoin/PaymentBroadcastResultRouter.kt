package com.bitchat.android.features.dogecoin

/**
 * Process-wide sink that delivers an inbound PAYMENT_BROADCAST_RESULT (0x31) received over the Nostr
 * fallback transport (Milestone 3b.1) to the active sender-side [PaymentBroadcastCoordinator].
 *
 * Why a bridge at all: the mesh transport reaches the coordinator directly through the ChatViewModel
 * MeshDelegate (`didReceivePaymentBroadcastResult`), but the Nostr direct-message handler is built in a
 * different scope ([com.bitchat.android.ui.GeohashViewModel]) and holds no reference to the per-ViewModel
 * coordinator. ChatViewModel registers its coordinator's `onResult` here while it is alive; the Nostr
 * handler calls [deliver].
 *
 * Safety/liveness notes:
 *  - A dropped result (no sink registered, or no broadcast in flight) is harmless: the coordinator's
 *    results flow has replay 0 and ignores a reply that matches no outstanding request UUID.
 *  - The coordinator dedups corroboration by the source id passed to `onResult`. Both receive paths feed
 *    it the helper's canonical Noise-key hex (the Nostr handler resolves the gift-wrap pubkey back to the
 *    favorite's Noise key and DROPS anything that doesn't resolve to a known favorite; the mesh path
 *    resolves the peerID to its Noise key). So a single physical helper maps to one identity regardless of
 *    transport and cannot be counted twice toward a `Confirmed` outcome — a free-to-mint Nostr keypair
 *    never resolves to a favorite, so it can't manufacture corroboration.
 */
object PaymentBroadcastResultRouter {

    @Volatile
    private var sink: ((fromId: String, resultPayload: ByteArray) -> Unit)? = null

    /** Register (or clear, with null) the active coordinator's result handler. Last writer wins. */
    fun setSink(sink: ((fromId: String, resultPayload: ByteArray) -> Unit)?) {
        this.sink = sink
    }

    /** Clear the sink only if [sink] is still the registered one, so a newer registrant is not clobbered. */
    fun clearSinkIfCurrent(sink: ((fromId: String, resultPayload: ByteArray) -> Unit)) {
        if (this.sink === sink) this.sink = null
    }

    /** Route a result payload to the active sink. Returns true if a sink consumed it. */
    fun deliver(fromId: String, resultPayload: ByteArray): Boolean {
        val current = sink ?: return false
        return try {
            current(fromId, resultPayload)
            true
        } catch (_: Exception) {
            false
        }
    }
}
