package dev.duma.android.nfctorfid.uhf

import android.content.Context
import dev.duma.android.nfctorfid.R

/** Maps controller exceptions to localized, user-facing text. */
fun Throwable.toUserMessage(context: Context): String = when (this) {
    is UhfError.NotReady -> context.getString(R.string.error_reader_not_ready)
    is UhfError.ServiceLost -> context.getString(R.string.error_service_lost)
    is UhfError.Timeout -> context.getString(R.string.error_timeout)
    is UhfError.DuplicateTag -> context.getString(R.string.error_duplicate_tag)
    is UhfError.VerifyFailed -> context.getString(R.string.error_verify_failed)
    is UhfError.Command -> when (code) {
        UhfError.ERR_NO_TAG,
        UhfError.ERR_INVENTORY_OK_ACCESS_FAIL,
        -> context.getString(R.string.error_tag_gone)
        UhfError.ERR_ACCESS_FAIL -> context.getString(R.string.error_access_failed)
        UhfError.ERR_TAG_WRITE -> context.getString(R.string.error_write_failed)
        else -> context.getString(R.string.error_reader_code, code, protocolMessage ?: "")
    }
    else -> message ?: context.getString(R.string.error_unknown)
}
