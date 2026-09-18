package `in`.aicortex.iso8583studio.domain.service.posSimulatorService

import java.nio.charset.StandardCharsets

/**
 * Small, strict ISO 8583 codec for the POS simulator.
 *
 * It intentionally uses the common test-bench representation: ASCII MTI, binary bitmap and ASCII
 * field values. This keeps captures readable and makes it interoperable with the application's
 * embedded host. Field definitions are centralized below so adding a host-specific field does not
 * require changing terminal logic.
 */
object Iso8583Codec {
    private data class FieldSpec(val fixedLength: Int? = null, val variableDigits: Int = 0)

    private val specs = mapOf(
        2 to FieldSpec(variableDigits = 2),
        3 to FieldSpec(fixedLength = 6),
        4 to FieldSpec(fixedLength = 12),
        5 to FieldSpec(fixedLength = 12),
        6 to FieldSpec(fixedLength = 12),
        7 to FieldSpec(fixedLength = 10),
        8 to FieldSpec(fixedLength = 8),
        9 to FieldSpec(fixedLength = 8),
        10 to FieldSpec(fixedLength = 8),
        11 to FieldSpec(fixedLength = 6),
        12 to FieldSpec(fixedLength = 6),
        13 to FieldSpec(fixedLength = 4),
        14 to FieldSpec(fixedLength = 4),
        15 to FieldSpec(fixedLength = 4),
        16 to FieldSpec(fixedLength = 4),
        17 to FieldSpec(fixedLength = 4),
        18 to FieldSpec(fixedLength = 4),
        19 to FieldSpec(fixedLength = 3),
        22 to FieldSpec(fixedLength = 3),
        23 to FieldSpec(fixedLength = 3),
        24 to FieldSpec(fixedLength = 3),
        25 to FieldSpec(fixedLength = 2),
        26 to FieldSpec(fixedLength = 2),
        28 to FieldSpec(fixedLength = 9),
        30 to FieldSpec(fixedLength = 9),
        32 to FieldSpec(variableDigits = 2),
        33 to FieldSpec(variableDigits = 2),
        35 to FieldSpec(variableDigits = 2),
        37 to FieldSpec(fixedLength = 12),
        38 to FieldSpec(fixedLength = 6),
        39 to FieldSpec(fixedLength = 2),
        41 to FieldSpec(fixedLength = 8),
        42 to FieldSpec(fixedLength = 15),
        43 to FieldSpec(fixedLength = 40),
        44 to FieldSpec(variableDigits = 2),
        48 to FieldSpec(variableDigits = 3),
        49 to FieldSpec(fixedLength = 3),
        52 to FieldSpec(fixedLength = 16),
        53 to FieldSpec(fixedLength = 16),
        54 to FieldSpec(variableDigits = 3),
        55 to FieldSpec(variableDigits = 3),
        56 to FieldSpec(variableDigits = 3),
        60 to FieldSpec(variableDigits = 3),
        61 to FieldSpec(variableDigits = 3),
        62 to FieldSpec(variableDigits = 3),
        63 to FieldSpec(variableDigits = 3),
        70 to FieldSpec(fixedLength = 3),
        90 to FieldSpec(fixedLength = 42),
    )

