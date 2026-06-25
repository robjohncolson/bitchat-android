package com.bitchat.android.features.dogecoin

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

data class DogecoinRpcConfig(
    val url: String = "",
    val username: String = "",
    val password: String = "",
    val walletName: String = ""
) {
    fun normalized(network: DogecoinNetwork): DogecoinRpcConfig {
        val parsedAuth = if (password.isBlank()) parseDogecoinRpcAuthToken(username) else null
        return copy(
            url = normalizedUrl(network),
            username = parsedAuth?.first ?: username.trim(),
            password = parsedAuth?.second ?: password,
            walletName = normalizedWalletName()
        )
    }

    fun normalizedUrl(network: DogecoinNetwork): String {
        return url.trim()
    }

    fun walletEndpointUrl(): String {
        val cleanWalletName = normalizedWalletName()
        if (cleanWalletName.isBlank()) return url.trim()

        val parsedUrl = url.trim().toHttpUrlOrNull() ?: return url.trim()
        val builder = parsedUrl.newBuilder()
        val walletSegmentIndex = parsedUrl.pathSegments.indexOf("wallet")
        if (walletSegmentIndex >= 0) {
            repeat(parsedUrl.pathSegments.size - walletSegmentIndex) {
                builder.removePathSegment(walletSegmentIndex)
            }
        }
        return builder
            .addPathSegment("wallet")
            .addPathSegment(cleanWalletName)
            .build()
            .toString()
    }

    fun hasValidUrl(network: DogecoinNetwork): Boolean {
        val parsedUrl = normalizedUrl(network).toHttpUrlOrNull() ?: return false
        return when (parsedUrl.scheme) {
            "https" -> true
            "http" -> isAllowedCleartextRpcHost(parsedUrl.host)
            else -> false
        }
    }

    private fun isAllowedCleartextRpcHost(host: String): Boolean {
        val normalizedHost = host.trim().lowercase().trimEnd('.')
        if (normalizedHost == "localhost" || normalizedHost.endsWith(".local")) return true
        if (!normalizedHost.contains(".") && !normalizedHost.contains(":")) return true
        return isPrivateIpv4Host(normalizedHost) || isPrivateIpv6Host(normalizedHost)
    }

    private fun isPrivateIpv4Host(host: String): Boolean {
        val octets = host.split(".").map { it.toIntOrNull() ?: return false }
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

    private fun isPrivateIpv6Host(host: String): Boolean {
        val normalizedHost = host.removeSurrounding("[", "]")
        return normalizedHost == "::1" ||
            normalizedHost.startsWith("fc") ||
            normalizedHost.startsWith("fd") ||
            normalizedHost.startsWith("fe80:")
    }

    private fun normalizedWalletName(): String {
        return walletName.trim().trim('/')
    }
}

internal fun parseDogecoinRpcAuthToken(value: String): Pair<String, String>? {
    val trimmed = value.trim()
    val separatorIndex = trimmed.indexOf(':')
    if (separatorIndex <= 0 || separatorIndex >= trimmed.lastIndex) return null
    return trimmed.substring(0, separatorIndex) to trimmed.substring(separatorIndex + 1)
}

data class DogecoinWalletSnapshot(
    val key: DogecoinWalletKey,
    val rpcConfig: DogecoinRpcConfig
)

data class DogecoinWifCopyState(
    val address: String?,
    val copiedAtMillis: Long
) {
    fun matches(key: DogecoinWalletKey): Boolean {
        return copiedAtMillis > 0L && address == key.address
    }
}

data class DogecoinSavedAddress(
    val address: String,
    val label: String,
    val savedAtMillis: Long
)

class DogecoinWalletRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences by lazy {
        createEncryptedPrefs(PREFS_NAME).also { migrateLegacyPrefsIfNeeded(it) }
    }
    private val legacyPrefs: SharedPreferences by lazy {
        createEncryptedPrefs(LEGACY_PREFS_NAME)
    }

    fun loadOrCreateWallet(network: DogecoinNetwork = loadSelectedNetwork()): DogecoinWalletSnapshot {
        val privateKeyPrefsKey = privateKeyKey(network)
        val existingPrivateKeyHex = prefs.getString(privateKeyPrefsKey, null)
            ?: if (network == DogecoinNetwork.TESTNET) prefs.getString(KEY_LEGACY_PRIVATE_KEY_HEX, null) else null
        val key = if (existingPrivateKeyHex.isNullOrBlank()) {
            DogecoinKeyGenerator.generate(network).also { generated ->
                prefs.edit()
                    .putString(privateKeyPrefsKey, generated.privateKeyHex)
                    .putBoolean(compressedKey(network), generated.isCompressed)
                    .putString(createdAtKey(network), System.currentTimeMillis().toString())
                    .apply()
            }
        } else {
            DogecoinKeyGenerator.fromPrivateKeyHex(
                existingPrivateKeyHex,
                network,
                compressed = loadIsCompressed(network)
            ).also {
                if (!prefs.contains(privateKeyPrefsKey)) {
                    prefs.edit()
                        .putString(privateKeyPrefsKey, existingPrivateKeyHex)
                        .putBoolean(compressedKey(network), it.isCompressed)
                        .apply()
                }
            }
        }

        return DogecoinWalletSnapshot(
            key = key,
            rpcConfig = loadRpcConfig(network)
        )
    }

    /**
     * Read-only variant of [loadOrCreateWallet]: returns the persisted wallet for [network] if a
     * private key already exists, or null otherwise. Unlike [loadOrCreateWallet] this NEVER
     * generates or persists key material, so it is safe to call from background paths (e.g. the
     * mesh announce loop) without silently creating a wallet for a user who never opened the feature.
     */
    fun loadWalletIfPresent(network: DogecoinNetwork = loadSelectedNetwork()): DogecoinWalletSnapshot? {
        val existingPrivateKeyHex = prefs.getString(privateKeyKey(network), null)
            ?: if (network == DogecoinNetwork.TESTNET) prefs.getString(KEY_LEGACY_PRIVATE_KEY_HEX, null) else null
        if (existingPrivateKeyHex.isNullOrBlank()) return null

        val key = DogecoinKeyGenerator.fromPrivateKeyHex(
            existingPrivateKeyHex,
            network,
            compressed = loadIsCompressed(network)
        )
        return DogecoinWalletSnapshot(
            key = key,
            rpcConfig = loadRpcConfig(network)
        )
    }

    /**
     * Whether the user has opted in to advertising their Dogecoin receive address to mesh peers
     * (so peers can "pay @nickname"). Defaults to false: nothing is advertised until the user
     * explicitly enables it in the wallet UI.
     */
    fun loadAdvertiseAddressEnabled(): Boolean {
        return prefs.getBoolean(KEY_ADVERTISE_ADDRESS_ENABLED, false)
    }

    fun saveAdvertiseAddressEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_ADVERTISE_ADDRESS_ENABLED, enabled)
            .apply()
    }

    /**
     * Whether this device will broadcast OTHER peers' signed transactions through its own Dogecoin
     * node (Milestone 3b "helper"). Per-network and default false: mainnet must be enabled explicitly
     * and independently of testnet/regtest, so running a testnet helper never silently relays mainnet.
     */
    fun loadHelperEnabled(network: DogecoinNetwork): Boolean {
        return prefs.getBoolean(helperEnabledKey(network), false)
    }

    fun saveHelperEnabled(network: DogecoinNetwork, enabled: Boolean) {
        prefs.edit()
            .putBoolean(helperEnabledKey(network), enabled)
            .apply()
    }

    /**
     * Whether the helper only serves mutual-favorite peers. Defaults to true (favorites-only) to keep
     * the privacy-linkage and Sybil/amplification surface small for a privacy-first app.
     */
    fun loadHelperFavoritesOnly(): Boolean {
        return prefs.getBoolean(KEY_HELPER_FAVORITES_ONLY, true)
    }

    fun saveHelperFavoritesOnly(favoritesOnly: Boolean) {
        prefs.edit()
            .putBoolean(KEY_HELPER_FAVORITES_ONLY, favoritesOnly)
            .apply()
    }

    fun loadSelectedNetwork(): DogecoinNetwork {
        val hasExistingWallet = prefs.contains(privateKeyKey(DogecoinNetwork.MAINNET)) ||
            prefs.contains(privateKeyKey(DogecoinNetwork.TESTNET)) ||
            prefs.contains(KEY_LEGACY_PRIVATE_KEY_HEX)
        return dogecoinNetworkForStoredSelection(
            prefs.getString(KEY_SELECTED_NETWORK, null),
            hasExistingWallet
        )
    }

    fun saveSelectedNetwork(network: DogecoinNetwork) {
        prefs.edit()
            .putString(KEY_SELECTED_NETWORK, network.id)
            .apply()
    }

    fun loadRpcConfig(network: DogecoinNetwork): DogecoinRpcConfig {
        return DogecoinRpcConfig(
            url = prefs.getString(rpcUrlKey(network), null)
                ?: legacyTestnetString(network, KEY_LEGACY_RPC_URL)
                ?: "",
            username = prefs.getString(rpcUsernameKey(network), null)
                ?: legacyTestnetString(network, KEY_LEGACY_RPC_USERNAME)
                ?: "",
            password = prefs.getString(rpcPasswordKey(network), null)
                ?: legacyTestnetString(network, KEY_LEGACY_RPC_PASSWORD)
                ?: "",
            walletName = prefs.getString(rpcWalletNameKey(network), null) ?: ""
        ).normalized(network)
    }

    fun saveRpcConfig(network: DogecoinNetwork, config: DogecoinRpcConfig) {
        prefs.edit()
            .putString(rpcUrlKey(network), config.url.trim())
            .putString(rpcUsernameKey(network), config.username.trim())
            .putString(rpcPasswordKey(network), config.password)
            .putString(rpcWalletNameKey(network), config.walletName.trim().trim('/'))
            .apply()
    }

    fun loadWifCopyState(key: DogecoinWalletKey): DogecoinWifCopyState {
        return DogecoinWifCopyState(
            address = prefs.getString(wifCopyAddressKey(key.network), null),
            copiedAtMillis = prefs.getLong(wifCopyAtKey(key.network), 0L)
        )
    }

    fun loadPracticeNudgeDismissed(): Boolean {
        return prefs.getBoolean(KEY_PRACTICE_NUDGE_DISMISSED, false)
    }

    fun dismissPracticeNudge() {
        prefs.edit()
            .putBoolean(KEY_PRACTICE_NUDGE_DISMISSED, true)
            .apply()
    }

    fun markWifCopied(key: DogecoinWalletKey): DogecoinWifCopyState {
        return markWifBackedUp(key)
    }

    fun markWifBackedUp(key: DogecoinWalletKey): DogecoinWifCopyState {
        val copiedAtMillis = System.currentTimeMillis()
        prefs.edit()
            .putString(wifCopyAddressKey(key.network), key.address)
            .putLong(wifCopyAtKey(key.network), copiedAtMillis)
            .apply()
        return DogecoinWifCopyState(key.address, copiedAtMillis)
    }

    fun loadSavedAddresses(network: DogecoinNetwork): List<DogecoinSavedAddress> {
        return dogecoinSavedAddressesFromJson(prefs.getString(addressBookKey(network), null), network)
    }

    fun upsertSavedAddress(
        network: DogecoinNetwork,
        address: String,
        label: String = "",
        savedAtMillis: Long = System.currentTimeMillis()
    ): List<DogecoinSavedAddress> {
        val cleanAddress = address.trim()
        require(DogecoinAddress.isValidAddress(cleanAddress, network)) {
            "Invalid Dogecoin ${network.displayName} address"
        }

        val savedAddress = DogecoinSavedAddress(
            address = cleanAddress,
            label = label.trim(),
            savedAtMillis = savedAtMillis
        )
        val updatedAddresses = listOf(savedAddress) +
            loadSavedAddresses(network).filterNot { it.address == cleanAddress }
        saveSavedAddresses(network, updatedAddresses)
        return updatedAddresses
    }

    fun removeSavedAddress(network: DogecoinNetwork, address: String): List<DogecoinSavedAddress> {
        val cleanAddress = address.trim()
        val updatedAddresses = loadSavedAddresses(network).filterNot { it.address == cleanAddress }
        saveSavedAddresses(network, updatedAddresses)
        return updatedAddresses
    }

    fun resetWallet(network: DogecoinNetwork): DogecoinWalletSnapshot {
        val editor = prefs.edit()
            .remove(privateKeyKey(network))
            .remove(compressedKey(network))
            .remove(createdAtKey(network))
            .remove(wifCopyAddressKey(network))
            .remove(wifCopyAtKey(network))
            .remove(addressBookKey(network))
        if (network == DogecoinNetwork.TESTNET) {
            editor.remove(KEY_LEGACY_PRIVATE_KEY_HEX)
        }
        editor.apply()
        return loadOrCreateWallet(network)
    }

    fun importWalletFromWif(network: DogecoinNetwork, wif: String): DogecoinWalletSnapshot {
        val key = DogecoinKeyGenerator.fromWif(wif, expectedNetwork = network)
        val backedUpAtMillis = System.currentTimeMillis()
        prefs.edit()
            .putString(privateKeyKey(network), key.privateKeyHex)
            .putBoolean(compressedKey(network), key.isCompressed)
            .putString(createdAtKey(network), backedUpAtMillis.toString())
            .putString(wifCopyAddressKey(network), key.address)
            .putLong(wifCopyAtKey(network), backedUpAtMillis)
            .apply()
        return DogecoinWalletSnapshot(
            key = key,
            rpcConfig = loadRpcConfig(network)
        )
    }

    @Deprecated("Use loadRpcConfig(network) so RPC state cannot cross Dogecoin networks.")
    fun loadRpcConfig(): DogecoinRpcConfig {
        val network = loadSelectedNetwork()
        return DogecoinRpcConfig(
            url = prefs.getString(rpcUrlKey(network), "") ?: "",
            username = prefs.getString(rpcUsernameKey(network), "") ?: "",
            password = prefs.getString(rpcPasswordKey(network), "") ?: "",
            walletName = prefs.getString(rpcWalletNameKey(network), "") ?: ""
        )
    }

    @Deprecated("Use saveRpcConfig(network, config) so RPC state cannot cross Dogecoin networks.")
    fun saveRpcConfig(config: DogecoinRpcConfig) {
        saveRpcConfig(loadSelectedNetwork(), config)
    }

    @Deprecated("Use resetWallet(network) so the selected Dogecoin network is explicit.")
    fun resetWallet(): DogecoinWalletSnapshot {
        return resetWallet(loadSelectedNetwork())
    }

    private fun createEncryptedPrefs(name: String): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            appContext,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateLegacyPrefsIfNeeded(target: SharedPreferences) {
        if (target.getBoolean(KEY_LEGACY_PREFS_MIGRATED, false)) return

        val sourceValues = runCatching { legacyPrefs.all }.getOrElse { emptyMap() }
        if (sourceValues.isEmpty()) {
            target.edit()
                .putBoolean(KEY_LEGACY_PREFS_MIGRATED, true)
                .apply()
            return
        }

        val editor = target.edit()
        sourceValues.forEach { (key, value) ->
            if (target.contains(key)) return@forEach
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> if (value.all { it is String }) {
                    editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
        }
        editor.putBoolean(KEY_LEGACY_PREFS_MIGRATED, true).apply()
    }

    private fun saveSavedAddresses(network: DogecoinNetwork, savedAddresses: List<DogecoinSavedAddress>) {
        val array = JSONArray()
        savedAddresses.forEach { savedAddress ->
            array.put(
                JSONObject()
                    .put("address", savedAddress.address)
                    .put("label", savedAddress.label)
                    .put("savedAtMillis", savedAddress.savedAtMillis)
            )
        }
        prefs.edit()
            .putString(addressBookKey(network), array.toString())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "dogecoin_wallet"
        const val LEGACY_PREFS_NAME = "dogecoin_testnet_wallet"
        const val KEY_LEGACY_PREFS_MIGRATED = "legacy_testnet_prefs_migrated"
        const val KEY_SELECTED_NETWORK = "selected_network"
        const val KEY_ADVERTISE_ADDRESS_ENABLED = "advertise_address_enabled"
        const val KEY_HELPER_FAVORITES_ONLY = "helper_favorites_only"
        const val KEY_LEGACY_PRIVATE_KEY_HEX = "private_key_hex"
        const val KEY_LEGACY_RPC_URL = "rpc_url"
        const val KEY_LEGACY_RPC_USERNAME = "rpc_username"
        const val KEY_LEGACY_RPC_PASSWORD = "rpc_password"
        const val KEY_PRACTICE_NUDGE_DISMISSED = "practice_nudge_dismissed"

        fun privateKeyKey(network: DogecoinNetwork): String = "${network.id}_private_key_hex"
        fun helperEnabledKey(network: DogecoinNetwork): String = "${network.id}_helper_enabled"
        fun compressedKey(network: DogecoinNetwork): String = "${network.id}_compressed"
        fun createdAtKey(network: DogecoinNetwork): String = "${network.id}_created_at"
        fun rpcUrlKey(network: DogecoinNetwork): String = "${network.id}_rpc_url"
        fun rpcUsernameKey(network: DogecoinNetwork): String = "${network.id}_rpc_username"
        fun rpcPasswordKey(network: DogecoinNetwork): String = "${network.id}_rpc_password"
        fun rpcWalletNameKey(network: DogecoinNetwork): String = "${network.id}_rpc_wallet_name"
        fun wifCopyAddressKey(network: DogecoinNetwork): String = "${network.id}_wif_copy_address"
        fun wifCopyAtKey(network: DogecoinNetwork): String = "${network.id}_wif_copy_at"
        fun addressBookKey(network: DogecoinNetwork): String = "${network.id}_address_book"
    }

    private fun loadIsCompressed(network: DogecoinNetwork): Boolean {
        val key = compressedKey(network)
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else true
    }

    private fun legacyTestnetString(network: DogecoinNetwork, key: String): String? {
        return if (network == DogecoinNetwork.TESTNET) prefs.getString(key, null) else null
    }
}

