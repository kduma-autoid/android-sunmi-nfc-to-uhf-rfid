package dev.duma.android.nfctorfid

import android.content.Context
import androidx.core.content.edit
import dev.duma.android.nfctorfid.epc.hexToBytesOrNull
import dev.duma.android.nfctorfid.uhf.InventoryMode
import dev.duma.android.nfctorfid.uhf.ReaderBackend

class AppSettings(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** 8-hex-digit access password, empty = not configured. */
    var accessPasswordHex: String
        get() = prefs.getString(KEY_ACCESS_PWD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ACCESS_PWD, value) }

    /** 8-hex-digit kill password, empty = not configured. */
    var killPasswordHex: String
        get() = prefs.getString(KEY_KILL_PWD, "") ?: ""
        set(value) = prefs.edit { putString(KEY_KILL_PWD, value) }

    var lockEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOCK_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_LOCK_ENABLED, value) }

    /** UHF scan window in seconds (2–10). */
    var scanSeconds: Int
        get() = prefs.getInt(KEY_SCAN_SECONDS, DEFAULT_SCAN_SECONDS).coerceIn(MIN_SCAN_SECONDS, MAX_SCAN_SECONDS)
        set(value) = prefs.edit { putInt(KEY_SCAN_SECONDS, value.coerceIn(MIN_SCAN_SECONDS, MAX_SCAN_SECONDS)) }

    /** RF output power for inventory/scanning in dBm — kept low to shrink the field. */
    var readPowerDbm: Int
        get() = prefs.getInt(KEY_READ_POWER_DBM, DEFAULT_READ_POWER_DBM).coerceIn(MIN_POWER_DBM, MAX_POWER_DBM)
        set(value) = prefs.edit { putInt(KEY_READ_POWER_DBM, value.coerceIn(MIN_POWER_DBM, MAX_POWER_DBM)) }

    /** RF output power for write/lock operations in dBm — writes target one EPC anyway. */
    var writePowerDbm: Int
        get() = prefs.getInt(KEY_WRITE_POWER_DBM, DEFAULT_WRITE_POWER_DBM).coerceIn(MIN_POWER_DBM, MAX_POWER_DBM)
        set(value) = prefs.edit { putInt(KEY_WRITE_POWER_DBM, value.coerceIn(MIN_POWER_DBM, MAX_POWER_DBM)) }

    var continuousMode: Boolean
        get() = prefs.getBoolean(KEY_CONTINUOUS, false)
        set(value) = prefs.edit { putBoolean(KEY_CONTINUOUS, value) }

    /** Which UHF reader to use; AUTO probes USB → Sunmi → saved BLE device. */
    var backend: ReaderBackend
        get() = prefs.getString(KEY_BACKEND, null)
            ?.let { runCatching { ReaderBackend.valueOf(it) }.getOrNull() }
            ?: ReaderBackend.AUTO
        set(value) = prefs.edit { putString(KEY_BACKEND, value.name) }

    /** MAC of the last chosen Chainway Bluetooth reader. */
    var bleMac: String?
        get() = prefs.getString(KEY_BLE_MAC, null)
        set(value) = prefs.edit { putString(KEY_BLE_MAC, value) }

    var bleName: String?
        get() = prefs.getString(KEY_BLE_NAME, null)
        set(value) = prefs.edit { putString(KEY_BLE_NAME, value) }

    /** Gen2 session preset used while scanning; see [InventoryMode]. */
    var inventoryMode: InventoryMode
        get() = prefs.getString(KEY_INVENTORY_MODE, null)
            ?.let { runCatching { InventoryMode.valueOf(it) }.getOrNull() }
            ?: InventoryMode.HIGH_SPEED
        set(value) = prefs.edit { putString(KEY_INVENTORY_MODE, value.name) }

    fun accessPasswordBytes(): ByteArray? = passwordBytes(accessPasswordHex)

    fun killPasswordBytes(): ByteArray? = passwordBytes(killPasswordHex)

    companion object {
        const val MIN_SCAN_SECONDS = 2
        const val MAX_SCAN_SECONDS = 10
        const val DEFAULT_SCAN_SECONDS = 3
        const val MIN_POWER_DBM = 10
        const val MAX_POWER_DBM = 33
        const val DEFAULT_READ_POWER_DBM = 20
        const val DEFAULT_WRITE_POWER_DBM = 26

        val ZERO_PASSWORD = ByteArray(4)

        private const val KEY_ACCESS_PWD = "access_pwd"
        private const val KEY_KILL_PWD = "kill_pwd"
        private const val KEY_LOCK_ENABLED = "lock_enabled"
        private const val KEY_SCAN_SECONDS = "scan_seconds"
        private const val KEY_READ_POWER_DBM = "read_power_dbm"
        private const val KEY_WRITE_POWER_DBM = "write_power_dbm"
        private const val KEY_CONTINUOUS = "continuous_mode"
        private const val KEY_BACKEND = "backend"
        private const val KEY_BLE_MAC = "ble_mac"
        private const val KEY_BLE_NAME = "ble_name"
        private const val KEY_INVENTORY_MODE = "inventory_mode"

        fun isValidPasswordHex(hex: String): Boolean {
            val bytes = hex.hexToBytesOrNull() ?: return false
            return bytes.size == 4
        }

        /** Valid, non-zero password bytes, or null. */
        private fun passwordBytes(hex: String): ByteArray? {
            val bytes = hex.hexToBytesOrNull() ?: return null
            if (bytes.size != 4) return null
            if (bytes.all { it == 0.toByte() }) return null
            return bytes
        }
    }
}
