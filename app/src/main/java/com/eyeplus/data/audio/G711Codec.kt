package com.eyeplus.data.audio

/**
 * Pure Kotlin G.711 codec — A-law and μ-law encoding/decoding.
 * No external dependencies needed.
 */
object G711Codec {

    // ─── μ-law (used by most IP cameras for audio backchannel) ───

    private val MU_LAW_COMPRESS_TABLE = intArrayOf(
        0,0,1,1,2,2,2,2,3,3,3,3,3,3,3,3,
        4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,4,
        5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
        5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7
    )

    private val MU_LAW_EXPAND_TABLE = shortArrayOf(
        -8031, -7775, -7519, -7263, -7007, -6751, -6495, -6239,
        -5983, -5727, -5471, -5215, -4959, -4703, -4447, -4191,
        -3999, -3903, -3807, -3711, -3615, -3519, -3423, -3327,
        -3231, -3135, -3039, -2943, -2847, -2751, -2655, -2559,
        -2495, -2447, -2399, -2351, -2303, -2255, -2207, -2159,
        -2111, -2063, -2015, -1967, -1919, -1871, -1823, -1775,
        -1743, -1719, -1695, -1671, -1647, -1623, -1599, -1575,
        -1551, -1527, -1503, -1479, -1455, -1431, -1407, -1383,
        -1359, -1335, -1311, -1287, -1263, -1239, -1215, -1191,
        -1167, -1143, -1119, -1095, -1071, -1047, -1023, -999,
        -975, -951, -927, -903, -879, -855, -831, -807,
        -783, -759, -735, -711, -687, -663, -639, -615,
        -591, -567, -543, -519, -495, -471, -447, -423,
        -399, -375, -351, -327, -303, -279, -255, -231,
        -207, -183, -159, -135, -111, -87, -63, -39,
        -16,  -1,   0,   1,   16,  39,  63,  87,
        111, 135, 159, 183, 207, 231, 255, 279,
        303, 327, 351, 375, 399, 423, 447, 471,
        495, 519, 543, 567, 591, 615, 639, 663,
        687, 711, 735, 759, 783, 807, 831, 855,
        879, 903, 927, 951, 975, 999, 1023, 1047,
        1071, 1095, 1119, 1143, 1167, 1191, 1215, 1239,
        1263, 1287, 1311, 1335, 1359, 1383, 1407, 1431,
        1455, 1479, 1503, 1527, 1551, 1575, 1599, 1623,
        1647, 1671, 1695, 1719, 1743, 1775, 1823, 1871,
        1919, 1967, 2015, 2063, 2111, 2159, 2207, 2255,
        2303, 2351, 2399, 2447, 2495, 2559, 2655, 2751,
        2847, 2943, 3039, 3135, 3231, 3327, 3423, 3519,
        3615, 3711, 3807, 3903, 3999, 4191, 4447, 4703,
        4959, 5215, 5471, 5727, 5983, 6239, 6495, 6751,
        7007, 7263, 7519, 7775, 8031
    )

    /** Encode 16-bit PCM sample to 8-bit μ-law */
    fun encodeULaw(pcm: Int): Byte {
        val sign = (pcm shr 8) and 0x80
        var magnitude = if (pcm < 0) -pcm else pcm
        if (magnitude > 32767) magnitude = 32767
        magnitude = (magnitude + 0x84) shr 2  // add bias
        val segment = MU_LAW_COMPRESS_TABLE[magnitude shr 7]
        val adjusted = (magnitude shr (segment + 3)) and 0x0F
        return ((sign or (segment shl 4) or adjusted).inv()).toByte()
    }

    /** Decode 8-bit μ-law to 16-bit PCM */
    fun decodeULaw(ulaw: Byte): Short {
        val u = ulaw.toInt() and 0xFF
        val inverted = u.inv() and 0xFF
        return MU_LAW_EXPAND_TABLE[inverted]
    }

    /** Encode a buffer of 16-bit PCM samples to μ-law */
    fun encodeULaw(pcmBuffer: ShortArray): ByteArray {
        val result = ByteArray(pcmBuffer.size)
        for (i in pcmBuffer.indices) {
            result[i] = encodeULaw(pcmBuffer[i].toInt())
        }
        return result
    }

    /** Decode μ-law buffer to 16-bit PCM */
    fun decodeULaw(ulawBuffer: ByteArray): ShortArray {
        val result = ShortArray(ulawBuffer.size)
        for (i in ulawBuffer.indices) {
            result[i] = decodeULaw(ulawBuffer[i])
        }
        return result
    }

