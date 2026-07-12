package com.bitchat.android.features.dogecoin

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class DogecoinNodeStatus(
    val connected: Boolean,
    val expectedNetwork: DogecoinNetwork = DogecoinNetwork.DEFAULT,
    val chain: String? = null,
    val blocks: Int? = null,
    val headers: Int? = null,
    val pruned: Boolean? = null,
    val initialBlockDownload: Boolean? = null,
    val verificationProgress: Double? = null,
    val walletReady: Boolean? = null,
    val walletName: String? = null,
    val loadedWalletNames: List<String> = emptyList(),
    val walletError: String? = null,
    val relayReady: Boolean? = null,
    val networkActive: Boolean? = null,
    val peerCount: Int? = null,
    val relayFeePerKbKoinu: Long? = null,
    val incrementalFeePerKbKoinu: Long? = null,
    val softDustLimitKoinu: Long? = null,
    val hardDustLimitKoinu: Long? = null,
    val relayError: String? = null,
    val rescanBlockchainAvailable: Boolean? = null,
    val rescanBlockchainError: String? = null,
    val policyCheckAvailable: Boolean? = null,
    val policyCheckError: String? = null,
    val error: String? = null
) {
    val isUsable: Boolean
        get() = connected && chain == expectedNetwork.chainName

    val isReady: Boolean
        get() = isUsable && initialBlockDownload != true && walletReady != false

    fun isUsableFor(network: DogecoinNetwork): Boolean {
        return connected && expectedNetwork == network && chain == network.chainName
    }

    fun isReadyFor(network: DogecoinNetwork): Boolean {
        return isUsableFor(network) && initialBlockDownload != true && walletReady != false
    }

    fun supportsHistoricalRescanFor(network: DogecoinNetwork): Boolean {
        return isReadyFor(network) && pruned != true
    }

    fun canBroadcastFor(network: DogecoinNetwork): Boolean {
        return isReadyFor(network) && relayReady == true
    }
}

data class DogecoinWalletBalance(
    val confirmedKoinu: Long,
    val unconfirmedKoinu: Long,
    val utxoCount: Int,
    val utxos: List<DogecoinUtxo> = emptyList()
) {
    init {
        require(confirmedKoinu >= 0L) { "Confirmed Dogecoin balance must be non-negative" }
        require(unconfirmedKoinu >= 0L) { "Unconfirmed Dogecoin balance must be non-negative" }
        require(utxoCount >= 0) { "Dogecoin UTXO count must be non-negative" }
    }

    val totalKoinu: Long
        get() = dogecoinSaturatingAddKoinu(confirmedKoinu, unconfirmedKoinu)

    val confirmedUtxos: List<DogecoinUtxo>
        get() = utxos.filter { it.confirmations > 0 }

    val unconfirmedUtxos: List<DogecoinUtxo>
        get() = utxos.filter { it.confirmations <= 0 }
}

data class DogecoinRescanResult(
    val startHeight: Int?,
    val stopHeight: Int?
)

data class DogecoinWalletActivity(
    val txid: String,
    val category: String,
    val address: String?,
    val amountKoinu: Long,
    val feeKoinu: Long?,
    val confirmations: Int,
    val timeSeconds: Long?,
    val involvesWatchOnly: Boolean
)

data class DogecoinAddressWatchStatus(
    val address: String,
    val isMine: Boolean,
    val isWatchOnly: Boolean
) {
    val isImported: Boolean
        get() = isMine || isWatchOnly
}

/**
 * Result of the DES-1-A one-shot trust-ceremony probe. Every field is node-reported setup evidence,
 * not an independent proof that the node is honest or that its historical rescan is complete.
 */
internal data class DogecoinTrustedPersonalNodeProvisioningResult(
    val origin: String,
    val network: DogecoinNetwork,
    val androidAddress: String,
    val coreWalletId: String,
    val chain: String,
    val watchStatus: DogecoinAddressWatchStatus
)

/**
 * Display-only DES-1-B balance. This deliberately does not expose the node's UTXO rows, so a
 * node-reported balance cannot accidentally enter coin selection before DES-1-C proof support exists.
 */
internal data class DogecoinTrustedPersonalNodeDisplayBalance(
    val confirmedKoinu: Long,
    val unconfirmedKoinu: Long,
    val utxoCount: Int
) {
    init {
        require(confirmedKoinu >= 0L) { "Confirmed trusted-node balance must be non-negative." }
        require(unconfirmedKoinu >= 0L) { "Unconfirmed trusted-node balance must be non-negative." }
        require(utxoCount >= 0) { "Trusted-node UTXO count must be non-negative." }
    }

    val totalKoinu: Long
        get() = dogecoinSaturatingAddKoinu(confirmedKoinu, unconfirmedKoinu)
}

/**
 * One fixed, read-only DES-1-B result. Freshness is attached by the in-memory session with the phone's
 * monotonic clock; no node timestamp is allowed to decide whether this snapshot is fresh.
 */
internal data class DogecoinTrustedPersonalNodeDisplaySnapshot(
    val profileRevision: Long,
    val origin: String,
    val androidAddress: String,
    val coreWalletId: String,
    val blocks: Int,
    val headers: Int,
    val verificationProgress: Double,
    val peerCount: Int,
    val balance: DogecoinTrustedPersonalNodeDisplayBalance,
    val activity: List<DogecoinWalletActivity>
)

/** HTTP status is retained so the TPN session can distinguish 401/403 from route/node degradation. */
internal class DogecoinRpcHttpException(
    val statusCode: Int,
    message: String
) : IllegalStateException(message)

internal fun Throwable.isDogecoinRpcAuthenticationFailure(): Boolean =
    this is DogecoinRpcHttpException && (statusCode == 401 || statusCode == 403)

data class DogecoinMempoolAcceptance(
    val checked: Boolean,
    val allowed: Boolean?,
    val txid: String? = null,
    val rejectReason: String? = null,
    val error: String? = null
) {
    val isAllowed: Boolean
        get() = checked && allowed == true
}

private class DogecoinRpcActiveCalls {
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    @Volatile private var cancelled = false

    fun register(call: Call) {
        calls.add(call)
        // Close the small race where dismissal revokes the request lease after beforeRequest(), but before
        // this Call is registered. This client family is sheet-owned and never reused after cancellation.
        if (cancelled) call.cancel()
    }

    fun unregister(call: Call) {
        calls.remove(call)
    }

    fun cancelAll() {
        cancelled = true
        calls.toList().forEach(Call::cancel)
    }
}

