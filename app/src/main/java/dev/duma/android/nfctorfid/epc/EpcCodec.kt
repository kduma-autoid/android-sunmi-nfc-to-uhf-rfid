package dev.duma.android.nfctorfid.epc

/**
 * EPC layout used by this app (96-bit / 6 words):
 *
 * ```
 * byte 0      : 0x4E  — application magic ('N')
 * byte 1      : NFC UID length in bytes (0x04 / 0x07 / 0x0A)
 * bytes 2..11 : NFC UID, left-aligned, zero-padded
 * ```
 *
 * The PC word is written together with the EPC so re-encoding works regardless of
 * the tag's previous EPC length. T (toggle) bit is set: the tag declares a
 * non-GS1 (ISO 15961, AFI=0x00) identifier, so the encoding can never collide
 * with present or future GS1 EPC schemes.
 */
object EpcCodec {

    const val PREFIX: Byte = 0x4E
    const val EPC_BYTES = 12
    const val EPC_WORDS = 6

    /** L=6 words (bits 15–11), UMI=0, XI=0, T=1 (non-GS1), AFI=0x00 (bits 7–0). */
    const val PC_WORD = 0x3100

    val VALID_UID_LENGTHS = setOf(4, 7, 10)

    fun isValidUid(uid: ByteArray): Boolean = uid.size in VALID_UID_LENGTHS

    /** True for ISO 14443-4 random IDs (4 bytes starting with 0x08) — unusable as identifiers. */
    fun isRandomUid(uid: ByteArray): Boolean = uid.size == 4 && uid[0] == 0x08.toByte()

    /** 12-byte EPC for the given NFC UID. */
    fun encodeEpc(uid: ByteArray): ByteArray {
        require(isValidUid(uid)) { "Unsupported UID length: ${uid.size}" }
        val epc = ByteArray(EPC_BYTES)
        epc[0] = PREFIX
        epc[1] = uid.size.toByte()
        uid.copyInto(epc, 2)
        return epc
    }

    /** PC word + EPC (14 bytes), ready for writeTag(bank=EPC, wordAdd=0x01, wordCnt=7). */
    fun encodePcAndEpc(uid: ByteArray): ByteArray {
        val out = ByteArray(2 + EPC_BYTES)
        out[0] = (PC_WORD shr 8).toByte()
        out[1] = (PC_WORD and 0xFF).toByte()
        encodeEpc(uid).copyInto(out, 2)
        return out
    }

    /** Extracts the NFC UID from an EPC produced by [encodeEpc]; null when not ours. */
    fun decodeUid(epc: ByteArray): ByteArray? {
        if (epc.size != EPC_BYTES) return null
        if (epc[0] != PREFIX) return null
        val len = epc[1].toInt()
        if (len !in VALID_UID_LENGTHS) return null
        for (i in 2 + len until EPC_BYTES) {
            if (epc[i] != 0.toByte()) return null
        }
        return epc.copyOfRange(2, 2 + len)
    }

    fun decodeUidHex(epcHex: String): String? {
        val bytes = epcHex.hexToBytesOrNull() ?: return null
        return decodeUid(bytes)?.toHex()
    }
}
