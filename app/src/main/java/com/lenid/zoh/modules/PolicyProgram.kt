package com.lenid.zoh.modules

/** Each exported WebAssembly function is bounded and pure. No imports or memory. */
internal class PolicyProgram private constructor(private val code: IntArray, private val arity: Int) {
    fun run(args: IntArray): Int {
        require(args.size == arity)
        val stack = IntArray(32)
        var size = 0
        var pc = 0
        while (pc < code.size) {
            when (val op = code[pc++]) {
                LOCAL -> stack[size++] = args[code[pc++]]
                CONST -> stack[size++] = code[pc++]
                EQZ -> stack[size - 1] = if (stack[size - 1] == 0) 1 else 0
                SELECT -> {
                    val condition = stack[--size]
                    val second = stack[--size]
                    if (condition == 0) stack[size - 1] = second
                }
                else -> {
                    val b = stack[--size]
                    val a = stack[size - 1]
                    stack[size - 1] = when (op) {
                        0x48 -> if (a < b) 1 else 0
                        0x4a -> if (a > b) 1 else 0
                        0x4c -> if (a <= b) 1 else 0
                        0x4e -> if (a >= b) 1 else 0
                        0x46 -> if (a == b) 1 else 0
                        0x6a -> a + b
                        0x6b -> a - b
                        0x6c -> a * b
                        0x71 -> a and b
                        0x72 -> a or b
                        else -> error("Invalid compiled opcode")
                    }
                }
            }
        }
        return stack[0]
    }

    companion object {
        private const val LOCAL = 0x20
        private const val CONST = 0x41
        private const val EQZ = 0x45
        private const val SELECT = 0x1b
        private val binary = setOf(0x46, 0x48, 0x4a, 0x4c, 0x4e, 0x6a, 0x6b, 0x6c, 0x71, 0x72)
        fun decode(bytes: ByteArray, exports: Map<String, Int>): Map<String, PolicyProgram> {
            require(bytes.size <= 4096 && exports.size in 1..32)
            val reader = Reader(bytes)
            reader.expect(0, 97, 115, 109, 1, 0, 0, 0)
            val types = reader.section(1)
            val typeCount = types.number(false)
            require(typeCount in 1..exports.size)
            val arities = List(typeCount) {
                types.expect(0x60)
                val arity = types.number(false)
                require(arity in 1..8)
                repeat(arity) { types.expect(0x7f) }
                types.expect(1, 0x7f)
                arity
            }
            types.finish()
            val functions = reader.section(3)
            functions.expect(exports.size)
            val signatures = List(exports.size) { arities[functions.number(false)] }
            functions.finish()
            val names = reader.section(7)
            names.expect(exports.size)
            val namesByIndex = arrayOfNulls<String>(exports.size)
            repeat(exports.size) {
                val length = names.number(false)
                require(length in 1..48)
                val name = String(ByteArray(length) { names.byte().toByte() }, Charsets.US_ASCII)
                names.expect(0)
                val index = names.number(false)
                require(index in signatures.indices && namesByIndex[index] == null &&
                    name !in namesByIndex && exports[name] == signatures[index])
                namesByIndex[index] = name
            }
            names.finish()
            val bodies = reader.section(10)
            bodies.expect(exports.size)
            val programs = signatures.indices.associate { index ->
                checkNotNull(namesByIndex[index]) to decodeBody(bodies.block(), signatures[index])
            }
            bodies.finish()
            reader.finish()
            return programs
        }

        private fun decodeBody(body: Reader, arity: Int): PolicyProgram {
            body.expect(0)
            val instructions = mutableListOf<Int>()
            var height = 0
            while (true) {
                val op = body.byte()
                if (op == 0x0b) break
                require(instructions.size < 256)
                instructions.add(op)
                when (op) {
                    CONST -> { instructions.add(body.number(true)); height++ }
                    LOCAL -> { instructions.add(body.number(false).also { require(it in 0 until arity) }); height++ }
                    EQZ -> require(height >= 1)
                    SELECT -> { require(height >= 3); height -= 2 }
                    in binary -> { require(height >= 2); height-- }
                    else -> error("Unsupported policy instruction")
                }
                require(height <= 32)
            }
            require(height == 1)
            body.finish()
            return PolicyProgram(instructions.toIntArray(), arity)
        }
    }

    private class Reader(val bytes: ByteArray, var offset: Int = 0, val end: Int = bytes.size) {
        fun byte(): Int { require(offset < end); return bytes[offset++].toInt() and 255 }
        fun expect(vararg values: Int) = values.forEach { require(byte() == it) }
        fun finish() = require(offset == end)
        fun number(signed: Boolean): Int {
            var n = 0L
            for (i in 0..4) {
                val b = byte()
                val shift = i * 7
                n = n or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) {
                    if (signed && b and 64 != 0) n = n or (-1L shl (shift + 7))
                    require(n in (if (signed) Int.MIN_VALUE.toLong() else 0L)..Int.MAX_VALUE.toLong())
                    return n.toInt()
                }
            }
            error("Invalid integer")
        }
        fun block(): Reader {
            val length = number(false)
            require(length <= end - offset)
            return Reader(bytes, offset, offset + length).also { offset += length }
        }
        fun section(id: Int): Reader { expect(id); return block() }
    }
}
