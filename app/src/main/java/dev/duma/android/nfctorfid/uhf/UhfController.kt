package dev.duma.android.nfctorfid.uhf

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Which UHF reader drives the app; AUTO probes USB → Sunmi → saved BLE device. */
enum class ReaderBackend { AUTO, SUNMI, CHAINWAY_USB, CHAINWAY_BLE }

/**
 * One UHF reader. Implementations own their vendor SDK singleton and expose the
 * full tag lifecycle the app needs: inventory windows and the encode sequence
 * (write EPC → write passwords → lock → verify).
 */
interface UhfController {

    sealed class ReaderState {
        data object Disconnected : ReaderState()
        data object Connecting : ReaderState()
        data object NoReader : ReaderState()
        data class Ready(val description: String) : ReaderState()
    }

    val backend: ReaderBackend

    val state: StateFlow<ReaderState>

    /**
     * Hardware trigger events reported by the reader itself (Chainway handhelds):
     * true = pressed, false = released. Sunmi devices report the trigger as a key
     * event instead, so this flow stays silent there.
     */
    val triggerEvents: SharedFlow<Boolean>

    /** Starts connecting; safe to call repeatedly. */
    fun connect()

    fun disconnect()

    /** Suspends until the reader is ready; throws [UhfError.NotReady] otherwise. */
    suspend fun awaitReady(timeoutMs: Long = 7_000)

    /** Applies RF output power (dBm); errors are swallowed — power is best-effort. */
    suspend fun applyPower(dbm: Int)

    /**
     * Runs inventory rounds until [durationMs] elapses and returns unique tags by EPC.
     * [onUpdate] fires on a worker thread after each new observation.
     */
    suspend fun scanTags(
        durationMs: Long,
        onUpdate: ((Map<String, TagInfo>) -> Unit)? = null,
    ): Map<String, TagInfo>

    /**
     * Full encode sequence against the single tag currently in field:
     * write EPC (skippable) → write kill+access passwords → lock banks → verify.
     * Throws [UhfError]; partially-completed sequences are safe to re-run.
     */
    suspend fun writeAndLock(
        currentEpcHex: String,
        uid: ByteArray,
        accessPwd: ByteArray?,
        killPwd: ByteArray?,
        lockEnabled: Boolean,
        skipEpcWrite: Boolean,
        readPowerDbm: Int? = null,
        writePowerDbm: Int? = null,
        onStep: (WriteStep) -> Unit = {},
    )
}