    fun encode(message: Iso8583Message): ByteArray {
        require(message.mti.length == 4 && message.mti.all { it.isDigit() }) { "MTI must be four digits" }
        val fields = message.fields.filterKeys { it in 2..128 }
        val secondary = fields.keys.any { it > 64 }
        val bitmap = ByteArray(if (secondary) 16 else 8)
        if (secondary) bitmap[0] = (bitmap[0].toInt() or 0x80).toByte()
        fields.keys.forEach { number ->
            require(number != 1) { "field 1 is generated from the bitmap" }
            val index = number - 1
            val byteIndex = index / 8
            val bit = 7 - (index % 8)
            if (byteIndex < bitmap.size) bitmap[byteIndex] = (bitmap[byteIndex].toInt() or (1 shl bit)).toByte()
        }

        val out = ByteArrayOutput()
        out.write(message.mti.toByteArray(StandardCharsets.US_ASCII))
        out.write(bitmap)
        fields.toSortedMap().forEach { (number, value) ->
            val spec = specs[number] ?: FieldSpec(variableDigits = 3)
            val normalized = if (spec.fixedLength != null) {
                require(value.length == spec.fixedLength) {
                    "field $number requires ${spec.fixedLength} chars, got ${value.length}"
                }
                value
            } else value
            if (spec.variableDigits > 0) {
                val max = when (spec.variableDigits) { 2 -> 99; else -> 999 }
                require(normalized.length <= max) { "field $number exceeds ${spec.variableDigits}-digit length" }
                out.write(normalized.length.toString().padStart(spec.variableDigits, '0').toByteArray(StandardCharsets.US_ASCII))
            }
            out.write(normalized.toByteArray(StandardCharsets.US_ASCII))
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): Iso8583Message {
        require(bytes.size >= 12) { "ISO8583 message is too short" }
        val ascii = String(bytes, StandardCharsets.US_ASCII)
        val mti = ascii.substring(0, 4)
        require(mti.all { it.isDigit() }) { "Only ASCII MTI is supported by this simulator codec" }
        var offset = 4
        val primary = bytes.copyOfRange(offset, offset + 8)
        offset += 8
        val bitmap = if ((primary[0].toInt() and 0x80) != 0) {
            require(bytes.size >= offset + 8) { "secondary bitmap is truncated" }
            primary + bytes.copyOfRange(offset, offset + 8).also { offset += 8 }
        } else primary
        val fields = linkedMapOf<Int, String>()
        for (number in 2..(if (bitmap.size == 16) 128 else 64)) {
            val index = number - 1
            val byteIndex = index / 8
            val bit = 7 - (index % 8)
            if ((bitmap[byteIndex].toInt() and 0xFF and (1 shl bit)) == 0) continue
            val spec = specs[number] ?: FieldSpec(variableDigits = 3)
            val length = if (spec.fixedLength != null) spec.fixedLength else {
                require(offset + spec.variableDigits <= bytes.size) { "field $number length is truncated" }
                val lengthText = String(bytes, offset, spec.variableDigits, StandardCharsets.US_ASCII)
                require(lengthText.all { it.isDigit() }) { "field $number has an invalid length" }
                offset += spec.variableDigits
                lengthText.toInt()
            }
            require(offset + length <= bytes.size) { "field $number is truncated" }
            fields[number] = String(bytes, offset, length, StandardCharsets.US_ASCII)
            offset += length
        }
        return Iso8583Message(mti, fields)
    }

    fun display(message: Iso8583Message): String = buildString {
        append("MTI: ").append(message.mti).append('\n')
        message.fields.toSortedMap().forEach { (field, value) ->
            append("DE ").append(field.toString().padStart(3, '0')).append(": ").append(value).append('\n')
        }
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    private class ByteArrayOutput {
        private var data = ByteArray(128)
        private var size = 0
        fun write(bytes: ByteArray) {
            if (size + bytes.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + bytes.size))
            bytes.copyInto(data, size)
            size += bytes.size
        }
        fun toByteArray(): ByteArray = data.copyOf(size)
    }
}

/** Length framing used by TCP test benches. */
internal object POSFrameCodec {
    fun encode(payload: ByteArray, format: POSFrameFormat): ByteArray = when (format) {
        POSFrameFormat.NONE -> payload
        POSFrameFormat.ASCII_4 -> payload.size.toString().padStart(4, '0').toByteArray(StandardCharsets.US_ASCII) + payload
        POSFrameFormat.BINARY_2 -> byteArrayOf((payload.size ushr 8).toByte(), payload.size.toByte()) + payload
        POSFrameFormat.BCD_2 -> {
            require(payload.size <= 9999) { "BCD frame payload too large" }
            val text = payload.size.toString().padStart(4, '0')
            byteArrayOf(
                ((text[0].digitToInt() shl 4) or text[1].digitToInt()).toByte(),
                ((text[2].digitToInt() shl 4) or text[3].digitToInt()).toByte(),
            ) + payload
        }
    }

    fun readLength(header: ByteArray, format: POSFrameFormat): Int = when (format) {
        POSFrameFormat.NONE -> -1
        POSFrameFormat.ASCII_4 -> String(header, StandardCharsets.US_ASCII).toInt()
        POSFrameFormat.BINARY_2 -> ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        POSFrameFormat.BCD_2 -> {
            val a = header[0].toInt() and 0xFF
            val b = header[1].toInt() and 0xFF
            (((a ushr 4) and 0xF) * 1000 + (a and 0xF) * 100 +
                ((b ushr 4) and 0xF) * 10 + (b and 0xF))
        }
    }

    fun headerSize(format: POSFrameFormat): Int = when (format) {
        POSFrameFormat.NONE -> 0
        POSFrameFormat.ASCII_4 -> 4
        else -> 2
    }
}
