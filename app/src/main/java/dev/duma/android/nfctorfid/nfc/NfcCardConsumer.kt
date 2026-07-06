package dev.duma.android.nfctorfid.nfc

/**
 * Implemented by fragments that react to NFC cards. MainActivity owns the single
 * reader-mode session and forwards events to the currently visible consumer.
 * Calls arrive on the NFC dispatch thread — post to main before touching views.
 */
interface NfcCardConsumer {
    fun onNfcCard(uid: ByteArray, originality: OriginalityStatus)

    fun onNfcRandomUid() {}

    fun onNfcUnsupportedUid(length: Int) {}
}
