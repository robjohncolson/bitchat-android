package com.bitchat.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.features.dogecoin.DogecoinAmount
import com.bitchat.android.features.dogecoin.DogecoinNetwork
import com.bitchat.android.features.dogecoin.DogepaidClaimReason
import com.bitchat.android.features.dogecoin.DogepaidReceipt
import com.bitchat.android.features.dogecoin.DogepaidReceiptCheckResult
import com.bitchat.android.features.dogecoin.DogepaidReceiptState
import com.bitchat.android.features.dogecoin.ui.ConfirmationRing
import com.bitchat.android.features.dogecoin.ui.DogeWalletDark
import com.bitchat.android.features.dogecoin.ui.DogeWalletLight
import com.bitchat.android.features.dogecoin.ui.LocalDogeWalletColors
import com.bitchat.android.features.dogecoin.ui.RingMode
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.luminance
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat

/** Power-profile wrapper: preserves the normal message header and transport delivery-status overlay. */
@Composable
fun DogepaidReceiptBubble(
    message: BitchatMessage,
    receipt: DogepaidReceipt,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    duplicate: Boolean,
    outgoing: Boolean,
    walletNetwork: DogecoinNetwork?,
    onCheckStatus: ((DogepaidReceipt, (DogepaidReceiptCheckResult) -> Unit) -> Unit)?,
    onRetry: ((BitchatMessage) -> Unit)?,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val headerText = formatMessageHeaderAnnotatedString(
        message = message,
        currentUserNickname = currentUserNickname,
        meshService = meshService,
        colorScheme = colorScheme,
        timeFormatter = timeFormatter
    )
    val haptic = LocalHapticFeedback.current
    var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = headerText,
            fontFamily = FontFamily.Monospace,
            color = colorScheme.onSurface,
            modifier = Modifier.pointerInput(message.id) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = headerLayout ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        val annotations = headerText.getStringAnnotations("nickname_click", offset, offset)
                        if (annotations.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick(annotations.first().item)
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            onTextLayout = { headerLayout = it }
        )
        StatefulDogepaidReceiptCard(
            receipt = receipt,
            duplicate = duplicate,
            outgoing = outgoing,
            walletNetwork = walletNetwork,
            onCheckStatus = onCheckStatus,
            retryEnabled = outgoing && message.deliveryStatus is DeliveryStatus.Failed && onRetry != null,
            onRetry = { onRetry?.invoke(message) }
        )
    }
}

/** Shared Simple/Power status owner: polling exists only while the user has the status dialog open. */
@Composable
fun StatefulDogepaidReceiptCard(
    receipt: DogepaidReceipt,
    duplicate: Boolean,
    outgoing: Boolean,
    walletNetwork: DogecoinNetwork?,
    onCheckStatus: ((DogepaidReceipt, (DogepaidReceiptCheckResult) -> Unit) -> Unit)?,
    retryEnabled: Boolean = false,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showStatus by remember(receipt.network, receipt.txid) { mutableStateOf(false) }
    var checking by remember(receipt.network, receipt.txid) { mutableStateOf(false) }
    var receiptState by remember(receipt.network, receipt.txid) {
        mutableStateOf<DogepaidReceiptState>(
            DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED)
        )
    }
    var rpcNotOverTorDisclosure by remember(receipt.network, receipt.txid) { mutableStateOf(false) }

    val crossNetwork = walletNetwork != null && receipt.network != walletNetwork
    if (showStatus && !outgoing && !duplicate && !crossNetwork && onCheckStatus != null) {
        LaunchedEffect(receipt.network, receipt.txid, showStatus) {
            while (true) {
                if (!checking) {
                    checking = true
                    onCheckStatus(receipt) { result ->
                        receiptState = result.state
                        rpcNotOverTorDisclosure = result.rpcNotOverTorDisclosure
                        checking = false
                    }
                }
                delay(DOGEPAID_STATUS_POLL_MILLIS)
            }
        }
        DogepaidReceiptStatusDialog(
            receipt = receipt,
            state = receiptState,
            checking = checking,
            rpcNotOverTorDisclosure = rpcNotOverTorDisclosure,
            onDismiss = { showStatus = false }
        )
    }

    DogepaidReceiptCard(
        receipt = receipt,
        duplicate = duplicate,
        outgoing = outgoing,
        walletNetwork = walletNetwork,
        modifier = modifier,
        state = receiptState,
        retryEnabled = retryEnabled,
        onRetry = onRetry,
        onStatusClick = if (!outgoing && !duplicate && !crossNetwork && onCheckStatus != null) {
            { showStatus = true }
        } else null
    )
}

/**
 * Rich receipt body. With no independently supplied local observation it renders a claim, never a
 * paid/checkmark/confirmation assertion. The corroborated branch accepts only resolver output.
 */
