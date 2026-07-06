package dev.duma.android.nfctorfid.nfc

import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Reads the NXP originality signature from a tag that is still in the field
 * (call from the reader-mode callback) and verifies it with [OriginalityVerifier].
 *
 * Transient I/O failures are retried with a reconnect — [OriginalityStatus.NotSupported]
 * is reported only when the card explicitly refuses the command (status word),
 * never for a broken transmission.
 */
object OriginalityChecker {

    /** DESFire Read_Sig wrapped in ISO 7816: 90 3C 00 00 01 00 00. */
    private val DESFIRE_READ_SIG =
        byteArrayOf(0x90.toByte(), 0x3C, 0x00, 0x00, 0x01, 0x00, 0x00)

    /** DESFire "additional frame" continuation: 90 AF 00 00 00. */
    private val DESFIRE_ADDITIONAL_FRAME =
        byteArrayOf(0x90.toByte(), 0xAF.toByte(), 0x00, 0x00, 0x00)

    /** NTAG21x / Ultralight READ_SIG: 3C 00. */
    private val NFCA_READ_SIG = byteArrayOf(0x3C, 0x00)

    private const val TRANSCEIVE_TIMEOUT_MS = 1_000
    private const val ATTEMPTS = 3
    private const val RETRY_DELAY_MS = 40L

    fun check(tag: Tag): OriginalityStatus {
        val uid = tag.id ?: return OriginalityStatus.NotSupported
        IsoDep.get(tag)?.let { tech ->
            return try {
                checkIsoDep(tech, uid)
            } finally {
                runCatching { tech.close() }
            }
        }
        NfcA.get(tag)?.let { tech ->
            return try {
                checkNfcA(tech, uid)
            } finally {
                runCatching { tech.close() }
            }
        }
        return OriginalityStatus.NotSupported
    }

    private fun checkIsoDep(isoDep: IsoDep, uid: ByteArray): OriginalityStatus {
        repeat(ATTEMPTS) { attempt ->
            try {
                if (!isoDep.isConnected) isoDep.connect()
                isoDep.timeout = TRANSCEIVE_TIMEOUT_MS
                val signature = readDesfireSignature(isoDep)
                    ?: return OriginalityStatus.NotSupported // card answered: no such command
                return OriginalityVerifier.match(uid, signature)
            } catch (e: TagLostException) {
                // Card left the field — retrying without the card is pointless.
                return OriginalityStatus.ReadError
            } catch (e: IOException) {
                // Transmission glitch — reconnect and retry.
                runCatching { isoDep.close() }
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return OriginalityStatus.ReadError
    }

    /**
     * Runs Read_Sig, following 91 AF additional frames; returns the signature
     * bytes, or null when the card refuses the command with an error status.
     */
    private fun readDesfireSignature(isoDep: IsoDep): ByteArray? {
        val out = ByteArrayOutputStream()
        var response = isoDep.transceive(DESFIRE_READ_SIG)
        while (true) {
            val n = response.size
            if (n < 2 || response[n - 2] != 0x91.toByte()) return null
            out.write(response, 0, n - 2)
            when (response[n - 1]) {
                0x00.toByte() -> return out.toByteArray()
                0xAF.toByte() -> response = isoDep.transceive(DESFIRE_ADDITIONAL_FRAME)
                else -> return null // e.g. 91 1C ILLEGAL_COMMAND — genuinely unsupported
            }
        }
    }

    private fun checkNfcA(nfcA: NfcA, uid: ByteArray): OriginalityStatus {
        repeat(ATTEMPTS) { attempt ->
            try {
                if (!nfcA.isConnected) nfcA.connect()
                nfcA.timeout = TRANSCEIVE_TIMEOUT_MS
                val response = nfcA.transceive(NFCA_READ_SIG)
                // A NAK sometimes surfaces as a short (1-byte) response.
                if (response.size < 32) return OriginalityStatus.NotSupported
                return OriginalityVerifier.match(uid, response)
            } catch (e: TagLostException) {
                return OriginalityStatus.ReadError
            } catch (e: IOException) {
                // NfcA cannot distinguish a NAK (command unsupported, e.g. MIFARE
                // Classic) from an RF glitch — retry, then classify as unsupported.
                runCatching { nfcA.close() }
                if (attempt < ATTEMPTS - 1) Thread.sleep(RETRY_DELAY_MS)
            }
        }
        return OriginalityStatus.NotSupported
    }
}
