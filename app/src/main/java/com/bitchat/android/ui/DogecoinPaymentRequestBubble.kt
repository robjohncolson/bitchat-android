package com.bitchat.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.features.dogecoin.DogecoinPaymentRequest
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import java.text.SimpleDateFormat

@Composable
fun DogecoinPaymentRequestBubble(
    message: BitchatMessage,
    uri: String,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onPayClick: ((String) -> Unit)?,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val request = remember(uri) { DogecoinPaymentRequest.parse(uri) }
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
                        val nicknameAnnotations = headerText.getStringAnnotations(
                            tag = "nickname_click",
                            start = offset,
                            end = offset
                        )
                        if (nicknameAnnotations.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick.invoke(nicknameAnnotations.first().item)
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
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, colorScheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.dogecoin_payment_request_bubble_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.primary
                )
                Text(
                    text = request?.amount?.let {
                        stringResource(R.string.dogecoin_payment_request_bubble_amount, it)
                    } ?: stringResource(R.string.dogecoin_payment_request_bubble_open_amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface
                )
                request?.label?.let { label ->
                    Text(
                        text = stringResource(R.string.dogecoin_payment_request_bubble_label, label),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurface.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                request?.message?.let { memo ->
                    Text(
                        text = stringResource(R.string.dogecoin_payment_request_bubble_message, memo),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurface.copy(alpha = 0.78f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = request?.address?.let { shortBubbleDogecoinValue(it) } ?: shortBubbleDogecoinValue(uri),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = colorScheme.onSurface.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Button(
                    onClick = { onPayClick?.invoke(uri) },
                    enabled = onPayClick != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.dogecoin_payment_request_bubble_pay),
                        fontSize = BASE_FONT_SIZE.sp
                    )
                }
            }
        }
    }
}

private fun shortBubbleDogecoinValue(value: String): String {
    return if (value.length <= 22) value else value.take(10) + "..." + value.takeLast(8)
}
