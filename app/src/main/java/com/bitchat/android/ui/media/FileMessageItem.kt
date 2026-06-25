package com.bitchat.android.ui.media

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.model.BitchatFilePacket
import java.io.File

/**
 * Modern chat-style file message display
 */
@Composable
fun FileMessageItem(
    packet: BitchatFilePacket,
    onFileClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .fillMaxWidth(0.8f)
            .clickable {
                onFileClick()
                showDialog = true
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // File icon
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = stringResource(com.bitchat.android.R.string.cd_file),
                tint = getFileIconColor(packet.fileName),
                modifier = Modifier.size(32.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // File name
                Text(
                    text = packet.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // File details
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = FileUtils.formatFileSize(packet.fileSize),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // File type indicator
                    FileTypeBadge(mimeType = packet.mimeType)
                }
            }
        }
    }

    // File viewer dialog
    if (showDialog) {
        FileViewerDialog(
            packet = packet,
            onDismiss = { showDialog = false },
            onSaveToDevice = { content, fileName ->
                val saved = saveFileToDownloads(context, content, fileName, packet.mimeType)
                Toast.makeText(
                    context,
                    if (saved) context.getString(R.string.toast_file_saved) else context.getString(R.string.toast_failed_to_save_file),
                    Toast.LENGTH_SHORT
                ).show()
                saved
            }
        )
    }
}

/**
 * Small badge showing file type
 */
@Composable
private fun FileTypeBadge(mimeType: String) {
    val (text, color) = when {
        mimeType.startsWith("application/pdf") -> "PDF" to Color(0xFFDC2626)
        mimeType.startsWith("text/") -> "TXT" to Color(0xFF059669)
        mimeType.startsWith("image/") -> "IMG" to Color(0xFF7C3AED)
        mimeType.startsWith("audio/") -> "AUD" to Color(0xFFEA580C)
        mimeType.startsWith("video/") -> "VID" to Color(0xFF2563EB)
        mimeType.contains("document") -> "DOC" to Color(0xFF1D4ED8)
        mimeType.contains("zip") || mimeType.contains("rar") -> "ZIP" to Color(0xFF7C2D12)
        else -> "FILE" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold
    )
}

/**
 * Get appropriate icon color based on file extension
 */
private fun getFileIconColor(fileName: String): Color {
    val extension = fileName.substringAfterLast(".", "").lowercase()
    return when (extension) {
        "pdf" -> Color(0xFFDC2626) // Red
        "doc", "docx" -> Color(0xFF1D4ED8) // Blue
        "xls", "xlsx" -> Color(0xFF059669) // Green
        "ppt", "pptx" -> Color(0xFFEA580C) // Orange
        "txt", "json", "xml" -> Color(0xFF7C3AED) // Purple
        "jpg", "png", "gif", "webp" -> Color(0xFF2563EB) // Blue
        "mp3", "wav", "m4a" -> Color(0xFFEA580C) // Orange
        "mp4", "avi", "mov" -> Color(0xFFDC2626) // Red
        "zip", "rar", "7z" -> Color(0xFF7C2D12) // Brown
        else -> Color(0xFF6B7280) // Gray
    }
}

private fun saveFileToDownloads(
    context: Context,
    content: ByteArray,
    fileName: String,
    mimeType: String
): Boolean {
    val safeName = fileName
        .takeIf { it.isNotBlank() }
        ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        ?: "bitchat_file.bin"

    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
            try {
                val outputStream = resolver.openOutputStream(uri)
                if (outputStream == null) {
                    resolver.delete(uri, null, null)
                    return false
                }
                outputStream.use { output -> output.write(content) }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                false
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) return false

            val target = uniqueFile(downloadsDir, safeName)
            target.writeBytes(content)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            true
        }
    } catch (_: Exception) {
        false
    }
}

private fun uniqueFile(directory: File, fileName: String): File {
    var candidate = File(directory, fileName)
    if (!candidate.exists()) return candidate

    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0) fileName.substring(dot) else ""
    var index = 1
    while (candidate.exists() && index < 1000) {
        candidate = File(directory, "$base ($index)$ext")
        index++
    }
    return candidate
}
