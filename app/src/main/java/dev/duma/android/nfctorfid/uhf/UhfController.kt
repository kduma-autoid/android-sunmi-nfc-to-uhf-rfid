package dev.duma.android.nfctorfid.uhf

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Which UHF reader drives the app; AUTO probes USB → Sunmi → saved BLE device. */
enum class ReaderBackend { AUTO, SUNMI, CHAINWAY_USB, CHAINWAY_BLE }

/**
 * Inventory mode — maps to the EPC Gen2 session used while scanning, which
 * controls how long a tag stays silent after it has been read once.
 */
enum class InventoryMode(val gen2Session: Int) {
    /** Session S0: tags answer continuously — fastest re-reads, live RSSI. */
    HIGH_SPEED(0),

    /** Session S1: short (~0.5–5 s) silence after a read — fresh reads still repeat. */
    BALANCE(1),

    /** Session S2: long silence after a read — each tag answers once, best for bulk. */
    TRAVERSAL(2),
}

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
     * Applies the inventory mode used by [scanTags]; best-effort. The Scan tab
     * applies the configured mode for the duration of its scan and resets to
     * [InventoryMode.HIGH_SPEED] afterwards; encode/validate scans stay in high
     * speed. The post-write verification forces high speed regardless — a tag
     * silenced by S1/S2 persistence would fail verification falsely.
     */
    suspend fun applyInventoryMode(mode: InventoryMode)

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
