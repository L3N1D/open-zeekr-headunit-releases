package com.shilapi.xcertplay.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

internal object LabBridgeFraming {
    const val MAX_BYTES = 262144
    fun read(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size !in 1..MAX_BYTES) throw IOException("Invalid bridge reply length")
        return ByteArray(size).also(input::readFully)
    }
    fun write(output: DataOutputStream, data: ByteArray) {
        require(data.size in 1..MAX_BYTES)
        output.writeInt(data.size); output.write(data); output.flush()
    }
}
