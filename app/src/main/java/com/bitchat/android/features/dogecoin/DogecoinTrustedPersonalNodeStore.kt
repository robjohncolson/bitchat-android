package com.bitchat.android.features.dogecoin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Durable DES-1-A authorization storage. The non-secret trust tuple and Basic credentials live in
 * separate encrypted preference files and are joined only by an exact monotonically increasing
 * revision. There is deliberately no migration from ordinary RPC settings.
 */
internal class DogecoinTrustedPersonalNodeStore private constructor(
    trustPrefsProvider: () -> SharedPreferences,
    credentialPrefsProvider: () -> SharedPreferences
) {
    constructor(context: Context) : this(
        trustPrefsProvider = encryptedPrefsProvider(context, TRUST_PREFS_NAME),
        credentialPrefsProvider = encryptedPrefsProvider(context, CREDENTIAL_PREFS_NAME)
    )

    internal constructor(
        trustPrefs: SharedPreferences,
        credentialPrefs: SharedPreferences
    ) : this(
        trustPrefsProvider = { trustPrefs },
        credentialPrefsProvider = { credentialPrefs }
    )

    // Production preference/Keystore setup happens on the first store operation, which the settings
    // surface dispatches to IO; merely composing or editing a draft does not touch disk/Keystore.
    private val trustPrefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        trustPrefsProvider()
    }
    private val credentialPrefs: SharedPreferences by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        credentialPrefsProvider()
    }

    fun loadState(): DogecoinTrustedPersonalNodeState = synchronized(STORE_LOCK) {
        when (readStoredState()) {
            DogecoinTrustedPersonalNodeState.REVOKED -> DogecoinTrustedPersonalNodeState.REVOKED
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
            DogecoinTrustedPersonalNodeState.DISPUTED -> {
                val storedState = readStoredState()!!
                val profile = readTrustProfile()
                if (profile != null && readCredentials(profile.revision) != null) {
                    storedState
                } else {
                    DogecoinTrustedPersonalNodeState.UNAUTHORIZED
                }
            }
            else -> DogecoinTrustedPersonalNodeState.UNAUTHORIZED
        }
    }

    fun loadProfile(): DogecoinTrustedPersonalNodeProfile? = synchronized(STORE_LOCK) {
        if (readStoredState() !in setOf(
                DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
                DogecoinTrustedPersonalNodeState.DISPUTED
            )
        ) return@synchronized null
        val profile = readTrustProfile() ?: return@synchronized null
        profile.takeIf { readCredentials(it.revision) != null }
    }

    fun loadCredentials(
        profile: DogecoinTrustedPersonalNodeProfile
    ): DogecoinTrustedPersonalNodeCredentials? = synchronized(STORE_LOCK) {
        if (!isValidDogecoinTrustedPersonalNodeProfile(profile)) return@synchronized null
        if (readStoredState() !in setOf(
                DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE,
                DogecoinTrustedPersonalNodeState.DISPUTED
            )
        ) return@synchronized null
        if (readTrustProfile() != profile) return@synchronized null
        readCredentials(profile.revision)
    }

    fun loadDisputeStatus(
        profile: DogecoinTrustedPersonalNodeProfile
    ): DogecoinTrustedPersonalNodeDisputeStatus? = synchronized(STORE_LOCK) {
        if (readTrustProfile() != profile || readCredentials(profile.revision) == null) {
            return@synchronized null
        }
        val state = readStoredState()?.takeIf {
            it == DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE ||
                it == DogecoinTrustedPersonalNodeState.DISPUTED
        } ?: return@synchronized null
        DogecoinTrustedPersonalNodeDisputeStatus(
            state = state,
            stableConflictStreak = trustPrefs.getInt(KEY_DISPUTE_CONFLICT_STREAK, 0).coerceIn(0, 2),
            recoveryAgreementStreak = trustPrefs.getInt(KEY_DISPUTE_AGREEMENT_STREAK, 0).coerceIn(0, 2),
            recoveryReadyForOperator = state == DogecoinTrustedPersonalNodeState.DISPUTED &&
                trustPrefs.getInt(KEY_DISPUTE_AGREEMENT_STREAK, 0) >= 2,
            lastComparisonId = trustPrefs.getString(KEY_DISPUTE_LAST_COMPARISON_ID, null)
        )
    }

    /**
     * Persists stable conflict/recovery evidence for the exact profile. Replaying one comparison id is
     * idempotent. DISPUTED is entered only after two distinct fully-synced conflicts and never clears
     * automatically; two distinct agreements merely arm the explicit operator recovery action.
     */
    fun recordCrossCheck(
        profile: DogecoinTrustedPersonalNodeProfile,
        evidence: DogecoinTrustedPersonalNodeCrossCheckEvidence
    ): DogecoinTrustedPersonalNodeDisputeStatus? = synchronized(STORE_LOCK) {
        if (readTrustProfile() != profile || readCredentials(profile.revision) == null) {
            return@synchronized null
        }
        val currentState = readStoredState()?.takeIf {
            it == DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE ||
                it == DogecoinTrustedPersonalNodeState.DISPUTED
        } ?: return@synchronized null
        if (!isValidCrossCheckEvidence(evidence)) return@synchronized null
        val current = loadDisputeStatus(profile) ?: return@synchronized null
        if (current.lastComparisonId == evidence.comparisonId) return@synchronized current
        val lastComparisonAt = trustPrefs.getLong(KEY_DISPUTE_LAST_COMPARISON_AT, 0L)
        if (evidence.capturedAtMillis <= lastComparisonAt) return@synchronized null

        var nextState = currentState
        var conflictStreak = current.stableConflictStreak
        var agreementStreak = current.recoveryAgreementStreak
        when (currentState) {
            DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE -> when (evidence.result) {
                DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT -> {
                    conflictStreak = (conflictStreak + 1).coerceAtMost(2)
                    agreementStreak = 0
                    if (conflictStreak >= 2) nextState = DogecoinTrustedPersonalNodeState.DISPUTED
                }
                DogecoinTrustedPersonalNodeCrossCheckResult.AGREEMENT -> {
                    conflictStreak = 0
                    agreementStreak = 0
                }
                DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE -> {
                    conflictStreak = 0
                    agreementStreak = 0
                }
            }
            DogecoinTrustedPersonalNodeState.DISPUTED -> when (evidence.result) {
                DogecoinTrustedPersonalNodeCrossCheckResult.AGREEMENT -> {
                    agreementStreak = (agreementStreak + 1).coerceAtMost(2)
                    conflictStreak = 0
                }
                DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT,
                DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE -> {
                    agreementStreak = 0
                    if (evidence.result == DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT) {
                        conflictStreak = 2
                    }
                }
            }
            else -> return@synchronized null
        }
        val committed = trustPrefs.edit()
            .putString(KEY_STATE, nextState.name)
            .putInt(KEY_DISPUTE_CONFLICT_STREAK, conflictStreak)
            .putInt(KEY_DISPUTE_AGREEMENT_STREAK, agreementStreak)
            .putString(KEY_DISPUTE_LAST_COMPARISON_ID, evidence.comparisonId)
            .putLong(KEY_DISPUTE_LAST_COMPARISON_AT, evidence.capturedAtMillis)
            .commit()
        if (!committed) return@synchronized null
        loadDisputeStatus(profile)
    }

    /** Explicit human action after the locked two-agreement recovery gate. */
    fun clearDisputeAfterOperatorConfirmation(
        profile: DogecoinTrustedPersonalNodeProfile
    ): Boolean = synchronized(STORE_LOCK) {
        val status = loadDisputeStatus(profile) ?: return@synchronized false
        if (status.state != DogecoinTrustedPersonalNodeState.DISPUTED ||
            !status.recoveryReadyForOperator) return@synchronized false
        trustPrefs.edit()
            .putString(KEY_STATE, DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE.name)
            .remove(KEY_DISPUTE_CONFLICT_STREAK)
            .remove(KEY_DISPUTE_AGREEMENT_STREAK)
            .remove(KEY_DISPUTE_LAST_COMPARISON_ID)
            .remove(KEY_DISPUTE_LAST_COMPARISON_AT)
            .commit()
    }

    private fun isValidCrossCheckEvidence(
        evidence: DogecoinTrustedPersonalNodeCrossCheckEvidence
    ): Boolean {
        if (!Regex("^[0-9a-f]{64}:[0-9a-f]{64}$").matches(evidence.comparisonId)) return false
        if (!evidence.fullySyncedMainnet || evidence.confirmationContextDepth < 6 ||
            evidence.capturedAtMillis <= 0L) return false
        return when (evidence.result) {
            DogecoinTrustedPersonalNodeCrossCheckResult.AGREEMENT -> !evidence.hasConflictingSpend
            DogecoinTrustedPersonalNodeCrossCheckResult.CONFLICT -> true
            DogecoinTrustedPersonalNodeCrossCheckResult.INCONCLUSIVE -> !evidence.hasConflictingSpend
        }
    }

    /**
     * Credentials are committed first, followed by the complete trust tuple and revision in one
     * synchronous commit. A crash at any boundary leaves either the old matching pair or a revision
     * mismatch, which [loadState] treats as unauthorized. The caller cannot choose/roll back revision.
     */
    fun authorize(
        candidate: DogecoinTrustedPersonalNodeProfileCandidate,
        credentials: DogecoinTrustedPersonalNodeCredentials,
        authorizedAtMillis: Long
    ): DogecoinTrustedPersonalNodeProfile? = synchronized(STORE_LOCK) {
        if (!isValidDogecoinTrustedPersonalNodeProfileCandidate(candidate)) return@synchronized null
        if (!credentials.isValid()) return@synchronized null
        if (authorizedAtMillis <= 0L || candidate.rescanAttestedAtMillis > authorizedAtMillis) {
            return@synchronized null
        }

        val lastRevision = runCatching { trustPrefs.getLong(KEY_LAST_REVISION, 0L) }
            .getOrNull()
            ?.takeIf { it >= 0L }
            ?: return@synchronized null
        val storedProfileRevision = runCatching { trustPrefs.getLong(KEY_PROFILE_REVISION, 0L) }
            .getOrNull()
            ?.takeIf { it >= 0L }
            ?: return@synchronized null
        val revisionBase = maxOf(lastRevision, storedProfileRevision)
        if (revisionBase == Long.MAX_VALUE) return@synchronized null
        val revision = revisionBase + 1L

        val profile = DogecoinTrustedPersonalNodeProfile(
            origin = candidate.origin,
            network = candidate.network,
            androidAddress = candidate.androidAddress,
            coreWalletId = candidate.coreWalletId,
            policyVersion = DOGECOIN_TRUSTED_PERSONAL_NODE_POLICY_VERSION,
            revision = revision,
            authorizedAtMillis = authorizedAtMillis,
            rescanAttested = candidate.rescanAttested,
            rescanAttestedAtMillis = candidate.rescanAttestedAtMillis
        )
        if (!isValidDogecoinTrustedPersonalNodeProfile(profile)) return@synchronized null

        val credentialsCommitted = runCatching {
            credentialPrefs.edit()
                .clear()
                .putLong(KEY_CREDENTIAL_REVISION, revision)
                .putString(KEY_CREDENTIAL_USERNAME, credentials.username)
                .putString(KEY_CREDENTIAL_PASSWORD, credentials.password)
                .commit()
        }.getOrDefault(false)
        if (!credentialsCommitted) return@synchronized null

        val profileCommitted = runCatching {
            trustPrefs.edit()
                .clear()
                .putString(KEY_STATE, DogecoinTrustedPersonalNodeState.AUTHORIZED_INACTIVE.name)
                .putString(KEY_PROFILE_ORIGIN, profile.origin)
                .putString(KEY_PROFILE_NETWORK, profile.network.id)
                .putString(KEY_PROFILE_ANDROID_ADDRESS, profile.androidAddress)
                .putString(KEY_PROFILE_CORE_WALLET_ID, profile.coreWalletId)
                .putInt(KEY_PROFILE_POLICY_VERSION, profile.policyVersion)
                .putLong(KEY_PROFILE_REVISION, profile.revision)
                .putLong(KEY_PROFILE_AUTHORIZED_AT, profile.authorizedAtMillis)
                .putBoolean(KEY_PROFILE_RESCAN_ATTESTED, profile.rescanAttested)
                .putLong(KEY_PROFILE_RESCAN_ATTESTED_AT, profile.rescanAttestedAtMillis)
                .putLong(KEY_LAST_REVISION, profile.revision)
                .commit()
        }.getOrDefault(false)

        if (!profileCommitted) {
            // Best effort only: the old profile cannot pair with the newly written revision either way.
            runCatching { credentialPrefs.edit().clear().commit() }
            return@synchronized null
        }
        profile
    }

    /** Persist the tombstone before deleting credentials, so every interruption is fail-closed. */
    fun revoke(): Boolean = synchronized(STORE_LOCK) {
        val lastRevision = runCatching {
            maxOf(
                trustPrefs.getLong(KEY_LAST_REVISION, 0L),
                trustPrefs.getLong(KEY_PROFILE_REVISION, 0L)
            )
        }.getOrNull()?.takeIf { it >= 0L } ?: Long.MAX_VALUE

        val trustRevoked = runCatching {
            trustPrefs.edit()
                .clear()
                .putString(KEY_STATE, DogecoinTrustedPersonalNodeState.REVOKED.name)
                .putLong(KEY_LAST_REVISION, lastRevision)
                .commit()
        }.getOrDefault(false)
        if (!trustRevoked) return@synchronized false

        runCatching { credentialPrefs.edit().clear().commit() }.getOrDefault(false)
    }

    private fun readStoredState(): DogecoinTrustedPersonalNodeState? = runCatching {
        trustPrefs.getString(KEY_STATE, null)?.let { stored ->
            DogecoinTrustedPersonalNodeState.values().firstOrNull { it.name == stored }
        }
    }.getOrNull()

    private fun readTrustProfile(): DogecoinTrustedPersonalNodeProfile? = runCatching {
        val networkId = trustPrefs.getString(KEY_PROFILE_NETWORK, null) ?: return@runCatching null
        val network = DogecoinNetwork.values().firstOrNull { it.id == networkId } ?: return@runCatching null
        DogecoinTrustedPersonalNodeProfile(
            origin = trustPrefs.getString(KEY_PROFILE_ORIGIN, null) ?: return@runCatching null,
            network = network,
            androidAddress = trustPrefs.getString(KEY_PROFILE_ANDROID_ADDRESS, null) ?: return@runCatching null,
            coreWalletId = trustPrefs.getString(KEY_PROFILE_CORE_WALLET_ID, null) ?: return@runCatching null,
            policyVersion = trustPrefs.getInt(KEY_PROFILE_POLICY_VERSION, 0),
            revision = trustPrefs.getLong(KEY_PROFILE_REVISION, 0L),
            authorizedAtMillis = trustPrefs.getLong(KEY_PROFILE_AUTHORIZED_AT, 0L),
            rescanAttested = trustPrefs.getBoolean(KEY_PROFILE_RESCAN_ATTESTED, false),
            rescanAttestedAtMillis = trustPrefs.getLong(KEY_PROFILE_RESCAN_ATTESTED_AT, 0L)
        ).takeIf(::isValidDogecoinTrustedPersonalNodeProfile)
    }.getOrNull()

    private fun readCredentials(revision: Long): DogecoinTrustedPersonalNodeCredentials? = runCatching {
        if (credentialPrefs.getLong(KEY_CREDENTIAL_REVISION, 0L) != revision) return@runCatching null
        DogecoinTrustedPersonalNodeCredentials(
            username = credentialPrefs.getString(KEY_CREDENTIAL_USERNAME, null) ?: return@runCatching null,
            password = credentialPrefs.getString(KEY_CREDENTIAL_PASSWORD, null) ?: return@runCatching null
        ).takeIf { it.isValid() }
    }.getOrNull()

    private companion object {
        val STORE_LOCK = Any()

        const val TRUST_PREFS_NAME = "dogecoin_tpn_trust"
        const val CREDENTIAL_PREFS_NAME = "dogecoin_tpn_credentials"

        const val KEY_STATE = "state"
        const val KEY_LAST_REVISION = "last_revision"
        const val KEY_PROFILE_ORIGIN = "profile_origin"
        const val KEY_PROFILE_NETWORK = "profile_network"
        const val KEY_PROFILE_ANDROID_ADDRESS = "profile_android_address"
        const val KEY_PROFILE_CORE_WALLET_ID = "profile_core_wallet_id"
        const val KEY_PROFILE_POLICY_VERSION = "profile_policy_version"
        const val KEY_PROFILE_REVISION = "profile_revision"
        const val KEY_PROFILE_AUTHORIZED_AT = "profile_authorized_at"
        const val KEY_PROFILE_RESCAN_ATTESTED = "profile_rescan_attested"
        const val KEY_PROFILE_RESCAN_ATTESTED_AT = "profile_rescan_attested_at"
        const val KEY_DISPUTE_CONFLICT_STREAK = "dispute_conflict_streak"
        const val KEY_DISPUTE_AGREEMENT_STREAK = "dispute_agreement_streak"
        const val KEY_DISPUTE_LAST_COMPARISON_ID = "dispute_last_comparison_id"
        const val KEY_DISPUTE_LAST_COMPARISON_AT = "dispute_last_comparison_at"

        const val KEY_CREDENTIAL_REVISION = "credential_revision"
        const val KEY_CREDENTIAL_USERNAME = "credential_username"
        const val KEY_CREDENTIAL_PASSWORD = "credential_password"

        fun encryptedPrefsProvider(context: Context, name: String): () -> SharedPreferences {
            val appContext = context.applicationContext
            return { createEncryptedPrefs(appContext, name) }
        }

        fun createEncryptedPrefs(context: Context, name: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
