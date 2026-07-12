package com.bitchat.android.profile

import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.nostr.Bech32
import com.bitchat.android.nostr.KnownNpubStore
import java.text.Normalizer

/**
 * Display-time contact name resolution for Simple/family surfaces (spec Workstream A).
 *
 * Pure-read: never mutates stored [BitchatMessage.sender]. Priority:
 * 1) local relationship label (favorite pet-name / KnownNpub label)
 * 2) sanitized remote/message fallback (if not a banned token)
 * 3) localized family fallback + short hex id (collision-breaker, not a verifier)
 *
 * See docs/family-wallet-ux-learnings-spec.md §3.
 */
object ContactDisplayName {

    const val MAX_PET_NAME_LEN = 24

    /** Identity material for one contact thread — any subset may be present. */
    data class Identity(
        val convKey: String? = null,
        val noiseKeyHex: String? = null,
        val meshPeerId: String? = null,
        val nostrPubkeyHex: String? = null,
        val npub: String? = null
    )

    data class ResolvedName(
        /** String to show a human. Never the bare token "anon". */
        val display: String,
        /** True when display came from a local favorite / KnownNpub label. */
        val isLocalLabel: Boolean,
        /** True when display is a remote-asserted name (priority 2), possibly spoofable. */
        val isRemoteAsserted: Boolean = false,
        /** True when remote name collides with a local pet-name after NFKC/case-fold. */
        val isUnverifiedCollision: Boolean = false,
        /** Short id suffix material (first 4 hex), if any. */
        val shortId: String? = null
    )

    // --- sanitizers (pure) ----------------------------------------------------

    private val BANNED_BASE = setOf("anon", "unknown")

    /** True for anon / unknown / anon#xxxx (case-insensitive). */
    fun isBannedToken(raw: String?): Boolean {
        val s = raw?.trim() ?: return true
        if (s.isEmpty()) return true
        val base = s.substringBefore('#').trim().lowercase()
        return base in BANNED_BASE
    }

    /**
     * Strip control/format chars (incl. bidi overrides U+202A–E), trim, cap length.
     * Returns null if empty after sanitize or if the result is a banned token.
     */
    fun sanitizeRemote(raw: String?): String? {
        if (raw == null) return null
        val stripped = buildString(raw.length) {
            for (ch in raw) {
                val type = Character.getType(ch).toByte()
                if (type == Character.CONTROL || type == Character.FORMAT) continue
                // Explicit bidi + zero-width joiners often used in spoofing
                if (ch in '\u200B'..'\u200F' || ch in '\u202A'..'\u202E' || ch == '\uFEFF') continue
                append(ch)
            }
        }.trim()
        if (stripped.isEmpty() || isBannedToken(stripped)) return null
        return stripped.take(MAX_PET_NAME_LEN * 2) // remote can be slightly longer before display clamp
    }

    /**
     * Validate a user-typed pet-name for rename UI. Returns sanitized name or null if invalid.
     */
    fun sanitizePetNameInput(raw: String): String? {
        val stripped = buildString(raw.length) {
            for (ch in raw) {
                val type = Character.getType(ch).toByte()
                if (type == Character.CONTROL || type == Character.FORMAT) continue
                if (ch in '\u200B'..'\u200F' || ch in '\u202A'..'\u202E' || ch == '\uFEFF') continue
                append(ch)
            }
        }.trim()
        if (stripped.isEmpty()) return null
        if (isBannedToken(stripped)) return null
        return stripped.take(MAX_PET_NAME_LEN)
    }

    private fun nfkcFold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()

    // --- core resolve (pure, injectable sources) ------------------------------