    // ─── A-law ───

    private val A_LAW_COMPRESS_TABLE = intArrayOf(
        1,1,2,2,3,3,3,3,4,4,4,4,4,4,4,4,
        5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,5,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,6,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,
        7,7,7,7,7,7,7,7,7,7,7,7,7,7,7,7
    )

    private val A_LAW_EXPAND_TABLE = shortArrayOf(
        -5504, -5248, -6016, -5760, -4480, -4224, -4992, -4736,
        -7552, -7296, -8064, -7808, -6528, -6272, -7040, -6784,
        -2752, -2624, -3008, -2880, -2240, -2112, -2496, -2368,
        -3776, -3648, -4032, -3904, -3264, -3136, -3520, -3392,
        -2208, -2112, -2400, -2304, -1824, -1728, -2016, -1920,
        -2976, -2880, -3168, -3072, -2592, -2496, -2784, -2688,
        -1376, -1312, -1504, -1440, -1120, -1056, -1248, -1184,
        -1888, -1824, -2016, -1952, -1632, -1568, -1760, -1696,
        -1104, -1056, -1200, -1152, -912, -864, -1008, -960,
        -1488, -1440, -1584, -1536, -1296, -1248, -1392, -1344,
        -688, -656, -752, -720, -560, -528, -624, -592,
        -944, -912, -1008, -976, -816, -784, -880, -848,
        -352, -336, -384, -368, -288, -272, -320, -304,
        -480, -464, -512, -496, -416, -400, -448, -432,
        -176, -168, -192, -184, -144, -136, -160, -152,
        -240, -232, -256, -248, -208, -200, -224, -216,
        216, 224, 200, 208, 248, 256, 232, 240,
        152, 160, 136, 144, 184, 192, 168, 176,
        432, 448, 400, 416, 496, 512, 464, 480,
        304, 320, 272, 288, 368, 384, 336, 352,
        848, 880, 784, 816, 976, 1008, 912, 944,
        592, 624, 528, 560, 720, 752, 656, 688,
        1344, 1392, 1248, 1296, 1536, 1584, 1440, 1488,
        960, 1008, 864, 912, 1152, 1200, 1056, 1104,
        1696, 1760, 1568, 1632, 1952, 2016, 1824, 1888,
        1184, 1248, 1056, 1120, 1440, 1504, 1312, 1376,
        2688, 2784, 2496, 2592, 3072, 3168, 2880, 2976,
        1920, 2016, 1728, 1824, 2304, 2400, 2112, 2208,
        3392, 3520, 3136, 3264, 3904, 4032, 3648, 3776,
        2368, 2496, 2112, 2240, 2880, 3008, 2624, 2752,
        6784, 7040, 6272, 6528, 7808, 8064, 7296, 7552,
        4736, 4992, 4224, 4480, 5760, 6016, 5248, 5504
    )

    /** Encode 16-bit PCM to 8-bit A-law */
    fun encodeALaw(pcm: Int): Byte {
        val sign = (pcm shr 8) and 0x80
        var magnitude = if (pcm < 0) -pcm else pcm
        if (magnitude > 32767) magnitude = 32767
        val segment = A_LAW_COMPRESS_TABLE[magnitude shr 7]
        val adjusted = (magnitude shr (segment + 3)) and 0x0F
        return ((sign or (segment shl 4) or adjusted).inv()).toByte()
    }

    /** Decode 8-bit A-law to 16-bit PCM */
    fun decodeALaw(aLaw: Byte): Short {
        val a = aLaw.toInt() and 0xFF
        val inverted = a.inv() and 0xFF
        return A_LAW_EXPAND_TABLE[inverted]
    }

    /** Encode PCM buffer to A-law */
    fun encodeALaw(pcmBuffer: ShortArray): ByteArray {
        val result = ByteArray(pcmBuffer.size)
        for (i in pcmBuffer.indices) {
            result[i] = encodeALaw(pcmBuffer[i].toInt())
        }
        return result
    }

    /** Decode A-law buffer to PCM */
    fun decodeALaw(aLawBuffer: ByteArray): ShortArray {
        val result = ShortArray(aLawBuffer.size)
        for (i in aLawBuffer.indices) {
            result[i] = decodeALaw(aLawBuffer[i])
        }
        return result
    }
}
