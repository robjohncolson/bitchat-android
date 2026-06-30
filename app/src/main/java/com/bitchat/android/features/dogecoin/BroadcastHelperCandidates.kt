package com.bitchat.android.features.dogecoin

/**
 * Merge ranked mesh broadcast-helper candidates with off-mesh Nostr-reachable favorites (Milestone 3b.1).
 *
 * Every candidate — mesh or Nostr — is identified by the helper's stable 64-hex Noise static key. This is
 * the canonical per-helper identity that the sender coordinator uses to dispatch, to track replies, and
 * (critically) to COUNT distinct corroborations toward Outcome.Confirmed. Using the Noise key (not a
 * transport address or a free-to-mint Nostr pubkey) is what makes the two-helper corroboration safe: one
 * physical helper has exactly one Noise key, so it collapses to one entry no matter how many transports it
 * answers on or how many throwaway Nostr identities it mints. The dispatch layer maps a Noise key to a
 * concrete transport (a connected mesh peer that holds the key, else Nostr addressed by the key).
 *
 * @param meshNoiseKeysOrdered Noise-key hex of connected, session-established helper candidates, already
 *   ranked (advertised helpers first, then mutual favorites). Order is preserved and these rank ahead of
 *   every off-mesh candidate (mesh is the fast path).
 * @param offMeshFavoriteNoiseHex Noise-key hex of mutual favorites with a stored Nostr key (off-mesh).
 *
 * A Noise key already present in the mesh tier is dropped from the off-mesh tier (a helper reachable on
 * mesh is dispatched there, not also over Nostr). Keys are lowercased; blanks dropped; each tier de-duped.
 */
internal fun mergeBroadcastHelperCandidates(
    meshNoiseKeysOrdered: List<String>,
    offMeshFavoriteNoiseHex: List<String>
): List<String> {
    val mesh = meshNoiseKeysOrdered
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() }
        .distinct()
    val meshSet = mesh.toSet()
    val offMesh = offMeshFavoriteNoiseHex
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() && it !in meshSet }
        .distinct()
    return mesh + offMesh
}
