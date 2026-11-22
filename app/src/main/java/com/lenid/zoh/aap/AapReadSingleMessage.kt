package com.lenid.zoh.aap

import android.os.SystemClock
import com.lenid.zoh.connection.projection.ProjectionConnection
import com.lenid.zoh.connection.projection.SocketProjectionConnection
import com.lenid.zoh.decoder.video.VideoFaultInjector
import com.lenid.zoh.utils.AppLog
import com.lenid.zoh.utils.Utils

internal class AapReadSingleMessage(
    connection: ProjectionConnection,
    ssl: AapSsl,
    handler: AapMessageHandler,
    onVideoRunHoled: (discardAssembledUnit: Boolean) -> Unit = {},
    faultInjector: VideoFaultInjector? = null,
    private val captureTiming: () -> Boolean = { false },
    private val onSlowRead: (TransportReadTiming, Long) -> Unit = { _, _ -> },
    private val onPeerClose: () -> Unit = {})
    : AapRead.Base(connection, ssl, handler, onVideoRunHoled, faultInjector) {

    private val socketConnection = connection as? SocketProjectionConnection
    private val recvHeader = AapMessageIncoming.EncryptedHeader()
    // Increase to 4MB to handle large 1080p/4K/HEVC I-frames
    private val msgBuffer = ByteArray(4 * 1024 * 1024)
    private val fragmentSizeBuffer = ByteArray(4)
    private var previousReadFinishedMs = 0L

    override fun doRead(connection: ProjectionConnection): Int {
        val timed = captureTiming()
        val readStart = if (timed) SystemClock.elapsedRealtime() else 0L
        try {
            // Step 1: Read the cleartext AAP envelope, not a TLS header. recvBlocking(...,
            // readFully=true) must finish these four bytes even if TCP splits them across reads.
            // Socket connections use a 15s idle timeout; the non-socket fallback passes zero.
            // Once any frame prefix is consumed, an incomplete remainder cannot be skipped:
            // there is no delimiter from which to rediscover the next header safely.
            val isSocket = connection is SocketProjectionConnection
            val timeout = if (isSocket) 15000 else 0
            val headerSize = connection.recvBlocking(recvHeader.buf, recvHeader.buf.size, timeout, true)
            when (AapReadRecoveryPolicy.afterHeaderRead(headerSize, AapMessageIncoming.EncryptedHeader.SIZE, isSocket)) {
                AapReadRecoveryPolicy.Outcome.CONTINUE -> {
                    // Either the whole header arrived, or nothing did on a transport that tolerates
                    // a quiet bus. Only the second needs to go round again without one.
                    if (headerSize != AapMessageIncoming.EncryptedHeader.SIZE) return 0
                }
                AapReadRecoveryPolicy.Outcome.DISCONNECT_EOF -> {
                    val failure = socketConnection?.lastReadFailure
                    if (failure != null && (failure.cause !is java.io.EOFException || failure.bytesRead > 0)) {
                        logIncompleteRead("AapRead: Header read failed. Disconnecting.")
                    } else {
                        val detail = failure?.let { " ${it.description()}" }.orEmpty()
                        AppLog.i("AapRead: Connection closed (EOF). Disconnecting.$detail")
                    }
                    return -1
                }
                AapReadRecoveryPolicy.Outcome.DISCONNECT_IDLE -> {
                    logIncompleteRead("AapRead: WiFi read timeout (${timeout}ms) - connection lost.")
                    return -1
                }
                AapReadRecoveryPolicy.Outcome.DISCONNECT_DESYNC -> {
                    logIncompleteRead(
                        "AapRead: partial header, $headerSize of ${AapMessageIncoming.EncryptedHeader.SIZE} bytes. " +
                            "The rest of it is still on the socket and cannot be located again - disconnecting to resync."
                    )
                    return -1
                }
            }

            val headerFinished = if (timed) SystemClock.elapsedRealtime() else 0L
            recvHeader.decode()

            // Immediate check for Magic Garbage in the header bytes.
            // This is the most reliable path for intentional disconnects from the Helper.
            if (isMagicGarbage(recvHeader.buf, 0, recvHeader.buf.size)) {
                // Publish the Helper's close intent before logging or returning to the poll
                // loop, while a pending TLS write may concurrently report a link failure.
                onPeerClose()
                AppLog.i("AapRead: Magic Garbage detected in header. Clean disconnect.")
                return -2
            }

            // Only a first fragment carries the total size, and only then is this meaningful.
            var declaredTotal = 0
            if (AapMessageFraming.carriesTotalLength(recvHeader.flags)) {
                // Once header arrived, data should be flowing — 10s timeout is valid here
                val readSize = connection.recvBlocking(fragmentSizeBuffer, 4, 10000, true)
                when (AapReadRecoveryPolicy.afterFragmentTotalRead(readSize, 4)) {
                    AapReadRecoveryPolicy.Outcome.CONTINUE -> Unit
                    AapReadRecoveryPolicy.Outcome.DISCONNECT_EOF -> {
                        logIncompleteRead("AapRead: Connection closed reading the fragment total.", eof = true)
                        return -1
                    }
                    else -> {
                        logIncompleteRead(
                            "AapRead: fragment total read returned $readSize of 4 - this message's header is " +
                                "already consumed, so the body would be read as the next header. Disconnecting to resync."
                        )
                        return -1
                    }
                }
                declaredTotal = Utils.bytesToInt(fragmentSizeBuffer, 0, false)
            }

            // Step 2: Read the encrypted message body
            // Header arrived so body should follow quickly — 10s timeout
            if (AapReadRecoveryPolicy.afterDeclaredLength(recvHeader.enc_len, msgBuffer.size) !=
                AapReadRecoveryPolicy.Outcome.CONTINUE
            ) {
                // Nearly always a header read out of garbage rather than a message too big to hold.
                // Either way the body cannot be consumed, so skipping would compound the loss.
                AppLog.e(
                    "AapRead: declared message size ${recvHeader.enc_len} is outside the ${msgBuffer.size}-byte " +
                        "buffer - the stream is no longer framed. Disconnecting to resync."
                )
                return -1
            }

            val msgSize = connection.recvBlocking(msgBuffer, recvHeader.enc_len, 10000, true)
            when (AapReadRecoveryPolicy.afterBodyRead(msgSize, recvHeader.enc_len)) {
                AapReadRecoveryPolicy.Outcome.CONTINUE -> Unit
                AapReadRecoveryPolicy.Outcome.DISCONNECT_EOF -> {
                    logIncompleteRead("AapRead: Connection closed during body read.", eof = true)
                    return -1
                }
                else -> {
                    // The return value is a failure sentinel, never permission to resume this
                    // body. Socket diagnostics separately retain the bytes consumed and cause.
                    logIncompleteRead(
                        "AapRead: body read returned $msgSize of ${recvHeader.enc_len} expected - " +
                            "the frame is incomplete and cannot safely be resumed. Disconnecting to resync."
                    )
                    return -1
                }
            }

            val bodyFinished = if (timed) SystemClock.elapsedRealtime() else 0L
            // Reader-stage fault injection, resolved before the audit and acted on after the
            // decrypt. Both halves of that are load-bearing - see shouldDropForFaultInjection.
            observeEncryptedBody(recvHeader.chan, recvHeader.enc_len)
            val injectedDrop =
                shouldDropForFaultInjection(recvHeader.chan, recvHeader.flags, recvHeader.enc_len)

            // Step 3: Decrypt the message. Unconditionally, including a message about to be dropped:
            // the SSL engine's record sequence advances per record and the phone's does too, so a
            // record we never unwrap desynchronises the session for good. A retired session
            // is the exception: no later record belongs to an engine we will reuse.
            if (isStopped) return -1
            val msg = AapMessageIncoming.decrypt(recvHeader, 0, msgBuffer, ssl)
            val decryptFinished = if (timed) SystemClock.elapsedRealtime() else 0L

            if (msg == null) {
                // If decryption failed because of a Magic Garbage signal, return -2 to signal clean quit
                if (ssl is AapSslContext && ssl.isUserDisconnect) {
                    onPeerClose()
                    AppLog.i("AapRead: Magic Garbage detected in decryption. Triggering clean disconnect.")
                    return -2
                }
                return 0
            }

            // Now the message can be thrown away: the SSL engine has seen it, the audit has not,
            // and nothing downstream ever will - which is a fragment that failed to arrive, as far
            // as everything past this point can tell.
            if (injectedDrop) return 0

            // Step 4: Handle the decrypted message
            deliverFragment(msg, declaredTotal)
            if (timed) {
                val finished = SystemClock.elapsedRealtime()
                val gap = if (previousReadFinishedMs > 0) (readStart - previousReadFinishedMs).coerceAtLeast(0) else 0L
                // Do not allocate a report on the ordinary packet path.
                if (TransportReadTiming.isProcessingSlow(gap, finished - headerFinished)) {
                    onSlowRead(TransportReadTiming(msg.channel, gap, headerFinished - readStart,
                        bodyFinished - headerFinished, decryptFinished - bodyFinished,
                        finished - decryptFinished), finished)
                }
            }
            return 0
        } catch (e: java.io.IOException) {
            AppLog.e("AapRead: invalid framing or TLS session", e)
            return -1
        } catch (e: Exception) {
            // Handler failures are isolated inside deliverFragment. Transport/TLS IOExceptions
            // above remain fatal; unexpected non-I/O processing errors retain the existing policy.
            AppLog.e("AapRead: Error in read loop (ignored): ${e.message}")
            return 0
        } finally {
            previousReadFinishedMs = SystemClock.elapsedRealtime()
        }
    }

    /** Attach the original socket exception to the same journal event as the framing failure. */
    private fun logIncompleteRead(message: String, eof: Boolean = false) {
        val failure = socketConnection?.lastReadFailure
        if (failure != null) {
            AppLog.e(
                "$message ${failure.description()}",
                failure.cause,
            )
        } else if (eof) {
            AppLog.i(message)
        } else {
            AppLog.e(message)
        }
    }

    private fun isMagicGarbage(buffer: ByteArray, start: Int, length: Int): Boolean {
        if (length < 4) return false // Need at least some bytes to verify
        // Check if at least the first 4 bytes are 0xFF
        for (i in 0 until 4.coerceAtMost(length)) {
            if (buffer[start + i] != 0xFF.toByte()) return false
        }
        return true
    }
}
