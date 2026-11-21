package com.lenid.zoh.aap

import com.lenid.zoh.modules.ModuleFunction
/**
 * A slow peer answers every version request it was sent, an abandoned handshake's included, and
 * the extra answers land in the TLS phase. The SSLEngine fails on them, so the reader skips them.
 */
object HandshakeMessagePolicy {
    /** [header] is the 6-byte AAP header: channel, flags, length (2), type (2). */
    fun isLateVersionResponse(header: ByteArray): Boolean =
        classifyFrame(header) == 1

    /**
     * TCP delivers a request or drops the link, so a resend there reaches the peer only as a
     * duplicate, and after one Android Auto's head unit server stops answering everyone.
     * USB can lose a request, so it keeps its retries.
     */
    fun versionRequests(isUsb: Boolean): Int =
        ModuleFunction.VERSION_REQUESTS.evaluate(if (isUsb) 1 else 0) ?: if (isUsb) 3 else 1

    /** The peer rejected framing (0xffff) or asked to end this session (ByeByeRequest). */
    fun isTerminalResponse(frame: ByteArray): Boolean = classifyFrame(frame) == 2

    private fun classifyFrame(frame: ByteArray): Int {
        if (frame.size < 6) return 0
        val message = ((frame[0].toInt() and 255) shl 16) or
            ((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255)
        return ModuleFunction.HANDSHAKE_FRAME.evaluate(message) ?: when (message) {
            2 -> 1
            15, 65535 -> 2
            else -> 0
        }
    }

    /**
     * A USB read returns -1 for a timeout and for an error alike, so there a -1 that took half its
     * timeout or more is a timeout. A socket read returns 0 on a timeout, so its -1 is an error.
     */
    fun isReadError(ret: Int, elapsedMs: Long, timeoutMs: Int, timeoutLooksLikeError: Boolean): Boolean {
        val early = elapsedMs * 2 < timeoutMs
        return ModuleFunction.READ_ERROR.evaluate(ret, if (timeoutLooksLikeError) 1 else 0, if (early) 1 else 0)
            ?.let { it == 1 } ?: (ret < 0 && (!timeoutLooksLikeError || early))
    }

    /** Names why the version exchange ended without a response; a transport error wins. */
    fun versionFailure(peerSentBytes: Boolean, transportError: Boolean): AapTransport.HandshakeFailure =
        when (ModuleFunction.VERSION_FAILURE.evaluate(if (peerSentBytes) 1 else 0, if (transportError) 1 else 0)
            ?: if (transportError) 2 else if (peerSentBytes) 0 else 1) {
            2 -> AapTransport.HandshakeFailure.TRANSPORT_ERROR
            1 -> AapTransport.HandshakeFailure.PEER_SILENT
            else -> AapTransport.HandshakeFailure.OTHER
        }
}