class DogecoinRpcClient private constructor(
    httpClient: OkHttpClient,
    private val gson: Gson,
    private val beforeRequest: () -> Unit,
    private val activeCalls: DogecoinRpcActiveCalls
) {
    constructor(
        httpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .build(),
        gson: Gson = Gson(),
        beforeRequest: () -> Unit = {}
    ) : this(httpClient, gson, beforeRequest, DogecoinRpcActiveCalls())

    // Hardened unconditionally (even for an injected client): redirects are never followed, so RPC Basic
    // credentials can never be forwarded to a changed origin.
    private val httpClient: OkHttpClient = hardenedDogecoinRpcHttpClient(httpClient)
    private val rescanHttpClient = this.httpClient.newBuilder()
        .readTimeout(30, TimeUnit.MINUTES)
        .build()

    /**
     * Return a client sharing this client's connection pool/timeouts but bound to a revocable caller
     * lease. [beforeRequest] runs at the central chokepoint before every RPC in a multi-call workflow.
     */
    internal fun guardedBy(requestGuard: () -> Unit): DogecoinRpcClient =
        DogecoinRpcClient(
            httpClient = httpClient,
            gson = gson,
            beforeRequest = {
                beforeRequest()
                requestGuard()
            },
            activeCalls = activeCalls
        )

    /** Cancel only calls issued by this client family (base plus guarded workflow clones). */
    internal fun cancelActiveRequests() {
        activeCalls.cancelAll()
    }

    suspend fun getBlockchainStatus(
        config: DogecoinRpcConfig,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): DogecoinNodeStatus = withContext(Dispatchers.IO) {
        try {
            val rpcConfig = normalizedRpcConfig(config, network)
            val result = callObject(rpcConfig, "getblockchaininfo")
            val chain = parseOptionalString(result, "chain", "getblockchaininfo")
            val initialBlockDownload = parseOptionalBoolean(
                result,
                fieldName = "initialblockdownload",
                method = "getblockchaininfo"
            )
            val blocks = parseOptionalNonNegativeInt(result, "blocks", "getblockchaininfo")
            val headers = parseOptionalNonNegativeInt(result, "headers", "getblockchaininfo")
            require(blocks == null || headers == null || headers >= blocks) {
                "RPC getblockchaininfo returned headers below the current block height."
            }
            val verificationProgress =
                parseOptionalProgressFraction(result, "verificationprogress", "getblockchaininfo")
            val pruned = parseOptionalBoolean(result, "pruned", "getblockchaininfo")
            val walletStatus = if (chain == network.chainName && initialBlockDownload != true) {
                getWalletInfoStatus(rpcConfig)
            } else {
                WalletInfoStatus.NotChecked
            }
            val relayStatus = if (chain == network.chainName && initialBlockDownload != true && walletStatus.ready != false) {
                getRelayInfoStatus(rpcConfig, network)
            } else {
                RelayInfoStatus.NotChecked
            }
            val policyCheckStatus = if (relayStatus.ready == true) {
                getMempoolPolicyCheckStatus(rpcConfig)
            } else {
                PolicyCheckStatus.NotChecked
            }
            val rescanBlockchainStatus = if (
                chain == network.chainName &&
                initialBlockDownload != true &&
                walletStatus.ready != false &&
                pruned != true
            ) {
                getRescanBlockchainStatus(rpcConfig)
            } else {
                RescanBlockchainStatus.NotChecked
            }
            val status = DogecoinNodeStatus(
                connected = chain == network.chainName,
                expectedNetwork = network,
                chain = chain,
                blocks = blocks,
                headers = headers,
                pruned = pruned,
                initialBlockDownload = initialBlockDownload,
                verificationProgress = verificationProgress,
                walletReady = walletStatus.ready,
                walletName = walletStatus.walletName,
                loadedWalletNames = walletStatus.loadedWalletNames,
                walletError = walletStatus.error,
                relayReady = relayStatus.ready,
                networkActive = relayStatus.networkActive,
                peerCount = relayStatus.peerCount,
                relayFeePerKbKoinu = relayStatus.relayFeePerKbKoinu,
                incrementalFeePerKbKoinu = relayStatus.incrementalFeePerKbKoinu,
                softDustLimitKoinu = relayStatus.softDustLimitKoinu,
                hardDustLimitKoinu = relayStatus.hardDustLimitKoinu,
                relayError = relayStatus.error,
                rescanBlockchainAvailable = rescanBlockchainStatus.available,
                rescanBlockchainError = rescanBlockchainStatus.error,
                policyCheckAvailable = policyCheckStatus.available,
                policyCheckError = policyCheckStatus.error,
                error = if (chain == network.chainName) {
                    null
                } else {
                    "Node must be on Dogecoin ${network.displayName}."
                }
            )
            status
        } catch (e: Exception) {
            DogecoinNodeStatus(
                connected = false,
                expectedNetwork = network,
                error = e.message ?: "Unable to reach Dogecoin ${network.displayName} node."
            )
        }
    }

    /**
     * DES-1-A provisioning only. This is deliberately not a general status/read API: after the user has
     * reviewed the exact-origin disclosure it issues exactly three fixed, non-mutating calls and returns
     * no balance, UTXO, activity, signing, or broadcast authority.
     */
    internal suspend fun probeTrustedPersonalNode(
        origin: String,
        credentials: DogecoinTrustedPersonalNodeCredentials,
        requestedWalletId: String,
        boundMainnetAddress: String
    ): DogecoinTrustedPersonalNodeProvisioningResult = withContext(Dispatchers.IO) {
        val exactOrigin = exactDogecoinTrustedPersonalNodeOriginOrNull(origin)
            ?: throw IllegalArgumentException(
                "Trusted personal node origin must be an exact lowercase Tailscale HTTPS origin."
            )
        require(DogecoinAddress.isValidP2pkhAddress(boundMainnetAddress, DogecoinNetwork.MAINNET)) {
            "Trusted personal node requires the active Android mainnet P2PKH address."
        }
        require(credentials.isValid()) {
            "Trusted personal node RPC username and password are required."
        }
        require(
            requestedWalletId.isEmpty() ||
                canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(requestedWalletId) == requestedWalletId
        ) {
            "Trusted personal node Core wallet name is not canonical."
        }

        val baseConfig = DogecoinRpcConfig(
            url = exactOrigin,
            username = credentials.username,
            password = credentials.password,
            walletName = requestedWalletId
        )

        // 1. TLS/auth are exercised by this first request; chain and IBD must be explicit and sane.
        val blockchainInfo = callObject(baseConfig.copy(walletName = ""), "getblockchaininfo")
        val chainElement = blockchainInfo.get("chain")?.takeUnless { it.isJsonNull }
            ?: throw IllegalArgumentException("RPC getblockchaininfo did not report chain.")
        val chainPrimitive = chainElement.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(chainPrimitive?.isString == true) {
            "RPC getblockchaininfo returned an invalid chain."
        }
        val chain = chainPrimitive.asString
        require(chain == DogecoinNetwork.MAINNET.chainName) {
            "Trusted personal node is on $chain, expected Dogecoin mainnet."
        }
        val initialBlockDownload = parseOptionalBoolean(
            blockchainInfo,
            "initialblockdownload",
            "getblockchaininfo"
        ) ?: throw IllegalArgumentException("RPC getblockchaininfo did not report initialblockdownload.")
        require(!initialBlockDownload) {
            "Trusted personal node is still in initial block download. Finish syncing before authorization."
        }

        // 2. Resolve one exact wallet identity. A mismatch stops before the phone address is disclosed.
        val walletInfo = callObject(baseConfig, "getwalletinfo")
        val walletElement = walletInfo.get("walletname")?.takeUnless { it.isJsonNull }
            ?: throw IllegalArgumentException("RPC getwalletinfo did not report walletname.")
        val walletPrimitive = walletElement.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(walletPrimitive?.isString == true) {
            "RPC getwalletinfo returned an invalid walletname."
        }
        val resolvedWalletId = walletPrimitive.asString
        require(canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(resolvedWalletId) == resolvedWalletId) {
            "RPC getwalletinfo returned a non-canonical walletname."
        }
        require(requestedWalletId.isEmpty() || requestedWalletId == resolvedWalletId) {
            "Trusted personal node returned a different Core wallet identity."
        }

        // 3. Always rebind to the resolved wallet endpoint before disclosing/checking the address.
        val validateResult = callObject(
            baseConfig.copy(walletName = resolvedWalletId),
            "validateaddress",
            JsonArray().apply { add(boundMainnetAddress) }
        )
        val isValid = parseOptionalBoolean(validateResult, "isvalid", "validateaddress")
            ?: throw IllegalArgumentException("RPC validateaddress did not report isvalid.")
        require(isValid) { "Dogecoin Core rejected the bound Android mainnet address." }
        val returnedAddressElement = validateResult.get("address")?.takeUnless { it.isJsonNull }
            ?: throw IllegalArgumentException("RPC validateaddress did not return the bound address.")
        val returnedAddressPrimitive = returnedAddressElement.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(returnedAddressPrimitive?.isString == true && returnedAddressPrimitive.asString == boundMainnetAddress) {
            "RPC validateaddress returned a different Dogecoin address."
        }
        val isMine = parseOptionalBoolean(validateResult, "ismine", "validateaddress")
            ?: throw IllegalArgumentException("RPC validateaddress did not report ismine.")
        val isWatchOnly = parseOptionalBoolean(validateResult, "iswatchonly", "validateaddress")
            ?: throw IllegalArgumentException("RPC validateaddress did not report iswatchonly.")
        require(!isMine) {
            "Trusted personal node reports ismine=true. Authorization stopped: the Android key must remain phone-only."
        }
        require(isWatchOnly) {
            "Trusted personal node does not report the Android address as watch-only."
        }

        DogecoinTrustedPersonalNodeProvisioningResult(
            origin = exactOrigin,
            network = DogecoinNetwork.MAINNET,
            androidAddress = boundMainnetAddress,
            coreWalletId = resolvedWalletId,
            chain = chain,
            watchStatus = DogecoinAddressWatchStatus(
                address = boundMainnetAddress,
                isMine = isMine,
                isWatchOnly = isWatchOnly
            )
        )
    }

    /**
     * DES-1-B's fixed readiness-and-display workflow. It intentionally does not reuse the generic wallet
     * balance/activity APIs because those ensure an import with `importaddress`. The authorized profile
     * already attests that its historical import/rescan was completed at the host console, and activation
     * rechecks the exact watch-only binding here without mutating Core.
     *
     * The only possible RPC methods are the literal calls visible below plus fixed `help` capability checks
     * for `testmempoolaccept` and the two approved previous-transaction sources. No caller can supply a
     * method name, transaction, outpoint, count, address, network, origin, or wallet that differs from the
     * durable profile.
     */
    internal suspend fun readTrustedPersonalNodeDisplaySnapshot(
        profile: DogecoinTrustedPersonalNodeProfile,
        credentials: DogecoinTrustedPersonalNodeCredentials
    ): DogecoinTrustedPersonalNodeDisplaySnapshot = withContext(Dispatchers.IO) {
        require(isValidDogecoinTrustedPersonalNodeProfile(profile)) {
            "Trusted personal node profile is invalid or no longer authorized."
        }
        require(profile.network == DogecoinNetwork.MAINNET) {
            "Trusted personal node reads are mainnet-only."
        }
        require(credentials.isValid()) {
            "Trusted personal node RPC credentials are unavailable."
        }

        val config = DogecoinRpcConfig(
            url = profile.origin,
            username = credentials.username,
            password = credentials.password,
            walletName = profile.coreWalletId
        )

        val blockchainInfo = callObject(config.copy(walletName = ""), "getblockchaininfo")
        val chain = requiredExactString(blockchainInfo, "chain", "getblockchaininfo")
        require(chain == DogecoinNetwork.MAINNET.chainName) {
            "Trusted personal node is on $chain, expected Dogecoin mainnet."
        }
        val initialBlockDownload = parseOptionalBoolean(
            blockchainInfo,
            "initialblockdownload",
            "getblockchaininfo"
        ) ?: throw IllegalArgumentException(
            "RPC getblockchaininfo did not report initialblockdownload."
        )
        require(!initialBlockDownload) {
            "Trusted personal node is still in initial block download."
        }
        val blocks = parseOptionalNonNegativeInt(blockchainInfo, "blocks", "getblockchaininfo")
            ?: throw IllegalArgumentException("RPC getblockchaininfo did not report blocks.")
        val headers = parseOptionalNonNegativeInt(blockchainInfo, "headers", "getblockchaininfo")
            ?: throw IllegalArgumentException("RPC getblockchaininfo did not report headers.")
        require(headers >= blocks) {
            "RPC getblockchaininfo returned headers below the current block height."
        }
        require(headers - blocks <= DOGECOIN_TPN_MAX_BLOCK_HEADER_LAG) {
            "Trusted personal node is more than $DOGECOIN_TPN_MAX_BLOCK_HEADER_LAG blocks behind its headers."
        }
        val verificationProgress = parseOptionalProgressFraction(
            blockchainInfo,
            "verificationprogress",
            "getblockchaininfo"
        ) ?: throw IllegalArgumentException(
            "RPC getblockchaininfo did not report verificationprogress."
        )
        require(verificationProgress >= DOGECOIN_TPN_MIN_VERIFICATION_PROGRESS) {
            "Trusted personal node verification progress is below the activation threshold."
        }

        val networkInfo = callObject(config.copy(walletName = ""), "getnetworkinfo")
        val networkActive = parseOptionalBoolean(networkInfo, "networkactive", "getnetworkinfo")
            ?: throw IllegalArgumentException("RPC getnetworkinfo did not report networkactive.")
        require(networkActive) { "Trusted personal node networking is disabled." }
        val peerCount = parseOptionalNonNegativeInt(networkInfo, "connections", "getnetworkinfo")
            ?: throw IllegalArgumentException("RPC getnetworkinfo did not report connections.")
        require(peerCount >= DOGECOIN_TPN_MIN_MAINNET_PEERS) {
            "Trusted personal node has $peerCount peers; at least $DOGECOIN_TPN_MIN_MAINNET_PEERS are required."
        }

        val walletInfo = callObject(config, "getwalletinfo")
        val returnedWalletId = requiredExactCoreWalletId(walletInfo)
        require(returnedWalletId == profile.coreWalletId) {
            "Trusted personal node returned a different Core wallet identity."
        }
        // Dogecoin Core versions that expose `scanning` report false when no rescan is active and an
        // object while scanning. Older versions may omit it, so the durable operator attestation plus
        // the mandatory watch-only recheck remains the v1 rescan-completion gate.
        walletInfo.get("scanning")?.takeUnless { it.isJsonNull }?.let { scanning ->
            val primitive = scanning.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
            require(primitive?.isBoolean == true && !primitive.asBoolean) {
                "Trusted personal node wallet reports an active or malformed rescan state."
            }
        }

        val validateAddress = callObject(
            config,
            "validateaddress",
            JsonArray().apply { add(profile.androidAddress) }
        )
        requireExactTrustedPersonalNodeWatchStatus(validateAddress, profile.androidAddress)

        require(isRpcMethodAvailable(config.copy(walletName = ""), "testmempoolaccept")) {
            "Trusted personal node does not provide testmempoolaccept."
        }
        val walletPreviousTransactionAvailable =
            isRpcMethodAvailable(config.copy(walletName = ""), "gettransaction")
        val rawPreviousTransactionAvailable = walletPreviousTransactionAvailable ||
            isRpcMethodAvailable(config.copy(walletName = ""), "getrawtransaction")
        require(rawPreviousTransactionAvailable) {
            "Trusted personal node does not provide an approved previous-transaction RPC."
        }

        val utxos = readTrustedPersonalNodeUnspent(config, profile)
        val activity = readTrustedPersonalNodeActivity(config, profile)
        DogecoinTrustedPersonalNodeDisplaySnapshot(
            profileRevision = profile.revision,
            origin = profile.origin,
            androidAddress = profile.androidAddress,
            coreWalletId = profile.coreWalletId,
            blocks = blocks,
            headers = headers,
            verificationProgress = verificationProgress,
            peerCount = peerCount,
            balance = DogecoinTrustedPersonalNodeDisplayBalance(
                confirmedKoinu = sumRpcUtxoAmounts(
                    utxos.filter { it.confirmations > 0 },
                    "confirmed trusted-node"
                ),
                unconfirmedKoinu = sumRpcUtxoAmounts(
                    utxos.filter { it.confirmations <= 0 },
                    "unconfirmed trusted-node"
                ),
                utxoCount = utxos.size
            ),
            activity = activity
        )
    }

    private fun requiredExactCoreWalletId(walletInfo: JsonObject): String {
        return requiredExactString(walletInfo, "walletname", "getwalletinfo").also { walletId ->
            require(canonicalDogecoinTrustedPersonalNodeWalletIdOrNull(walletId) == walletId) {
                "RPC getwalletinfo returned a non-canonical walletname."
            }
        }
    }

    private fun requireExactTrustedPersonalNodeWatchStatus(result: JsonObject, address: String) {
        val isValid = parseOptionalBoolean(result, "isvalid", "validateaddress")
            ?: throw IllegalArgumentException("RPC validateaddress did not report isvalid.")
        require(isValid) { "Dogecoin Core rejected the bound Android mainnet address." }
        val returnedAddress = requiredExactString(result, "address", "validateaddress")
        require(returnedAddress == address) {
            "RPC validateaddress returned a different Dogecoin address."
        }
        val isMine = parseOptionalBoolean(result, "ismine", "validateaddress")
            ?: throw IllegalArgumentException("RPC validateaddress did not report ismine.")
        val isWatchOnly = parseOptionalBoolean(result, "iswatchonly", "validateaddress")
            ?: throw IllegalArgumentException("RPC validateaddress did not report iswatchonly.")
        require(!isMine) {
            "Trusted personal node reports ismine=true. The Android key must remain phone-only."
        }
        require(isWatchOnly) {
            "Trusted personal node no longer reports the Android address as watch-only."
        }
    }

    private fun readTrustedPersonalNodeUnspent(
        config: DogecoinRpcConfig,
        profile: DogecoinTrustedPersonalNodeProfile
    ): List<DogecoinUtxo> {
        val result = callElement(
            config,
            "listunspent",
            JsonArray().apply {
                add(0)
                add(9_999_999)
                add(JsonArray().apply { add(profile.androidAddress) })
            }
        )
        require(result.isJsonArray) { "RPC listunspent response was not an array." }
        return result.asJsonArray.map { element ->
            require(element.isJsonObject) { "RPC listunspent returned a malformed UTXO row." }
            parseListUnspentItem(element.asJsonObject, profile.androidAddress, profile.network)
        }
    }

    private fun readTrustedPersonalNodeActivity(
        config: DogecoinRpcConfig,
        profile: DogecoinTrustedPersonalNodeProfile
    ): List<DogecoinWalletActivity> {
        val result = callElement(
            config,
            "listtransactions",
            JsonArray().apply {
                add("*")
                add(DOGECOIN_TPN_ACTIVITY_SCAN_COUNT)
                add(0)
                add(true)
            }
        )
        require(result.isJsonArray) { "RPC listtransactions response was not an array." }
        return result.asJsonArray.mapNotNull { element ->
            require(element.isJsonObject) {
                "RPC listtransactions returned a malformed activity row."
            }
            val item = element.asJsonObject
            if (exactListTransactionsAddressForFilter(item) != profile.androidAddress) {
                return@mapNotNull null
            }
            parseListTransactionsItem(item, profile.network)
        }
            .sortedWith(
                compareByDescending<DogecoinWalletActivity> { it.timeSeconds ?: Long.MIN_VALUE }
                    .thenByDescending { it.confirmations }
                    .thenBy { it.txid }
            )
            .take(DOGECOIN_TPN_ACTIVITY_DISPLAY_COUNT)
    }

    private fun requiredExactString(result: JsonObject, fieldName: String, method: String): String {
        val element = result.get(fieldName)?.takeUnless { it.isJsonNull }
            ?: throw IllegalArgumentException("RPC $method did not report $fieldName.")
        val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isString == true) {
            "RPC $method returned an invalid $fieldName."
        }
        return primitive.asString.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("RPC $method returned an invalid $fieldName.")
    }

    private fun exactListTransactionsAddressForFilter(item: JsonObject): String? {
        val value = item.get("address")?.takeUnless { it.isJsonNull } ?: return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.asString.takeIf { it.isNotBlank() }
    }

    suspend fun getWalletBalance(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): DogecoinWalletBalance =
        withContext(Dispatchers.IO) {
            val rpcConfig = normalizedRpcConfig(config, network)
            val utxos = listUnspentInternal(rpcConfig, address, network)
            DogecoinWalletBalance(
                confirmedKoinu = sumRpcUtxoAmounts(
                    utxos.filter { it.confirmations > 0 },
                    "confirmed"
                ),
                unconfirmedKoinu = sumRpcUtxoAmounts(
                    utxos.filter { it.confirmations <= 0 },
                    "unconfirmed"
                ),
                utxoCount = utxos.size,
                utxos = utxos.sortedWith(
                    compareByDescending<DogecoinUtxo> { it.confirmations }
                        .thenByDescending { it.amountKoinu }
                        .thenBy { it.txid }
                        .thenBy { it.vout }
                )
            )
        }

    suspend fun listUnspent(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): List<DogecoinUtxo> =
        withContext(Dispatchers.IO) {
            val rpcConfig = normalizedRpcConfig(config, network)
            listUnspentInternal(rpcConfig, address, network)
        }

    /**
     * READ-ONLY UTXO-set oracle: query a single outpoint via `gettxout`, returning its amount/script/
     * confirmations, or null when the node reports it spent or absent. `include_mempool=true` so a
     * mempool-spend is reflected (returns null) and a just-broadcast UTXO is still found. Unlike
     * `listunspent` this needs no wallet import — it hits the chainstate directly and works per-outpoint
     * on Dogecoin Core 1.14.x (which has no `scantxoutset`). Used only by the SPV cross-check; signs/
     * broadcasts nothing.
     */
    suspend fun getTxOut(
        config: DogecoinRpcConfig,
        txid: String,
        vout: Int,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): DogecoinTxOut? = withContext(Dispatchers.IO) {
        require(txidRegex.matches(txid.lowercase())) { "gettxout requires a 64-hex txid." }
        require(vout >= 0) { "gettxout requires a non-negative vout." }
        val rpcConfig = normalizedRpcConfig(config, network)
        val params = JsonArray().apply {
            add(txid.lowercase())
            add(vout)
            add(true) // include_mempool
        }
        val result = callElement(rpcConfig, "gettxout", params)
        if (result.isJsonNull) return@withContext null // spent or never existed
        require(result.isJsonObject) { "RPC gettxout response was not an object." }
        val obj = result.asJsonObject
        val amountKoinu = obj.get("value")
            ?.takeUnless { it.isJsonNull }
            ?.let { runCatching { dogeJsonToKoinu(it) }.getOrNull() }
            ?: throw IllegalArgumentException("RPC gettxout returned an output without a valid value.")
        require(amountKoinu > 0L) { "RPC gettxout returned a non-positive value." }
        val scriptObj = obj.get("scriptPubKey")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IllegalArgumentException("RPC gettxout returned no scriptPubKey object.")
        val scriptHex = parseOptionalString(scriptObj, "hex", "gettxout")?.lowercase()
            ?: throw IllegalArgumentException("RPC gettxout returned no scriptPubKey hex.")
        val confirmations = parseRequiredInt(
            obj,
            fieldName = "confirmations",
            invalidMessage = "RPC gettxout returned no valid confirmations."
        )
        DogecoinTxOut(amountKoinu = amountKoinu, scriptPubKeyHex = scriptHex, confirmations = confirmations)
    }

    suspend fun getAddressWatchStatus(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): DogecoinAddressWatchStatus =
        withContext(Dispatchers.IO) {
            val rpcConfig = normalizedRpcConfig(config, network)
            require(DogecoinAddress.isValidP2pkhAddress(address, network)) {
                "Wallet address is not a Dogecoin ${network.displayName} P2PKH address."
            }
            requireNetworkReady(rpcConfig, network)
            requireWalletRpcReady(rpcConfig)

            val result = callObject(
                rpcConfig,
                "validateaddress",
                JsonArray().apply { add(address) }
            )
            parseAddressWatchStatus(result, address)
        }

    suspend fun getWalletActivity(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT,
        count: Int = 20
    ): List<DogecoinWalletActivity> =
        withContext(Dispatchers.IO) {
            val rpcConfig = normalizedRpcConfig(config, network)
            require(DogecoinAddress.isValidP2pkhAddress(address, network)) {
                "Wallet address is not a Dogecoin ${network.displayName} P2PKH address."
            }
            requireNetworkReady(rpcConfig, network)
            requireWalletRpcReady(rpcConfig)
            importWatchAddress(rpcConfig, address, network)

            val requestedCount = count.coerceIn(1, 100)
            val scanCount = (requestedCount * 5).coerceAtMost(500)
            val params = JsonArray().apply {
                add("*")
                add(scanCount)
                add(0)
                add(true)
            }
            val result = callElement(rpcConfig, "listtransactions", params)
            require(result.isJsonArray) { "RPC listtransactions response was not an array." }
            result.asJsonArray.mapNotNull { element ->
                require(element.isJsonObject) { "RPC listtransactions returned a malformed activity row." }
                val item = element.asJsonObject
                if (listTransactionsAddressForFilter(item) != address) {
                    return@mapNotNull null
                }
                parseListTransactionsItem(item, network)
            }
                .sortedWith(
                    compareByDescending<DogecoinWalletActivity> { it.timeSeconds ?: Long.MIN_VALUE }
                        .thenByDescending { it.confirmations }
                        .thenBy { it.txid }
                )
                .take(requestedCount)
        }

    /**
     * Read-only confirmation observation for one just-broadcast wallet transaction. This never feeds coin
     * selection or signing; it exists so RPC/home-node confirmation UI can follow the effective backend.
     */
    internal suspend fun getTransactionConfirmations(
        config: DogecoinRpcConfig,
        txid: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): Int = withContext(Dispatchers.IO) {
        val rpcConfig = normalizedRpcConfig(config, network)
        val expectedTxid = txid.trim().lowercase()
        require(txidRegex.matches(expectedTxid)) {
            "Dogecoin transaction id must be exactly 64 hexadecimal characters."
        }
        requireNetworkReady(rpcConfig, network)

        val result = callObject(
            rpcConfig,
            "gettransaction",
            JsonArray().apply {
                add(expectedTxid)
                add(true)
            }
        )
        val returnedTxid = parseOptionalString(result, "txid", "gettransaction")
            ?.trim()
            ?.lowercase()
            ?: throw IllegalArgumentException("RPC gettransaction returned no valid txid.")
        require(returnedTxid == expectedTxid) {
            "RPC gettransaction returned a different txid."
        }
        parseOptionalNonNegativeInt(result, "confirmations", "gettransaction")
            ?: throw IllegalArgumentException("RPC gettransaction returned no valid confirmations.")
    }

    suspend fun sendRawTransaction(
        config: DogecoinRpcConfig,
        rawTransactionHex: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): String =
        withContext(Dispatchers.IO) {
            requireDogecoinGenericRpcSpendAllowed(network)
            val rpcConfig = normalizedRpcConfig(config, network)
            val normalizedRawTransactionHex = normalizedRawTransactionHex(rawTransactionHex)
            requireNetworkReady(rpcConfig, network)
            val relayStatus = requireRelayReady(rpcConfig, network)
            validateRawTransactionHex(
                normalizedRawTransactionHex,
                relayStatus.softDustLimitKoinu ?: DogecoinProtocol.MIN_STANDARD_OUTPUT_KOINU
            )
            val mempoolAcceptance = testMempoolAcceptanceInternal(rpcConfig, normalizedRawTransactionHex)
            check(!mempoolAcceptance.checked || mempoolAcceptance.allowed == true) {
                mempoolAcceptance.error ?: "Dogecoin node policy rejected this signed transaction."
            }
            val rpcTxid = parseRequiredResultString(
                callElement(
                    rpcConfig,
                    "sendrawtransaction",
                    JsonArray().apply { add(normalizedRawTransactionHex) },
                    // Once submitted, canceling the response wait cannot revoke the transaction and can create
                    // dangerous accepted-but-unknown retry ambiguity. Normal UI dismissal is already disabled
                    // while sending, so let this one money-path call finish; all preparatory/read RPCs cancel.
                    cancelOnOwnerDispose = false
                ),
                method = "sendrawtransaction",
                invalidMessage = "RPC sendrawtransaction returned an invalid txid."
            )
            verifiedBroadcastTxid(normalizedRawTransactionHex, rpcTxid)
        }

    suspend fun testMempoolAcceptance(
        config: DogecoinRpcConfig,
        rawTransactionHex: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): DogecoinMempoolAcceptance =
        withContext(Dispatchers.IO) {
            requireDogecoinGenericRpcSpendAllowed(network)
            val rpcConfig = normalizedRpcConfig(config, network)
            val normalizedRawTransactionHex = normalizedRawTransactionHex(rawTransactionHex)
            requireNetworkReady(rpcConfig, network)
            testMempoolAcceptanceInternal(rpcConfig, normalizedRawTransactionHex)
        }

    suspend fun rescanWalletHistory(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT,
        startHeight: Int? = null
    ): DogecoinRescanResult =
        withContext(Dispatchers.IO) {
            val rpcConfig = normalizedRpcConfig(config, network)
            require(startHeight == null || startHeight >= 0) {
                "Dogecoin rescan start height must be non-negative."
            }
            require(DogecoinAddress.isValidP2pkhAddress(address, network)) {
                "Wallet address is not a Dogecoin ${network.displayName} P2PKH address."
            }
            val blockchainInfo = requireNetworkReady(rpcConfig, network)
            require(parseOptionalBoolean(blockchainInfo, "pruned", "getblockchaininfo") != true) {
                "Dogecoin ${network.displayName} node is pruned. Historical rescan requires an unpruned node."
            }
            requireWalletRpcReady(rpcConfig)

            if (isRpcMethodAvailable(rpcConfig, "rescanblockchain")) {
                importWatchAddress(rpcConfig, address, network)
                val result = callObject(
                    config = rpcConfig,
                    method = "rescanblockchain",
                    params = JsonArray().apply {
                        if (startHeight != null) add(startHeight)
                    },
                    longRunning = true
                )
                parseRescanResult(result)
            } else {
                importWatchAddressWithHistoricalRescan(rpcConfig, address, network, startHeight)
                DogecoinRescanResult(
                    startHeight = startHeight,
                    stopHeight = null
                )
            }
        }

    private fun listUnspentInternal(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork
    ): List<DogecoinUtxo> {
        require(DogecoinAddress.isValidP2pkhAddress(address, network)) {
            "Wallet address is not a Dogecoin ${network.displayName} P2PKH address."
        }
        requireNetworkReady(config, network)
        requireWalletRpcReady(config)
        importWatchAddress(config, address, network)

        val addresses = JsonArray().apply { add(address) }
        val params = JsonArray().apply {
            add(0)
            add(9_999_999)
            add(addresses)
        }

        val result = callElement(config, "listunspent", params)
        require(result.isJsonArray) { "RPC listunspent response was not an array." }
        return result.asJsonArray.map { element ->
            require(element.isJsonObject) { "RPC listunspent returned a malformed UTXO row." }
            parseListUnspentItem(element.asJsonObject, address, network)
        }
    }

    private fun parseListUnspentItem(
        item: JsonObject,
        address: String,
        network: DogecoinNetwork
    ): DogecoinUtxo {
        val txid = parseOptionalString(item, "txid", "listunspent")
            ?.trim()
            ?.lowercase()
            ?: throw IllegalArgumentException("RPC listunspent returned a UTXO without a txid.")
        require(txidRegex.matches(txid)) {
            "RPC listunspent returned an invalid Dogecoin txid."
        }

        val vout = parseRequiredInt(
            item,
            fieldName = "vout",
            invalidMessage = "RPC listunspent returned a UTXO without a valid vout."
        )
        require(vout >= 0) {
            "RPC listunspent returned a UTXO with a negative vout."
        }

        val amountKoinu = item.get("amount")
            ?.takeUnless { it.isJsonNull }
            ?.let { runCatching { dogeJsonToKoinu(it) }.getOrNull() }
            ?: throw IllegalArgumentException("RPC listunspent returned a UTXO without a valid amount.")
        require(amountKoinu > 0L) {
            "RPC listunspent returned a UTXO with a non-positive amount."
        }

        val confirmations = parseRequiredInt(
            item,
            fieldName = "confirmations",
            invalidMessage = "RPC listunspent returned a UTXO without valid confirmations."
        )
        require(confirmations >= 0) {
            "RPC listunspent returned a UTXO with negative confirmations."
        }

        val scriptPubKey = parseOptionalString(item, "scriptPubKey", "listunspent")
            ?.trim()
            ?.lowercase()
            ?: throw IllegalArgumentException("RPC listunspent returned a UTXO without a scriptPubKey.")
        val scriptBytes = runCatching { DogecoinHex.decode(scriptPubKey) }.getOrNull()
            ?: throw IllegalArgumentException("RPC listunspent returned an invalid scriptPubKey hex.")
        require(scriptBytes.isNotEmpty()) {
            "RPC listunspent returned an empty scriptPubKey."
        }

        val expectedScriptPubKey = DogecoinHex.encode(DogecoinAddress.p2pkhScript(address, network))
        require(scriptPubKey == expectedScriptPubKey) {
            "RPC listunspent returned an output script that does not match this wallet address."
        }

        return DogecoinUtxo(
            txid = txid,
            vout = vout,
            amountKoinu = amountKoinu,
            scriptPubKeyHex = scriptPubKey,
            confirmations = confirmations
        )
    }

    private fun parseAddressWatchStatus(result: JsonObject, address: String): DogecoinAddressWatchStatus {
        val isValid = parseOptionalBoolean(result, "isvalid", "validateaddress")
            ?: throw IllegalArgumentException("RPC validateaddress did not report isvalid.")
        require(isValid) {
            "Dogecoin Core validateaddress rejected this wallet address."
        }
        parseOptionalString(result, "address", "validateaddress")?.let { validatedAddress ->
            require(validatedAddress == address) {
                "RPC validateaddress returned a different Dogecoin address."
            }
        }
        return DogecoinAddressWatchStatus(
            address = address,
            isMine = parseOptionalBoolean(result, "ismine", "validateaddress") ?: false,
            isWatchOnly = parseOptionalBoolean(result, "iswatchonly", "validateaddress") ?: false
        )
    }

    private fun sumRpcUtxoAmounts(utxos: List<DogecoinUtxo>, balanceType: String): Long {
        return utxos.fold(0L) { total, utxo ->
            try {
                Math.addExact(total, utxo.amountKoinu)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException(
                    "RPC listunspent returned a $balanceType Dogecoin balance total that is too large."
                )
            }
        }
    }

    private fun listTransactionsAddressForFilter(item: JsonObject): String? {
        val value = item.get("address")?.takeUnless { it.isJsonNull } ?: return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.asString.trim().takeIf { it.isNotBlank() }
    }

    private fun parseListTransactionsItem(
        item: JsonObject,
        network: DogecoinNetwork
    ): DogecoinWalletActivity {
        val txid = parseOptionalString(item, "txid", "listtransactions")
            ?.trim()
            ?.lowercase()
            ?: throw IllegalArgumentException("RPC listtransactions returned an activity row without a txid.")
        require(txidRegex.matches(txid)) {
            "RPC listtransactions returned an invalid Dogecoin txid."
        }

        val category = parseOptionalString(item, "category", "listtransactions")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

        val activityAddress = parseOptionalString(item, "address", "listtransactions")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        require(activityAddress == null || DogecoinAddress.isValidAddress(activityAddress, network)) {
            "RPC listtransactions returned an activity address for the wrong Dogecoin network."
        }

        val amountKoinu = item.get("amount")
            ?.takeUnless { it.isJsonNull }
            ?.let { runCatching { dogeJsonToKoinu(it) }.getOrNull() }
            ?: throw IllegalArgumentException("RPC listtransactions returned an activity row without a valid amount.")

        val feeKoinu = item.get("fee")
            ?.takeUnless { it.isJsonNull }
            ?.let {
                runCatching { dogeJsonToKoinu(it) }.getOrNull()
                    ?: throw IllegalArgumentException("RPC listtransactions returned an activity row without a valid fee.")
            }

        val confirmations = parseRequiredInt(
            item,
            fieldName = "confirmations",
            invalidMessage = "RPC listtransactions returned an activity row without valid confirmations."
        )

        val timeSeconds = item.get("time")
            ?.takeUnless { it.isJsonNull }
            ?.let {
                val parsedTime = runCatching { exactLong(it) }.getOrNull()
                    ?: throw IllegalArgumentException("RPC listtransactions returned an activity row without a valid time.")
                require(parsedTime >= 0L) {
                    "RPC listtransactions returned an activity row with a negative time."
                }
                parsedTime
            }

        val involvesWatchOnly = parseOptionalBoolean(item, "involvesWatchonly", "listtransactions") ?: false

        return DogecoinWalletActivity(
            txid = txid,
            category = category,
            address = activityAddress,
            amountKoinu = amountKoinu,
            feeKoinu = feeKoinu,
            confirmations = confirmations,
            timeSeconds = timeSeconds,
            involvesWatchOnly = involvesWatchOnly
        )
    }

    private fun parseRescanResult(result: JsonObject): DogecoinRescanResult {
        val startHeight = parseOptionalRescanHeight(result, "start_height")
        val stopHeight = parseOptionalRescanHeight(result, "stop_height")
        require(startHeight == null || stopHeight == null || stopHeight >= startHeight) {
            "RPC rescanblockchain returned a stop height before the start height."
        }
        return DogecoinRescanResult(
            startHeight = startHeight,
            stopHeight = stopHeight
        )
    }

    private fun parseOptionalRescanHeight(result: JsonObject, fieldName: String): Int? {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull } ?: return null
        val height = runCatching { exactInt(value) }.getOrNull()
            ?: throw IllegalArgumentException("RPC rescanblockchain returned an invalid $fieldName.")
        require(height >= 0) {
            "RPC rescanblockchain returned a negative $fieldName."
        }
        return height
    }

    private fun importWatchAddress(config: DogecoinRpcConfig, address: String, network: DogecoinNetwork) {
        try {
            callImportWatchAddress(config, address, network, rescan = false, startHeight = null)
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (!isAlreadyImportedWatchAddress(message)) {
                throw e
            }
        }
    }

    private fun importWatchAddressWithHistoricalRescan(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork,
        startHeight: Int?
    ) {
        try {
            callImportWatchAddress(config, address, network, rescan = true, startHeight = startHeight)
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (!isAlreadyImportedWatchAddress(message)) {
                throw e
            }
            throw IllegalStateException(
                "Dogecoin Core rescanblockchain is unavailable, and this address is already imported. " +
                    "Older Dogecoin Core can only rescan an address while importing it. Use a newer unpruned " +
                    "Dogecoin Core node with rescanblockchain, or use a fresh Core wallet and start the rescan " +
                    "before refreshing this address."
            )
        }
    }

    private fun callImportWatchAddress(
        config: DogecoinRpcConfig,
        address: String,
        network: DogecoinNetwork,
        rescan: Boolean,
        startHeight: Int?
    ) {
        callElement(
            config,
            "importaddress",
            JsonArray().apply {
                add(address)
                add(watchAddressLabel(network))
                add(rescan)
                if (rescan && startHeight != null) {
                    add(false)
                    add(startHeight)
                }
            },
            longRunning = rescan
        )
    }

    private fun isAlreadyImportedWatchAddress(message: String): Boolean {
        val normalizedMessage = message.lowercase()
        if (!normalizedMessage.contains("importaddress")) return false
        return normalizedMessage.contains("wallet already contains") ||
            normalizedMessage.contains("already contains the private key") ||
            normalizedMessage.contains("already contains the address") ||
            normalizedMessage.contains("already contains the script") ||
            normalizedMessage.contains("already have the private key") ||
            normalizedMessage.contains("already have the script")
    }

    private fun getWalletInfoStatus(config: DogecoinRpcConfig): WalletInfoStatus {
        return try {
            val result = callObject(config, "getwalletinfo")
            WalletInfoStatus(
                ready = true,
                walletName = parseOptionalString(result, "walletname", "getwalletinfo")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() },
                loadedWalletNames = emptyList(),
                error = null
            )
        } catch (e: Exception) {
            val error = e.message ?: "Dogecoin Core wallet RPC is unavailable."
            val includeLoadedWalletGuidance = shouldAddLoadedWalletGuidance(error)
            val loadedWalletNames = if (includeLoadedWalletGuidance) {
                getLoadedWalletNames(config)
            } else {
                null
            }
            WalletInfoStatus(
                ready = false,
                walletName = null,
                loadedWalletNames = loadedWalletNames.orEmpty(),
                error = if (includeLoadedWalletGuidance) {
                    walletUnavailableMessage(error, loadedWalletNames)
                } else {
                    error
                }
            )
        }
    }

    private fun shouldAddLoadedWalletGuidance(error: String): Boolean {
        val normalizedError = error.lowercase()
        return normalizedError.contains("wallet") &&
            (
                normalizedError.contains("not loaded") ||
                    normalizedError.contains("does not exist") ||
                    normalizedError.contains("not found")
                )
    }

    private fun getLoadedWalletNames(config: DogecoinRpcConfig): List<String>? {
        return runCatching {
            val result = callElement(config, "listwallets")
            require(result.isJsonArray) { "RPC listwallets response was not an array." }
            result.asJsonArray.mapNotNull { element ->
                val primitive = element.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                require(primitive?.isString == true) {
                    "RPC listwallets returned an invalid wallet name."
                }
                primitive.asString.trim().trim('/').takeIf { it.isNotBlank() }
            }.distinct()
        }.getOrElse { null }
    }

    private fun walletUnavailableMessage(error: String, loadedWalletNames: List<String>?): String {
        val baseError = error.trim().ifEmpty { "Dogecoin Core wallet RPC is unavailable." }
        return buildString {
            append(baseError)
            append(' ')
            when {
                loadedWalletNames == null -> {
                    append("Open or create a wallet in Dogecoin Core. For multiwallet nodes, enter the wallet name in the Wallet name field and keep the RPC URL on the node base endpoint.")
                }
                loadedWalletNames.isNotEmpty() -> {
                    append("Loaded Dogecoin Core wallets: ")
                    append(loadedWalletNames.take(5).joinToString(", ") { displayWalletName(it) })
                    if (loadedWalletNames.size > 5) {
                        append(", ...")
                    }
                    append(". Enter the matching name in the Wallet name field and keep the RPC URL on the node base endpoint.")
                }
                else -> {
                    append("Dogecoin Core did not report any loaded wallets. Open or create a wallet in Dogecoin Core, then use the Wallet name field for multiwallet nodes.")
                }
            }
        }
    }

    private fun displayWalletName(walletName: String): String {
        return walletName.replace(Regex("\\s+"), " ").take(80)
    }

    private fun requireWalletRpcReady(config: DogecoinRpcConfig) {
        val status = getWalletInfoStatus(config)
        check(status.ready == true) {
            status.error ?: "Dogecoin Core wallet RPC is unavailable."
        }
    }

    private fun getRelayInfoStatus(config: DogecoinRpcConfig, network: DogecoinNetwork): RelayInfoStatus {
        return try {
            val result = callObject(config, "getnetworkinfo")
            val networkActive = parseOptionalBoolean(result, "networkactive", "getnetworkinfo")
            val peerCount = parseOptionalNonNegativeInt(result, "connections", "getnetworkinfo")
            val relayFeePerKbKoinu = parseOptionalPositiveKoinu(result, "relayfee", "getnetworkinfo")
            val incrementalFeePerKbKoinu = parseOptionalPositiveKoinu(result, "incrementalfee", "getnetworkinfo")
            val softDustLimitKoinu = parseOptionalPositiveKoinu(result, "softdustlimit", "getnetworkinfo")
            val hardDustLimitKoinu = parseOptionalPositiveKoinu(result, "harddustlimit", "getnetworkinfo")
            require(softDustLimitKoinu == null || hardDustLimitKoinu == null || softDustLimitKoinu >= hardDustLimitKoinu) {
                "RPC getnetworkinfo returned a softdustlimit below harddustlimit."
            }
            // Regtest is intentionally peerless: it accepts transactions into its own mempool with
            // no connections, so do not require relay peers there. Mainnet/testnet are unchanged.
            val peersReady = network == DogecoinNetwork.REGTEST || (peerCount != null && peerCount > 0)
            val ready = networkActive != false && peersReady
            RelayInfoStatus(
                ready = ready,
                networkActive = networkActive,
                peerCount = peerCount,
                relayFeePerKbKoinu = relayFeePerKbKoinu,
                incrementalFeePerKbKoinu = incrementalFeePerKbKoinu,
                softDustLimitKoinu = softDustLimitKoinu,
                hardDustLimitKoinu = hardDustLimitKoinu,
                error = if (ready) {
                    null
                } else if (networkActive == false) {
                    "Dogecoin Core networking is disabled."
                } else if (peerCount == null) {
                    "Dogecoin Core did not report connected peers for broadcast relay."
                } else {
                    "Dogecoin Core has no connected peers for broadcast relay."
                }
            )
        } catch (e: Exception) {
            RelayInfoStatus(
                ready = false,
                networkActive = null,
                peerCount = null,
                relayFeePerKbKoinu = null,
                incrementalFeePerKbKoinu = null,
                softDustLimitKoinu = null,
                hardDustLimitKoinu = null,
                error = e.message ?: "Dogecoin Core network RPC is unavailable."
            )
        }
    }

    private fun requireRelayReady(config: DogecoinRpcConfig, network: DogecoinNetwork): RelayInfoStatus {
        val status = getRelayInfoStatus(config, network)
        check(status.ready == true) {
            status.error ?: "Dogecoin Core cannot relay broadcasts right now."
        }
        return status
    }

    private fun getMempoolPolicyCheckStatus(config: DogecoinRpcConfig): PolicyCheckStatus {
        return try {
            val available = isRpcMethodAvailable(config, "testmempoolaccept")
            PolicyCheckStatus(
                available = available,
                error = if (available) {
                    null
                } else {
                    "Dogecoin Core testmempoolaccept is unavailable on this node. Mainnet signed transactions require an extra acknowledgement and final broadcast acceptance."
                }
            )
        } catch (e: Exception) {
            val message = e.message ?: "Dogecoin Core testmempoolaccept availability could not be checked."
            PolicyCheckStatus(
                available = false,
                error = if (isUnsupportedMempoolAcceptance(message)) {
                    "Dogecoin Core testmempoolaccept is unavailable on this node. Mainnet signed transactions require an extra acknowledgement and final broadcast acceptance."
                } else {
                    "Dogecoin Core testmempoolaccept availability could not be verified: $message"
                }
            )
        }
    }

    private fun getRescanBlockchainStatus(config: DogecoinRpcConfig): RescanBlockchainStatus {
        return try {
            val available = isRpcMethodAvailable(config, "rescanblockchain")
            RescanBlockchainStatus(
                available = available,
                error = if (available) {
                    null
                } else {
                    "Dogecoin Core rescanblockchain is unavailable on this node. Historical rescans use importaddress and must run before the first balance refresh for this address."
                }
            )
        } catch (e: Exception) {
            val message = e.message ?: "Dogecoin Core rescanblockchain availability could not be checked."
            RescanBlockchainStatus(
                available = false,
                error = if (isUnsupportedRpcMethod(message, "rescanblockchain")) {
                    "Dogecoin Core rescanblockchain is unavailable on this node. Historical rescans use importaddress and must run before the first balance refresh for this address."
                } else {
                    "Dogecoin Core rescanblockchain availability could not be verified: $message"
                }
            )
        }
    }

    private fun isRpcMethodAvailable(config: DogecoinRpcConfig, method: String): Boolean {
        return try {
            val result = callElement(
                config,
                "help",
                JsonArray().apply { add(method) }
            )
            val helpText = parseRequiredResultString(
                result = result,
                method = "help",
                invalidMessage = "RPC help $method returned an invalid result."
            )
            if (isUnsupportedRpcMethod(helpText, method)) {
                return false
            }
            helpText.contains(method, ignoreCase = true)
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (isUnsupportedRpcMethod(message, method)) {
                false
            } else {
                throw e
            }
        }
    }

    private fun normalizedRpcConfig(config: DogecoinRpcConfig, network: DogecoinNetwork): DogecoinRpcConfig {
        val normalized = config.normalized(network)
        require(normalized.hasValidUrl(network)) {
            "Enter a valid Dogecoin RPC URL. Use HTTPS for public hosts, or HTTP only for local, private, or .local node addresses."
        }
        return normalized
    }

    private fun requireNetworkReady(config: DogecoinRpcConfig, network: DogecoinNetwork): JsonObject {
        val result = callObject(config, "getblockchaininfo")
        val chain = parseOptionalString(result, "chain", "getblockchaininfo")
        require(chain == network.chainName) {
            "Configured Dogecoin node is on ${chain ?: "unknown"}, expected ${network.displayName}."
        }

        val initialBlockDownload = parseOptionalBoolean(
            result,
            fieldName = "initialblockdownload",
            method = "getblockchaininfo"
        ) ?: false
        require(!initialBlockDownload) {
            "Dogecoin ${network.displayName} node is still syncing. Wait for initial block download to finish before using wallet balance or sending."
        }
        return result
    }

    private fun watchAddressLabel(network: DogecoinNetwork): String {
        return "bitchat-dogecoin-${network.id}"
    }

    private fun normalizedRawTransactionHex(rawTransactionHex: String): String =
        DogecoinRawTxValidator.normalize(rawTransactionHex)

    private fun validateRawTransactionHex(rawTransactionHex: String, minimumOutputKoinu: Long) =
        DogecoinRawTxValidator.validateHex(rawTransactionHex, minimumOutputKoinu)

    private fun verifiedBroadcastTxid(rawTransactionHex: String, rpcTxid: String): String =
        DogecoinRawTxValidator.verifyBroadcastTxid(rawTransactionHex, rpcTxid)

    private fun testMempoolAcceptanceInternal(
        config: DogecoinRpcConfig,
        rawTransactionHex: String
    ): DogecoinMempoolAcceptance {
        return try {
            val rawTransactions = JsonArray().apply { add(rawTransactionHex) }
            val result = callElement(
                config,
                "testmempoolaccept",
                JsonArray().apply { add(rawTransactions) }
            )
            require(result.isJsonArray && result.asJsonArray.size() == 1) {
                "RPC testmempoolaccept response did not include one transaction result."
            }

            val item = result.asJsonArray[0].asJsonObject
            val expectedTxid = DogecoinTransactionBuilder.transactionId(rawTransactionHex)
            val nodeTxid = parseOptionalString(item, "txid", "testmempoolaccept")
                ?.trim()
                ?.lowercase()
            if (!nodeTxid.isNullOrBlank()) {
                require(txidRegex.matches(nodeTxid)) {
                    "RPC testmempoolaccept returned an invalid Dogecoin txid."
                }
                require(nodeTxid == expectedTxid) {
                    "RPC testmempoolaccept txid did not match the signed Dogecoin transaction."
                }
            }

            val allowed = parseOptionalBoolean(item, "allowed", "testmempoolaccept") ?: false
            val rejectReason = parseOptionalString(item, "reject-reason", "testmempoolaccept")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: parseOptionalString(item, "reject_reason", "testmempoolaccept")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            DogecoinMempoolAcceptance(
                checked = true,
                allowed = allowed,
                txid = nodeTxid ?: expectedTxid,
                rejectReason = rejectReason,
                error = if (allowed) null else mempoolRejectionMessage(rejectReason)
            )
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (isUnsupportedMempoolAcceptance(message)) {
                DogecoinMempoolAcceptance(
                    checked = false,
                    allowed = null,
                    error = "Dogecoin Core testmempoolaccept is unavailable on this node."
                )
            } else {
                throw e
            }
        }
    }

    private fun dogeJsonToKoinu(value: JsonElement): Long {
        return exactDecimal(value)
            .movePointRight(8)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    }

    private fun parseRequiredInt(
        result: JsonObject,
        fieldName: String,
        invalidMessage: String
    ): Int {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull }
            ?: throw IllegalArgumentException(invalidMessage)
        return runCatching { exactInt(value) }.getOrNull()
            ?: throw IllegalArgumentException(invalidMessage)
    }

    private fun parseOptionalNonNegativeInt(result: JsonObject, fieldName: String, method: String): Int? {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull } ?: return null
        val parsed = runCatching {
            exactInt(value)
        }.getOrNull()
            ?: throw IllegalArgumentException("RPC $method returned an invalid $fieldName.")
        require(parsed >= 0) {
            "RPC $method returned a negative $fieldName."
        }
        return parsed
    }

    private fun parseOptionalPositiveKoinu(result: JsonObject, fieldName: String, method: String): Long? {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull } ?: return null
        val parsed = runCatching { dogeJsonToKoinu(value) }.getOrNull()
            ?: throw IllegalArgumentException("RPC $method returned an invalid $fieldName.")
        require(parsed > 0L) {
            "RPC $method returned a non-positive $fieldName."
        }
        return parsed
    }

    /**
     * Parses a display-only progress fraction. Dogecoin Core 1.14 can report a tiny floating-point
     * overshoot above 1.0 at tip, so plausible finite values are clamped for storage/display. This helper
     * must not be reused for monetary amounts, which stay on the exact decimal-to-koinu path.
     */
    private fun parseOptionalProgressFraction(
        result: JsonObject,
        fieldName: String,
        method: String
    ): Double? {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull } ?: return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isNumber == true) {
            "RPC $method returned an invalid $fieldName."
        }
        val parsed = runCatching { primitive.asDouble }.getOrNull()
            ?: throw IllegalArgumentException("RPC $method returned an invalid $fieldName.")
        require(parsed.isFinite()) {
            "RPC $method returned a non-finite $fieldName."
        }
        require(parsed in 0.0..2.0) {
            "RPC $method returned an out-of-range $fieldName."
        }
        return parsed.coerceIn(0.0, 1.0)
    }

    private fun parseOptionalBoolean(result: JsonObject, fieldName: String, method: String): Boolean? {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull } ?: return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        return if (primitive?.isBoolean == true) {
            primitive.asBoolean
        } else {
            throw IllegalArgumentException("RPC $method returned an invalid $fieldName.")
        }
    }

    private fun parseOptionalString(result: JsonObject, fieldName: String, method: String): String? {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull } ?: return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isString == true) {
            "RPC $method returned an invalid $fieldName."
        }
        return primitive.asString.trim().takeIf { it.isNotBlank() }
    }

    private fun parseRequiredResultString(
        result: JsonElement,
        method: String,
        invalidMessage: String
    ): String {
        val primitive = result.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isString == true) {
            invalidMessage
        }
        return primitive.asString.trim().takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException(invalidMessage)
    }

    private fun exactDecimal(value: JsonElement): BigDecimal {
        require(value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.isNumber == true) {
            "JSON value must be a number."
        }
        return BigDecimal(value.asString)
    }

    private fun exactInt(value: JsonElement): Int {
        require(value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.isNumber == true) {
            "JSON value must be a number."
        }
        return exactDecimal(value)
            .setScale(0, RoundingMode.UNNECESSARY)
            .intValueExact()
    }

    private fun exactLong(value: JsonElement): Long {
        require(value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.isNumber == true) {
            "JSON value must be a number."
        }
        return exactDecimal(value)
            .setScale(0, RoundingMode.UNNECESSARY)
            .longValueExact()
    }

    private fun callObject(
        config: DogecoinRpcConfig,
        method: String,
        params: JsonArray = JsonArray(),
        longRunning: Boolean = false
    ): JsonObject {
        val result = callElement(config, method, params, longRunning)
        require(result.isJsonObject) { "RPC response for $method did not include an object result." }
        return result.asJsonObject
    }

    private fun callElement(
        config: DogecoinRpcConfig,
        method: String,
        params: JsonArray = JsonArray(),
        longRunning: Boolean = false,
        cancelOnOwnerDispose: Boolean = true
    ): JsonElement {
        // Route-policy chokepoint: no request object is even built for an untrusted endpoint, so every
        // caller (wallet UI, broadcast helper, debug console) inherits the same trust classification.
        requireTrustedDogecoinRpcRoute(config)
        // A wallet workflow may carry a generation-specific lease. Saving/switching/stopping the route
        // revokes it synchronously, preventing a multi-call method from issuing its next HTTP request.
        beforeRequest()

        val requestJson = JsonObject().apply {
            addProperty("jsonrpc", "1.0")
            addProperty("id", "bitchat-dogecoin")
            addProperty("method", method)
            add("params", params)
        }

        val requestBuilder = Request.Builder()
            .url(rpcUrlForMethod(config, method))
            .post(gson.toJson(requestJson).toRequestBody("application/json".toMediaType()))

        if (config.username.isNotBlank() || config.password.isNotBlank()) {
            requestBuilder.header("Authorization", Credentials.basic(config.username, config.password))
        }

        val client = if (longRunning) rescanHttpClient else httpClient
        val call = client.newCall(requestBuilder.build())
        if (cancelOnOwnerDispose) activeCalls.register(call)
        try {
            call.execute().use { response ->
                // Redirect and authentication status are transport-policy results regardless of body content.
                // Check them before parsing so 3xx never forwards credentials and 401/403 stay typed.
                if (response.code in 300..399 || response.code == 401 || response.code == 403) {
                    throw DogecoinRpcHttpException(
                        response.code,
                        httpRpcErrorMessage(method, response.code, response.message)
                    )
                }
                // Authentication status owns the TPN transition even if a proxy supplies attacker-controlled
                // JSON or an oversized body. Do not let body parsing collapse 401/403 into generic degradation.
                if (response.code == 401 || response.code == 403) {
                    throw DogecoinRpcHttpException(
                        response.code,
                        httpRpcErrorMessage(method, response.code, response.message)
                    )
                }
                val body = readBoundedRpcBody(response, method)
                // Dogecoin Core returns JSON-RPC errors with HTTP 500 and the structured error in the
                // body, so the body must be inspected before the HTTP status. Otherwise every node-level
                // error (insufficient fee, missing inputs, already-imported watch address, unknown method)
                // collapses to a generic "HTTP 500" and the specific guidance/recovery handling is skipped.
                val json = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
                val error = json?.get("error")?.takeUnless { it.isJsonNull }
                if (error != null) {
                    throw IllegalStateException(rpcErrorMessage(method, error))
                }
                if (!response.isSuccessful) {
                    throw DogecoinRpcHttpException(
                        response.code,
                        httpRpcErrorMessage(method, response.code, response.message)
                    )
                }

                return json?.get("result") ?: JsonNull.INSTANCE
            }
        } finally {
            if (cancelOnOwnerDispose) activeCalls.unregister(call)
        }
    }

    /**
     * Read the response body with a hard size cap. A node/gateway response larger than
     * [DOGECOIN_RPC_MAX_RESPONSE_BYTES] aborts the call instead of buffering unbounded data — the cap
     * comfortably fits every RPC this client issues (listunspent/activity for one address included).
     */
    private fun readBoundedRpcBody(response: okhttp3.Response, method: String): String {
        val source = response.body?.source() ?: return ""
        if (source.request(DOGECOIN_RPC_MAX_RESPONSE_BYTES + 1)) {
            throw IllegalStateException(
                "Dogecoin RPC $method response exceeded ${DOGECOIN_RPC_MAX_RESPONSE_BYTES / (1024 * 1024)} MB and was refused."
            )
        }
        return source.readUtf8()
    }

    private fun httpRpcErrorMessage(method: String, code: Int, message: String): String {
        return when (code) {
            in 300..399 -> "Dogecoin RPC $method was redirected (HTTP $code). Redirects are never followed, so RPC " +
                "credentials are not forwarded; point the app directly at the node's real address."
            401 -> "Dogecoin RPC authentication failed for $method. Check the RPC username and password. " +
                "If Dogecoin Core is using cookie auth for dogecoin-cli, configure rpcuser/rpcpassword or rpcauth " +
                "for this node and enter those credentials in the app."
            403 -> "Dogecoin RPC rejected $method. Check Dogecoin Core rpcallowip, rpcbind, and wallet RPC access."
            404 -> "Dogecoin RPC endpoint was not found for $method. " +
                "If using a named Dogecoin Core wallet, set the wallet name in the app or include /wallet/<name> in the RPC URL."
            else -> buildString {
                append("Dogecoin RPC $method HTTP ")
                append(code)
                val responseMessage = message.trim()
                if (responseMessage.isNotBlank()) {
                    append(": ")
                    append(responseMessage)
                }
            }
        }
    }

    private fun rpcUrlForMethod(config: DogecoinRpcConfig, method: String): String {
        return if (method in walletRpcMethods) {
            config.walletEndpointUrl()
        } else {
            config.url.trim()
        }
    }

    private fun rpcErrorMessage(method: String, error: JsonElement): String {
        val errorObject = error.takeIf { it.isJsonObject }?.asJsonObject
        val code = parseOptionalRpcErrorCode(errorObject, method)
        val nodeMessage = parseOptionalRpcErrorMessage(errorObject, method)
            ?: error.toString()
        val normalizedMessage = nodeMessage.lowercase()

        if (method == "sendrawtransaction") {
            return broadcastRpcErrorMessage(code, nodeMessage, normalizedMessage)
        }

        if (method in walletRpcMethods) {
            if (
                code == -18 ||
                (
                    normalizedMessage.contains("wallet") &&
                        (
                            normalizedMessage.contains("not found") ||
                                normalizedMessage.contains("not loaded") ||
                                normalizedMessage.contains("does not exist")
                        )
                    )
            ) {
                return "Dogecoin Core wallet is not loaded for RPC method $method. " +
                    "Open or create a wallet in Dogecoin Core. For multiwallet nodes, enter the wallet name " +
                    "in Bitchat's Wallet name field and keep the RPC URL on the node base endpoint. " +
                    "Node message: $nodeMessage"
            }
            if (code == -32601 || normalizedMessage.contains("method not found")) {
                return "Dogecoin Core wallet RPC method $method is unavailable. " +
                    "Use a Dogecoin Core build with wallet support and a wallet endpoint that supports watch-only imports. " +
                    "Node message: $nodeMessage"
            }
        }

        return buildString {
            append("Dogecoin RPC $method failed")
            if (code != null) {
                append(" (code ")
                append(code)
                append(")")
            }
            append(": ")
            append(nodeMessage)
        }
    }

    private fun parseOptionalRpcErrorCode(errorObject: JsonObject?, method: String): Int? {
        val value = errorObject?.get("code")?.takeUnless { it.isJsonNull } ?: return null
        return runCatching { exactInt(value) }.getOrNull()
            ?: throw IllegalArgumentException("RPC $method returned an invalid error code.")
    }

    private fun parseOptionalRpcErrorMessage(errorObject: JsonObject?, method: String): String? {
        val value = errorObject?.get("message")?.takeUnless { it.isJsonNull } ?: return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isString == true) {
            "RPC $method returned an invalid error message."
        }
        return primitive.asString.trim().takeIf { it.isNotBlank() }
    }

    private fun broadcastRpcErrorMessage(code: Int?, nodeMessage: String, normalizedMessage: String): String {
        val guidance = when {
            normalizedMessage.contains("missing inputs") ||
                normalizedMessage.contains("missingorspent") ||
                normalizedMessage.contains("missing or spent") ->
                "Dogecoin node rejected this transaction because one or more selected inputs are missing or already spent. Refresh wallet balance and review the send again."
            normalizedMessage.contains("insufficient fee") ||
                normalizedMessage.contains("min relay fee") ||
                normalizedMessage.contains("mempool min fee") ||
                normalizedMessage.contains("fee not met") ->
                "Dogecoin node rejected this transaction because the fee is too low. Increase the DOGE/kB fee rate, refresh wallet balance, and review the send again."
            normalizedMessage.contains("dust") ->
                "Dogecoin node rejected this transaction as dust. Increase the send amount or use a higher-value confirmed UTXO, then review the send again."
            normalizedMessage.contains("already in block chain") ||
                normalizedMessage.contains("already in blockchain") ||
                normalizedMessage.contains("txn-already-in-mempool") ||
                normalizedMessage.contains("already in mempool") ->
                "Dogecoin node reports this transaction was already accepted. Refresh wallet balance or search for the signed txid before broadcasting again."
            normalizedMessage.contains("mandatory-script-verify") ||
                normalizedMessage.contains("non-mandatory-script-verify") ||
                normalizedMessage.contains("script verify") ->
                "Dogecoin node rejected the transaction script. Refresh wallet balance, verify the selected inputs, and review the send again."
            else -> null
        }

        return buildString {
            append(guidance ?: "Dogecoin node rejected the signed transaction.")
            if (code != null) {
                append(" RPC code ")
                append(code)
                append(".")
            }
            append(" Node message: ")
            append(nodeMessage)
        }
    }

    private fun mempoolRejectionMessage(rejectReason: String?): String {
        val nodeMessage = rejectReason?.trim().orEmpty()
        val normalizedMessage = nodeMessage.lowercase()
        val guidance = when {
            normalizedMessage.contains("missing inputs") ||
                normalizedMessage.contains("missingorspent") ||
                normalizedMessage.contains("missing or spent") ->
                "Dogecoin node policy rejected this signed transaction because one or more selected inputs are missing or already spent. Refresh wallet balance and review the send again."
            normalizedMessage.contains("insufficient fee") ||
                normalizedMessage.contains("min relay fee") ||
                normalizedMessage.contains("mempool min fee") ||
                normalizedMessage.contains("fee not met") ->
                "Dogecoin node policy rejected this signed transaction because the fee is too low. Increase the DOGE/kB fee rate, refresh wallet balance, and review the send again."
            normalizedMessage.contains("dust") ->
                "Dogecoin node policy rejected this signed transaction as dust. Increase the send amount or use a higher-value confirmed UTXO, then review the send again."
            normalizedMessage.contains("mandatory-script-verify") ||
                normalizedMessage.contains("non-mandatory-script-verify") ||
                normalizedMessage.contains("script verify") ->
                "Dogecoin node policy rejected the transaction script. Refresh wallet balance, verify the selected inputs, and review the send again."
            else -> "Dogecoin node policy rejected this signed transaction."
        }

        return if (nodeMessage.isBlank()) {
            guidance
        } else {
            "$guidance Node message: $nodeMessage"
        }
    }

    private fun isUnsupportedMempoolAcceptance(message: String): Boolean {
        return isUnsupportedRpcMethod(message, "testmempoolaccept")
    }

    private fun isUnsupportedRpcMethod(message: String, method: String): Boolean {
        val normalizedMessage = message.lowercase()
        return normalizedMessage.contains(method.lowercase()) &&
            (
                normalizedMessage.contains("method not found") ||
                    normalizedMessage.contains("code -32601") ||
                    normalizedMessage.contains("unknown command") ||
                    normalizedMessage.contains("unavailable")
                )
    }

    companion object {
        private val txidRegex = Regex("^[0-9a-f]{64}$")
        internal const val DOGECOIN_TPN_MIN_MAINNET_PEERS = 4
        internal const val DOGECOIN_TPN_MAX_BLOCK_HEADER_LAG = 2
        internal const val DOGECOIN_TPN_MIN_VERIFICATION_PROGRESS = 0.999999
        private const val DOGECOIN_TPN_ACTIVITY_DISPLAY_COUNT = 20
        private const val DOGECOIN_TPN_ACTIVITY_SCAN_COUNT = 100
        private val walletRpcMethods = setOf(
            "getwalletinfo",
            "importaddress",
            "gettransaction",
            "listunspent",
            "listtransactions",
            "rescanblockchain",
            "validateaddress"
        )

        /** Hard cap on any single RPC response body; larger responses abort the call. */
        internal const val DOGECOIN_RPC_MAX_RESPONSE_BYTES = 5L * 1024L * 1024L
    }

    private data class WalletInfoStatus(
        val ready: Boolean?,
        val walletName: String? = null,
        val loadedWalletNames: List<String> = emptyList(),
        val error: String? = null
    ) {
        companion object {
            val NotChecked = WalletInfoStatus(ready = null)
        }
    }

    private data class RelayInfoStatus(
        val ready: Boolean?,
        val networkActive: Boolean? = null,
        val peerCount: Int? = null,
        val relayFeePerKbKoinu: Long? = null,
        val incrementalFeePerKbKoinu: Long? = null,
        val softDustLimitKoinu: Long? = null,
        val hardDustLimitKoinu: Long? = null,
        val error: String? = null
    ) {
        companion object {
            val NotChecked = RelayInfoStatus(ready = null)
        }
    }

    private data class PolicyCheckStatus(
        val available: Boolean?,
        val error: String? = null
    ) {
        companion object {
            val NotChecked = PolicyCheckStatus(available = null)
        }
    }

    private data class RescanBlockchainStatus(
        val available: Boolean?,
        val error: String? = null
    ) {
        companion object {
            val NotChecked = RescanBlockchainStatus(available = null)
        }
    }
}

/**
 * Apply the non-negotiable transport hardening for Dogecoin RPC: redirects (plain and cross-scheme) are
 * never followed, so Basic credentials can never be replayed to a redirect target. Applied to every
 * client — including injected ones — by [DogecoinRpcClient]'s constructor.
 */
internal fun hardenedDogecoinRpcHttpClient(base: OkHttpClient): OkHttpClient =
    base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