internal fun dogecoinNetworkForStoredSelection(
    storedSelection: String?,
    hasExistingWallet: Boolean
): DogecoinNetwork {
    if (!storedSelection.isNullOrBlank()) return DogecoinNetwork.fromId(storedSelection)
    // A genuine first run (no stored selection AND no wallet yet) defaults to testnet so users can
    // practice without real funds. An existing wallet keeps the historical mainnet default so an
    // upgrade never silently moves a user off their (possibly funded) mainnet wallet.
    return if (hasExistingWallet) DogecoinNetwork.DEFAULT else DogecoinNetwork.TESTNET
}

/**
 * Parse a persisted address-book JSON array. Tolerant of null/blank/corrupt input (returns an empty
 * list rather than throwing), skips non-object/invalid/wrong-network entries, and dedupes by address
 * keeping the first occurrence.
 */
internal fun dogecoinSavedAddressesFromJson(
    raw: String?,
    network: DogecoinNetwork
): List<DogecoinSavedAddress> {
    if (raw.isNullOrBlank()) return emptyList()
    val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
    val savedAddresses = mutableListOf<DogecoinSavedAddress>()
    val seenAddresses = mutableSetOf<String>()
    for (index in 0 until array.length()) {
        val json = array.optJSONObject(index) ?: continue
        val address = json.optString("address").trim()
        if (!DogecoinAddress.isValidAddress(address, network)) continue
        if (!seenAddresses.add(address)) continue
        savedAddresses.add(
            DogecoinSavedAddress(
                address = address,
                label = json.optString("label").trim(),
                savedAtMillis = json.optLong("savedAtMillis", 0L)
            )
        )
    }
    return savedAddresses
}
