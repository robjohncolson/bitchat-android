package com.bitchat.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.features.dogecoin.DogecoinAmount
import com.bitchat.android.features.dogecoin.DogecoinProtocol
import com.bitchat.android.features.dogecoin.DogecoinWalletRepository

@Composable
fun RequestDogeDialog(
    requiresPublicConfirmation: Boolean,
    onDismiss: () -> Unit,
    onPostRequest: (String) -> Unit
) {
    val context = LocalContext.current
    val repository = remember(context) { DogecoinWalletRepository(context) }
    var amount by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var publicConfirmed by remember(requiresPublicConfirmation) { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun postRequest() {
        val cleanAmount = amount.trim()
        val cleanLabel = label.trim()
        val cleanMessage = message.trim()
        error = null

        if (requiresPublicConfirmation && !publicConfirmed) {
            error = context.getString(R.string.dogecoin_request_doge_confirm_required)
            return
        }
        if (!DogecoinAmount.isStandardOutputAmount(cleanAmount)) {
            error = context.getString(R.string.dogecoin_invalid_request)
            return
        }

        runCatching {
            val network = repository.loadSelectedNetwork()
            val snapshot = repository.loadOrCreateWallet(network)
            DogecoinProtocol.createPaymentUri(
                network = network,
                address = snapshot.key.address,
                amount = cleanAmount,
                label = cleanLabel.takeIf { it.isNotBlank() },
                message = cleanMessage.takeIf { it.isNotBlank() }
            )
        }.onSuccess { uri ->
            onPostRequest(uri)
            onDismiss()
        }.onFailure {
            error = it.message ?: context.getString(R.string.dogecoin_invalid_request)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dogecoin_request_doge_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = {
                        amount = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.dogecoin_amount_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = error != null && amount.isNotBlank() &&
                        !DogecoinAmount.isStandardOutputAmount(amount)
                )
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.dogecoin_label_label)) }
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = {
                        message = it
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.dogecoin_message_label)) }
                )
                if (requiresPublicConfirmation) {
                    Text(
                        text = stringResource(R.string.dogecoin_request_doge_public_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 18.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Checkbox(
                            checked = publicConfirmed,
                            onCheckedChange = {
                                publicConfirmed = it
                                error = null
                            }
                        )
                        Text(
                            text = stringResource(R.string.dogecoin_request_doge_public_confirm),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            lineHeight = 18.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        lineHeight = 18.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = { postRequest() }) {
                Text(stringResource(R.string.dogecoin_request_doge_post))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
