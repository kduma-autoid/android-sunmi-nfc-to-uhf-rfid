package dev.duma.android.nfctorfid.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import dev.duma.android.nfctorfid.epc.EpcCodec

/**
 * Reads NFC-A card UIDs with reader mode (no intent dispatch).
 * Callbacks arrive on the NFC dispatch thread — post to main before touching views.
 */
class NfcCardReader(
    private val activity: Activity,
    private val listener: Listener,
) {

    interface Listener {
        fun onCardRead(uid: ByteArray, originality: OriginalityStatus)

        /** ISO 14443-4 random ID (bank cards etc.) — changes on every tap, unusable. */
        fun onRandomUid()

        fun onUnsupportedUid(length: Int)
    }

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private val callback = NfcAdapter.ReaderCallback { tag ->
        val id = tag.id
        when {
            id == null || id.isEmpty() -> listener.onUnsupportedUid(0)
            EpcCodec.isRandomUid(id) -> listener.onRandomUid()
            !EpcCodec.isValidUid(id) -> listener.onUnsupportedUid(id.size)
            // Originality must be read while the tag is still in the field.
            else -> listener.onCardRead(id, OriginalityChecker.check(tag))
        }
    }

    fun enable() {
        adapter?.enableReaderMode(
            activity,
            callback,
            NfcAdapter.FLAG_READER_NFC_A
                or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
                or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null,
        )
    }

    fun disable() {
        adapter?.disableReaderMode(activity)
    }
}
