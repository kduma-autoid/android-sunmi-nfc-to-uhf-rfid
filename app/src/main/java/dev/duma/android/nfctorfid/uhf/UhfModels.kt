package dev.duma.android.nfctorfid.uhf

/** One unique tag observed during an inventory window. */
data class TagInfo(
    val epcHex: String,
    val pcHex: String,
    val rssiDbm: Int?,
    val readCount: Int,
)

sealed class UhfError(message: String) : Exception(message) {
    class NotReady(message: String = "RFID service is not connected") : UhfError(message)
    class ServiceLost : UhfError("RFID service connection lost")
    class Timeout(val operation: String) : UhfError("Operation timed out: $operation")

    /** Post-write check found the old EPC still in field — an identical-EPC twin took the write. */
    class DuplicateTag : UhfError("a tag with the old EPC is still in range")

    /** Post-write read-back did not match the expected EPC/PC. */
    class VerifyFailed(message: String) : UhfError(message)

    class Command(val code: Int, val protocolMessage: String?) :
        UhfError("Reader error 0x%02X: %s".format(code, protocolMessage ?: describe(code)))

    companion object {
        // Protocol error codes (Rodinbell/Invelion family); the SDK exposes no constants for these.
        const val ERR_TAG_WRITE = 0x33
        const val ERR_NO_TAG = 0x36
        const val ERR_INVENTORY_OK_ACCESS_FAIL = 0x37
        const val ERR_ACCESS_FAIL = 0x40
        const val ERR_INVALID_PARAMETER = 0x41
        const val ERR_POWER_OUT_OF_RANGE = 0x48

        fun describe(code: Int): String = when (code) {
            ERR_TAG_WRITE -> "tag write failed (locked bank or tag left the field)"
            ERR_NO_TAG -> "no operable tag in range"
            ERR_INVENTORY_OK_ACCESS_FAIL -> "tag found but access failed (tag left the field?)"
            ERR_ACCESS_FAIL -> "access failed (locked bank or wrong password)"
            ERR_INVALID_PARAMETER -> "invalid parameter"
            ERR_POWER_OUT_OF_RANGE -> "output power out of range for this module"
            else -> "reader error"
        }
    }
}

/** Progress steps reported by [UhfController.writeAndLock]. */
enum class WriteStep {
    TARGETING,
    WRITING_EPC,
    WRITING_PASSWORDS,
    LOCKING,
    VERIFYING,
}