@Composable
fun DogepaidReceiptCard(
    receipt: DogepaidReceipt,
    duplicate: Boolean,
    outgoing: Boolean,
    walletNetwork: DogecoinNetwork?,
    modifier: Modifier = Modifier,
    state: DogepaidReceiptState = DogepaidReceiptState.Claimed(DogepaidClaimReason.NOT_CORROBORATED),
    onStatusClick: (() -> Unit)? = null,
    retryEnabled: Boolean = false,
    onRetry: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onStatusClick != null) Modifier.clickable(onClick = onStatusClick) else Modifier)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(
                    Icons.Filled.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(
                        if (duplicate) R.string.dogepaid_receipt_duplicate_title
                        else R.string.dogepaid_receipt_title
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = localizedDogecoinNetwork(receipt.network),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = stringResource(
                    R.string.dogepaid_receipt_reported_amount,
                    DogecoinAmount.formatKoinu(receipt.amountKoinu)
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            SelectionContainer {
                Text(
                    // Keep the full txid in the selectable text; maxLines/ellipsis only shortens its paint.
                    text = stringResource(R.string.dogepaid_receipt_txid, receipt.txid),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            val statusText = when {
                duplicate -> stringResource(R.string.dogepaid_receipt_duplicate_body)
                outgoing -> stringResource(R.string.dogepaid_receipt_outgoing_claimed)
                walletNetwork != null && receipt.network != walletNetwork -> stringResource(
                    R.string.dogepaid_receipt_cross_network,
                    localizedDogecoinNetwork(receipt.network),
                    localizedDogecoinNetwork(walletNetwork)
                )
                state is DogepaidReceiptState.Corroborated -> stringResource(
                    R.string.dogepaid_receipt_corroborated,
                    DogecoinAmount.formatKoinu(state.observedAmountKoinu),
                    state.confirmations
                )
                else -> stringResource(R.string.dogepaid_receipt_claimed)
            }
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onStatusClick != null) {
                Text(
                    text = stringResource(R.string.dogepaid_receipt_tap_status),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            if (retryEnabled && onRetry != null) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.dogepaid_receipt_retry))
                }
            }
        }
    }
}

@Composable
private fun DogepaidReceiptStatusDialog(
    receipt: DogepaidReceipt,
    state: DogepaidReceiptState,
    checking: Boolean,
    rpcNotOverTorDisclosure: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dogepaid_receipt_status_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state is DogepaidReceiptState.Corroborated) {
                    val depth = state.confirmations.coerceAtLeast(0)
                    val colors = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
                        DogeWalletDark
                    } else {
                        DogeWalletLight
                    }
                    CompositionLocalProvider(LocalDogeWalletColors provides colors) {
                        ConfirmationRing(
                            mode = RingMode.CONFIRMING,
                            progress = depth.coerceAtMost(DOGEPAID_CONFIRMATION_TARGET).toFloat() /
                                DOGEPAID_CONFIRMATION_TARGET.toFloat(),
                            diameter = 150.dp,
                            contentDescription = stringResource(
                                R.string.dogepaid_receipt_confirmation_description,
                                depth,
                                DOGEPAID_CONFIRMATION_TARGET
                            ),
                            centerContent = {
                                Text(
                                    text = "$depth/$DOGEPAID_CONFIRMATION_TARGET",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        )
                    }
                }
                if (checking) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.dogepaid_receipt_checking))
                    }
                }
                if (rpcNotOverTorDisclosure) {
                    Text(
                        text = stringResource(R.string.dogepaid_receipt_rpc_not_over_tor),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = when (state) {
                        is DogepaidReceiptState.Corroborated -> stringResource(
                            R.string.dogepaid_receipt_corroborated,
                            DogecoinAmount.formatKoinu(state.observedAmountKoinu),
                            state.confirmations
                        )
                        is DogepaidReceiptState.Claimed -> stringResource(R.string.dogepaid_receipt_claimed)
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                SelectionContainer {
                    Text(
                        text = receipt.txid,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dogepaid_receipt_close))
            }
        }
    )
}

/** First receipt per (network, txid) in this conversation gets the one live status surface. */
internal fun canonicalDogepaidReceiptMessageIds(messages: List<BitchatMessage>): Set<String> {
    val seen = mutableSetOf<String>()
    val canonical = mutableSetOf<String>()
    messages.forEach { message ->
        val receipt = DogepaidReceipt.parse(message.content) ?: return@forEach
        val key = "${receipt.network.id}:${receipt.txid}"
        if (seen.add(key)) canonical.add(message.id)
    }
    return canonical
}

@Composable
private fun localizedDogecoinNetwork(network: DogecoinNetwork): String = stringResource(
    when (network) {
        DogecoinNetwork.MAINNET -> R.string.dogecoin_network_mainnet
        DogecoinNetwork.TESTNET -> R.string.dogecoin_network_testnet
        DogecoinNetwork.REGTEST -> R.string.dogecoin_network_regtest
    }
)

private const val DOGEPAID_STATUS_POLL_MILLIS = 15_000L
private const val DOGEPAID_CONFIRMATION_TARGET = 6
