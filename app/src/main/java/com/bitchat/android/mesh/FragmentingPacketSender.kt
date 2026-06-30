package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared transport send wrapper that applies bitchat packet fragmentation and
 * transfer progress before a transport writes packets to its concrete medium.
 */
class FragmentingPacketSender(
    private val scope: CoroutineScope,
    private val fragmentManager: FragmentManager?,
    private val logTag: String,
    private val interFragmentDelayMs: Long = 20L
) {
    private val transferJobs = ConcurrentHashMap<String, Job>()

    private companion object {
        // Option B (relay-hop arm): a single GATT connection allows one outstanding op, so a later
        // fragment of a TARGETED/relayed send (sendSingle returns the real write result) can momentarily
        // return false ("busy"). Previously that ABORTED the whole set after N/total fragments. Now we
        // retry the same fragment a bounded number of times before giving up, so the helper still
        // reassembles. (The broadcast arm's sendSingle always returns true and retries inside the
        // broadcaster instead, so this loop is a no-op there.)
        const val FRAGMENT_MAX_ATTEMPTS = 8
        const val FRAGMENT_RETRY_DELAY_MS = 50L
    }

    fun send(
        routed: RoutedPacket,
        description: String,
        sendSingle: (RoutedPacket) -> Boolean
    ): Boolean {
        val transferId = transferIdFor(routed)
        val packets = packetsForTransport(routed.packet) ?: return false
        val total = packets.size

        if (total <= 1) {
            if (transferId != null) {
                TransferProgressManager.start(transferId, 1)
            }
            val sent = sendSingle(routed.copy(packet = packets.first(), transferId = transferId))
            if (sent && transferId != null) {
                TransferProgressManager.progress(transferId, 1, 1)
                TransferProgressManager.complete(transferId, 1)
            }
            return sent
        }

        Log.d(logTag, "Fragmenting packet type ${routed.packet.type} into $total fragments for $description")
        if (transferId != null) {
            TransferProgressManager.start(transferId, total)
        }

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var sent = 0
            for (packet in packets) {
                if (!isActive) return@launch
                if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch

                val fragment = routed.copy(packet = packet, transferId = transferId)
                var delivered = false
                var attempt = 0
                while (attempt < FRAGMENT_MAX_ATTEMPTS && !delivered && isActive) {
                    delivered = try {
                        sendSingle(fragment)
                    } catch (e: Exception) {
                        Log.e(logTag, "Fragment send failed for $description: ${e.message}", e)
                        false
                    }
                    if (!delivered) {
                        attempt += 1
                        if (attempt < FRAGMENT_MAX_ATTEMPTS) {
                            Log.d(logTag, "Fragment ${sent + 1}/$total busy for $description, retry $attempt/$FRAGMENT_MAX_ATTEMPTS")
                            delay(FRAGMENT_RETRY_DELAY_MS)
                        }
                    }
                }

                if (!delivered) {
                    Log.w(logTag, "Stopping fragmented send for $description after $sent/$total fragments")
                    return@launch
                }

                sent += 1
                if (transferId != null) {
                    TransferProgressManager.progress(transferId, sent, total)
                }
                if (sent < total) {
                    delay(interFragmentDelayMs)
                }
            }

            if (transferId != null) {
                TransferProgressManager.complete(transferId, total)
            }
        }

        if (transferId != null) {
            transferJobs[transferId] = job
            job.invokeOnCompletion { transferJobs.remove(transferId, job) }
        }
        job.start()
        return true
    }

    fun cancelTransfer(transferId: String): Boolean {
        val job = transferJobs.remove(transferId) ?: return false
        job.cancel()
        return true
    }

    private fun packetsForTransport(packet: BitchatPacket): List<BitchatPacket>? {
        if (packet.type == MessageType.FRAGMENT.value) {
            return listOf(packet)
        }

        val manager = fragmentManager ?: return listOf(packet)
        return try {
            val fragments = manager.createFragments(packet)
            if (fragments.isEmpty()) {
                Log.e(logTag, "Fragment manager returned no packets for packet type ${packet.type}")
                null
            } else {
                fragments
            }
        } catch (e: Exception) {
            Log.e(logTag, "Fragment creation failed for packet type ${packet.type}: ${e.message}", e)
            null
        }
    }

    private fun transferIdFor(routed: RoutedPacket): String? {
        routed.transferId?.let { return it }
        val packet = routed.packet
        return if (packet.type == MessageType.FILE_TRANSFER.value) {
            sha256Hex(packet.payload)
        } else {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
