package com.shilapi.xcertplay.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Operations are scoped to the buffer and endpoint of this call. No module can select a peer. */
class TransportIo(
    private val nextTransfer: ((mode: Int, remaining: Int, transferred: Int) -> Int?)? = null,
    private val nextOpen: ((path: Int, position: Int) -> Int?)? = null,
    private val usbRetryDelay: ((Int) -> Int?)? = null,
) {
    enum class Mode { ONCE, FULL, USB_CAPPED, FRAME }
    enum class Path { TCP, USB }

    /** Setup and endpoint selection may trade places. Acquiring the connection always comes last. */
    fun open(path: Path, selectEndpoint: () -> Unit, configure: () -> Unit, acquire: () -> Unit) {
        val first = nextOpen?.invoke(path.ordinal, 0)
        val second = nextOpen?.invoke(path.ordinal, 1)
        val last = nextOpen?.invoke(path.ordinal, 2)
        if (first == 1 && second == 0 && last == 2) {
            configure()
            selectEndpoint()
        } else {
            selectEndpoint()
            configure()
        }
        acquire()
    }

    /** A retry owns no descriptor. Stop promptly when the caller retires the connection. */
    fun <T : Any> acquireUsb(
        defaultDelay: (failedAttempts: Int) -> Int = { 0 },
        current: () -> Boolean = { true },
        pause: (Int) -> Unit = { Thread.sleep(it.toLong()) },
        acquire: (attempt: Int) -> T?,
    ): T? {
        for (attempt in 1..5) {
            if (!current()) return null
            acquire(attempt)?.let { return it }
            if (attempt == 5) break
            var delay = (usbRetryDelay?.invoke(attempt) ?: defaultDelay(attempt)).coerceIn(0, 2000)
            if (delay == 0) break
            while (delay > 0) {
                if (!current()) return null
                val slice = minOf(delay, 100)
                try { pause(slice) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
                delay -= slice
            }
        }
        return null
    }

    /** A queued USB read stays one request; retain the host platform's size ceiling. */
    fun queueBytes(max: Int, packetSize: Int = 1): Int {
        val proposed = nextTransfer?.invoke(Mode.ONCE.ordinal, max, 0)?.takeIf { it in 1..max } ?: max
        return alignedChunk(proposed, max, packetSize)
    }

    private fun alignedChunk(proposed: Int, remaining: Int, packetSize: Int): Int {
        if (proposed == remaining || proposed == 0) return proposed
        val packet = packetSize.coerceAtLeast(1)
        return minOf(remaining, maxOf(packet, proposed / packet * packet))
    }

    /** Preserve a physical short write and share the caller's timeout across module-selected chunks. */
    fun writeUsb(length: Int, timeoutMillis: Int, packetSize: Int, operation: (Int, Int, Int) -> Int): Int {
        require(length >= 0)
        if (length == 0) return operation(0, 0, timeoutMillis)
        val start = System.nanoTime()
        var done = 0
        while (done < length) {
            val remaining = length - done
            val proposed = nextTransfer?.invoke(Mode.FULL.ordinal, remaining, done)
                ?.takeIf { it in 1..remaining } ?: remaining
            // An intermediate short USB packet would signal the end of a transfer to the phone.
            val count = alignedChunk(proposed, remaining, packetSize)
            val budget = if (timeoutMillis <= 0 || done == 0) timeoutMillis else {
                val left = timeoutMillis - (System.nanoTime() - start) / 1_000_000L
                if (left <= 0) return done
                left.toInt()
            }
            val actual = operation(done, count, budget)
            if (actual <= 0) return if (done == 0) actual else done
            if (actual > count) throw IOException("USB write exceeded requested size")
            done += actual
            if (actual != count) break
        }
        return done
    }

    /** One command per transfer. A positive result requests bytes, zero returns the current count. */
    fun transfer(mode: Mode, length: Int, packetSize: Int = 1, operation: (offset: Int, count: Int) -> Int): Int {
        require(length >= 0)
        if (length == 0) return operation(0, 0)
        var done = 0
        while (done < length) {
            val remaining = length - done
            val fallback = when {
                done > 0 && mode != Mode.FULL -> 0
                mode == Mode.USB_CAPPED -> minOf(remaining, 65536)
                else -> remaining
            }
            val requested = nextTransfer?.invoke(mode.ordinal, remaining, done) ?: fallback
            // Never divide one USB protocol frame or complete a full operation early.
            val count = when {
                mode == Mode.FRAME -> if (done == 0) remaining else 0
                requested == 0 && done > 0 && mode != Mode.FULL -> 0
                requested !in 1..remaining -> fallback
                mode == Mode.USB_CAPPED -> minOf(requested, 65536)
                else -> requested
            }
            if (count == 0) break
            val chunk = alignedChunk(count, remaining, packetSize)
            val actual = operation(done, chunk)
            if (actual <= 0) return if (done > 0 && mode != Mode.FULL) done else actual
            if (actual > chunk) throw IOException("Transport returned more bytes than requested")
            done += actual
        }
        return done
    }

    fun read(input: InputStream, data: ByteArray, offset: Int, length: Int, full: Boolean = false): Int =
        transfer(if (full) Mode.FULL else Mode.ONCE, length) { pos, size -> input.read(data, offset + pos, size) }

    fun write(output: OutputStream, data: ByteArray, offset: Int = 0, length: Int = data.size - offset) {
        transfer(Mode.FULL, length) { pos, size ->
            output.write(data, offset + pos, size)
            size
        }
    }

    companion object {
        val DEFAULT = TransportIo()
    }
}
