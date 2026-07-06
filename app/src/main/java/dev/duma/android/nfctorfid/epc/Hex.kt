package dev.duma.android.nfctorfid.epc

fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

/** Display form for UIDs: `04:A2:2E:5B:33:88:41`. */
fun ByteArray.toColonHex(): String = joinToString(":") { "%02X".format(it) }

/** Reformats a plain hex string into colon-separated display form; returns input when not hex. */
fun String.colonizeHex(): String = hexToBytesOrNull()?.toColonHex() ?: this

/** Parses a hex string, ignoring spaces; returns null when malformed. */
fun String.hexToBytesOrNull(): ByteArray? {
    val clean = replace(" ", "").uppercase()
    if (clean.isEmpty() || clean.length % 2 != 0) return null
    if (!clean.all { it in '0'..'9' || it in 'A'..'F' }) return null
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
