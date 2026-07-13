package com.bitchat.android.features.dogecoin

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/**
 * Shareable connection coordinates only. This is deliberately not a trust record: it contains no
 * Android address, authorization/revision, rescan attestation, proof, attempt, transaction, or WIF.
 * Importing it may only populate the local provisioning draft and must always run the full ceremony.
 */
internal data class DogecoinTrustedPersonalNodeConnectionProfile(
    val origin: String,
    val username: String,
    val coreWalletId: String,
    val password: String? = null
) {
    override fun toString(): String =
        "DogecoinTrustedPersonalNodeConnectionProfile(" +
            "origin=$origin, username=$username, coreWalletId=$coreWalletId, " +
            "password=${if (password == null) "<absent>" else "<redacted>"})"
}

internal data class DogecoinTrustedPersonalNodeConnectionDraft(
    val origin: String,
    val username: String,
    val password: String,
    val coreWalletId: String
) {
    override fun toString(): String =
        "DogecoinTrustedPersonalNodeConnectionDraft(" +
            "origin=$origin, username=$username, coreWalletId=$coreWalletId, password=<redacted>)"
}

/** A password-free import deliberately clears any password left in the old local draft. */
internal fun dogecoinTrustedPersonalNodeConnectionDraftFrom(
    profile: DogecoinTrustedPersonalNodeConnectionProfile
): DogecoinTrustedPersonalNodeConnectionDraft = DogecoinTrustedPersonalNodeConnectionDraft(
    origin = profile.origin,
    username = profile.username,
    password = profile.password.orEmpty(),
    coreWalletId = profile.coreWalletId
)

internal fun encodeDogecoinTrustedPersonalNodeConnectionProfile(
    profile: DogecoinTrustedPersonalNodeConnectionProfile
): String? {
    if (!isValidDogecoinTrustedPersonalNodeConnectionProfile(profile)) return null
    return buildString {
        append(CONNECTION_PROFILE_PREFIX)
        append(CONNECTION_PROFILE_VERSION)
        append(FIELD_SEPARATOR)
        append(profile.origin.connectionProfileField())
        append(FIELD_SEPARATOR)
        append(profile.username.connectionProfileField())
        append(FIELD_SEPARATOR)
        append(profile.coreWalletId.connectionProfileField())
        profile.password?.let {
            append(FIELD_SEPARATOR)
            append(it.connectionProfileField())
        }
    }
}

/**
 * Accepts only this app's bounded canonical, unpadded base64url field encoding. Base64url is transport
 * encoding, not encryption; a password-bearing profile is a plaintext-equivalent bearer secret.
 */
internal fun decodeDogecoinTrustedPersonalNodeConnectionProfileOrNull(
    encoded: String
): DogecoinTrustedPersonalNodeConnectionProfile? {
    if (encoded.length !in 1..DOGECOIN_TPN_CONNECTION_PROFILE_MAX_CHARS) return null
    val fields = encoded.split(FIELD_SEPARATOR, limit = 6)
    if (fields.size !in 4..5) return null
    if (fields[0] != "$CONNECTION_PROFILE_PREFIX$CONNECTION_PROFILE_VERSION") return null
    val profile = DogecoinTrustedPersonalNodeConnectionProfile(
        origin = fields[1].decodeConnectionProfileFieldOrNull() ?: return null,
        username = fields[2].decodeConnectionProfileFieldOrNull() ?: return null,
        coreWalletId = fields[3].decodeConnectionProfileFieldOrNull() ?: return null,
        password = fields.getOrNull(4)?.decodeConnectionProfileFieldOrNull()
            ?: if (fields.size == 5) return null else null
    )
    if (!isValidDogecoinTrustedPersonalNodeConnectionProfile(profile)) return null
    // Reject noncanonical alternate encodings even when a platform decoder would accept them.
    if (encodeDogecoinTrustedPersonalNodeConnectionProfile(profile) != encoded) return null
    return profile
}

private fun isValidDogecoinTrustedPersonalNodeConnectionProfile(
    profile: DogecoinTrustedPersonalNodeConnectionProfile
): Boolean =
    exactDogecoinTrustedPersonalNodeOriginOrNull(profile.origin) == profile.origin &&
        isValidConnectionProfileUsername(profile.username) &&
        profile.username.isWellFormedUtf8Source() &&
        canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(profile.coreWalletId) == profile.coreWalletId &&
        profile.coreWalletId.isWellFormedUtf8Source() &&
        (profile.password == null || (
            isValidConnectionProfilePassword(profile.password) &&
                profile.password.isWellFormedUtf8Source()
            ))

private fun isValidConnectionProfileUsername(username: String): Boolean =
    username.isNotBlank() &&
        username == username.trim() &&
        username.length <= 128 &&
        ':' !in username &&
        username.none { it.isISOControl() }

private fun isValidConnectionProfilePassword(password: String): Boolean =
    password.isNotBlank() &&
        password.length <= 1024 &&
        password.none { it.isISOControl() }

private fun String.connectionProfileField(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.isWellFormedUtf8Source(): Boolean = runCatching {
    Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(this))
}.isSuccess

private fun String.decodeConnectionProfileFieldOrNull(): String? {
    if (isEmpty() || any { it !in BASE64URL_CHARS }) return null
    val bytes = runCatching { Base64.getUrlDecoder().decode(this) }.getOrNull() ?: return null
    val decoded = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull() ?: return null
    return decoded.takeIf { it.connectionProfileField() == this }
}

private const val CONNECTION_PROFILE_PREFIX = "bitchat-tpn-profile:"
private const val CONNECTION_PROFILE_VERSION = 1
internal const val DOGECOIN_TPN_CONNECTION_PROFILE_MAX_CHARS = 8_192
private const val FIELD_SEPARATOR = '|'
private val BASE64URL_CHARS = ('A'..'Z') + ('a'..'z') + ('0'..'9') + setOf('-', '_')
