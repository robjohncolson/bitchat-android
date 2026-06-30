package com.bitchat.android.features.dogecoin

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.math.BigDecimal
import java.math.RoundingMode
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

class DogecoinRpcClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val gson: Gson = Gson()
) {
    private val rescanHttpClient = httpClient.newBuilder()
        .readTimeout(30, TimeUnit.MINUTES)
        .build()

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
            val verificationProgress = parseOptionalFraction(result, "verificationprogress", "getblockchaininfo")
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

    suspend fun sendRawTransaction(
        config: DogecoinRpcConfig,
        rawTransactionHex: String,
        network: DogecoinNetwork = DogecoinNetwork.DEFAULT
    ): String =
        withContext(Dispatchers.IO) {
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
                    JsonArray().apply { add(normalizedRawTransactionHex) }
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

    private fun parseOptionalFraction(result: JsonObject, fieldName: String, method: String): Double? {
        val value = result.get(fieldName)?.takeUnless { it.isJsonNull } ?: return null
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        require(primitive?.isNumber == true) {
            "RPC $method returned an invalid $fieldName."
        }
        val parsed = runCatching { primitive.asDouble }.getOrNull()
            ?: throw IllegalArgumentException("RPC $method returned an invalid $fieldName.")
        require(!parsed.isNaN() && !parsed.isInfinite() && parsed in 0.0..1.0) {
            "RPC $method returned an out-of-range $fieldName."
        }
        return parsed
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
        longRunning: Boolean = false
    ): JsonElement {
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
        client.newCall(requestBuilder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
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
                throw IllegalStateException(httpRpcErrorMessage(method, response.code, response.message))
            }

            return json?.get("result") ?: JsonNull.INSTANCE
        }
    }

    private fun httpRpcErrorMessage(method: String, code: Int, message: String): String {
        return when (code) {
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

    private companion object {
        val txidRegex = Regex("^[0-9a-f]{64}$")
        val walletRpcMethods = setOf(
            "getwalletinfo",
            "importaddress",
            "listunspent",
            "listtransactions",
            "rescanblockchain",
            "validateaddress"
        )
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