    /**
     * @param familyFallback localized string e.g. R.string.simple_family_fallback
     * @param localLabel from favorites/KnownNpub (already non-blank preferred)
     * @param allLocalLabels every local pet-name for collision detection
     * @param shortIdHex first 4 hex of identity for fallback (from pubkey or convKey)
     */
    fun resolve(
        localLabel: String?,
        messageSenderFallback: String?,
        allLocalLabels: Set<String>,
        familyFallback: String,
        shortIdHex: String?
    ): ResolvedName {
        val local = localLabel?.trim()?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
        if (local != null) {
            return ResolvedName(display = local, isLocalLabel = true, shortId = shortIdHex)
        }

        val remote = sanitizeRemote(messageSenderFallback)
        if (remote != null) {
            val collides = allLocalLabels.any { nfkcFold(it) == nfkcFold(remote) }
            val display = if (collides && shortIdHex != null) {
                "$remote · $shortIdHex"
            } else remote
            return ResolvedName(
                display = display,
                isLocalLabel = false,
                isRemoteAsserted = true,
                isUnverifiedCollision = collides,
                shortId = shortIdHex
            )
        }

        val short = shortIdHex?.take(4)?.lowercase()?.takeIf { it.length == 4 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
        val display = if (short != null) "$familyFallback · $short" else familyFallback
        return ResolvedName(display = display, isLocalLabel = false, shortId = short)
    }

    /** Derive short-id hex from available identity (prefer full pubkey, else convKey prefix). */
    fun shortIdFromIdentity(identity: Identity): String? {
        identity.nostrPubkeyHex?.lowercase()?.takeIf { it.length >= 4 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
            ?.take(4)?.let { return it }
        identity.noiseKeyHex?.lowercase()?.takeIf { it.length >= 4 }?.take(4)?.let { return it }
        // convKey "nostr_<16hex>" embeds the first-16-hex prefix of the pubkey
        identity.convKey?.lowercase()?.let { key ->
            if (key.startsWith("nostr_") && !key.startsWith("nostr_grp_")) {
                val p = key.removePrefix("nostr_")
                if (p.length >= 4 && p.take(4).all { c -> c in '0'..'9' || c in 'a'..'f' }) return p.take(4)
            }
        }
        identity.meshPeerId?.lowercase()?.takeIf { it.length >= 4 }?.take(4)?.let { return it }
        return null
    }

    /**
     * Look up a local label from pure maps (unit-testable).
     * Deterministic: when multiple favorites share a Nostr pubkey, pick lowest Noise key hex.
     */
    fun lookupLocalLabel(
        identity: Identity,
        favoritesByNoiseHex: Map<String, FavoriteRelationship>,
        knownNpubs: Map<String, String>
    ): String? {
        // 1) Direct Noise key
        identity.noiseKeyHex?.lowercase()?.let { nh ->
            favoritesByNoiseHex[nh]?.peerNickname?.trim()?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
                ?.let { return it }
        }
        // 2) Mesh peerID / fingerprint (NOT noise-key prefix)
        identity.meshPeerId?.let { pid ->
            FavoritesPersistenceService.matchFavoriteByPeerID(favoritesByNoiseHex, pid)
                ?.peerNickname?.trim()?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
                ?.let { return it }
        }
        // Full noise hex via matchFavoriteByPeerID also works for 64-hex
        identity.noiseKeyHex?.let { nh ->
            FavoritesPersistenceService.matchFavoriteByPeerID(favoritesByNoiseHex, nh)
                ?.peerNickname?.trim()?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
                ?.let { return it }
        }
        // 3) Nostr pubkey / npub / convKey-derived prefix
        val nostrHex = resolveNostrHex(identity)
        if (nostrHex != null) {
            // Deterministic among favorites with this npub: lowest noise key hex
            val matches = favoritesByNoiseHex.entries
                .filter { (_, rel) ->
                    rel.peerNostrPublicKey?.let { stored ->
                        normalizeNostrToHex(stored) == nostrHex
                    } == true
                }
                .sortedBy { it.key }
            matches.firstOrNull()?.value?.peerNickname?.trim()
                ?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
                ?.let { return it }

            knownNpubs[nostrHex]?.trim()?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
                ?.let { return it }
        }
        return null
    }

    private fun resolveNostrHex(identity: Identity): String? {
        identity.nostrPubkeyHex?.lowercase()?.takeIf { it.length == 64 }?.let { return it }
        identity.npub?.let { normalizeNostrToHex(it) }?.let { return it }
        identity.convKey?.lowercase()?.let { key ->
            if (key.startsWith("nostr_") && !key.startsWith("nostr_grp_")) {
                // Only 16 hex prefix available — match favorites by prefix on stored nostr hex
                return null // handled via known map full hex; prefix match done in live lookup
            }
        }
        return null
    }

    private fun normalizeNostrToHex(nostrPubkey: String): String? {
        return try {
            if (nostrPubkey.startsWith("npub1", ignoreCase = true)) {
                val (_, data) = Bech32.decode(nostrPubkey)
                data.joinToString("") { "%02x".format(it) }.lowercase()
            } else {
                val hex = nostrPubkey.lowercase()
                if (hex.length == 64 && hex.all { it in '0'..'9' || it in 'a'..'f' }) hex else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // --- live Android sources -------------------------------------------------

    /**
     * Resolve using live [FavoritesPersistenceService] + [KnownNpubStore].
     * Safe to call when favorites are not initialized (returns fallback path).
     */
    fun resolveLive(
        identity: Identity,
        messageSenderFallback: String?,
        familyFallback: String
    ): ResolvedName {
        val favorites = runCatching {
            FavoritesPersistenceService.shared.debugAllRelationships()
                .associateBy { it.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) } }
        }.getOrDefault(emptyMap())

        val known = runCatching { KnownNpubStore.snapshot() }.getOrDefault(emptyMap())

        // Prefix match for convKey when only 16 hex of nostr pubkey is known
        val enrichedLocal = lookupLocalLabel(identity, favorites, known)
            ?: lookupByConvKeyPrefix(identity.convKey, favorites, known)

        val allLocal = favorites.values.mapNotNull {
            it.peerNickname.trim().takeIf { n -> n.isNotEmpty() && !isBannedToken(n) }
        }.toSet() + known.values.mapNotNull {
            it.trim().takeIf { n -> n.isNotEmpty() && !isBannedToken(n) }
        }.toSet()

        return resolve(
            localLabel = enrichedLocal,
            messageSenderFallback = messageSenderFallback,
            allLocalLabels = allLocal,
            familyFallback = familyFallback,
            shortIdHex = shortIdFromIdentity(identity)
                ?: identity.convKey?.removePrefix("nostr_")?.take(4)
        )
    }

    private fun lookupByConvKeyPrefix(
        convKey: String?,
        favorites: Map<String, FavoriteRelationship>,
        known: Map<String, String>
    ): String? {
        val key = convKey?.lowercase() ?: return null
        if (!key.startsWith("nostr_") || key.startsWith("nostr_grp_")) return null
        val prefix = key.removePrefix("nostr_")
        if (prefix.length != 16) return null

        val matches = favorites.entries
            .filter { (_, rel) ->
                rel.peerNostrPublicKey?.let { normalizeNostrToHex(it)?.startsWith(prefix) } == true
            }
            .sortedBy { it.key }
        matches.firstOrNull()?.value?.peerNickname?.trim()
            ?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
            ?.let { return it }

        known.entries
            .filter { it.key.startsWith(prefix) }
            .sortedBy { it.key }
            .firstOrNull()?.value?.trim()
            ?.takeIf { it.isNotEmpty() && !isBannedToken(it) }
            ?.let { return it }

        return null
    }
}
