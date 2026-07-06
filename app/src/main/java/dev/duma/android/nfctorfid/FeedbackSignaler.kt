package dev.duma.android.nfctorfid

import android.content.Context
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View

/** Success/error feedback: tone + vibration + full-screen color flash. */
class FeedbackSignaler(context: Context, private val flashOverlay: View) {

    private val tone: ToneGenerator? =
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun success() {
        tone?.startTone(ToneGenerator.TONE_PROP_ACK, 180)
        vibrate(80)
        flash(FLASH_SUCCESS)
    }

    fun error() {
        tone?.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
        vibrate(250)
        flash(FLASH_ERROR)
    }

    /** Neutral "input registered" ack (e.g. NFC tap) — no verdict, so no flash. */
    fun acknowledge() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        vibrate(40)
    }

    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }

    private fun flash(color: Int) {
        flashOverlay.post {
            flashOverlay.animate().cancel()
            flashOverlay.setBackgroundColor(color)
            flashOverlay.alpha = 0.85f
            flashOverlay.visibility = View.VISIBLE
            flashOverlay.animate()
                .alpha(0f)
                .setDuration(400)
                .withEndAction { flashOverlay.visibility = View.GONE }
                .start()
        }
    }

    fun release() {
        tone?.release()
    }

    private companion object {
        val FLASH_SUCCESS = Color.rgb(0x2E, 0xAE, 0x3C)
        val FLASH_ERROR = Color.rgb(0xD0, 0x2A, 0x2A)
    }
}
