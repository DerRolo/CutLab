package de.cutlab.app

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/** Eigene Vibration (funktioniert auch, wenn "Touch-Feedback" in den Handy-Einstellungen aus ist). */
object Haptics {
    private fun play(ctx: Context, effectId: Int, fallbackMs: Long, amp: Int) {
        val v = ctx.getSystemService(Vibrator::class.java) ?: return
        if (!v.hasVibrator()) return
        try {
            v.vibrate(VibrationEffect.createPredefined(effectId))
        } catch (e: Exception) {
            try { v.vibrate(VibrationEffect.createOneShot(fallbackMs, amp)) } catch (_: Exception) {}
        }
    }

    /** Element angepackt. */
    fun pickup(ctx: Context) = play(ctx, VibrationEffect.EFFECT_CLICK, 25, 160)

    /** Etwas ist eingerastet (angedockt). */
    fun snap(ctx: Context) = play(ctx, VibrationEffect.EFFECT_TICK, 12, 110)

    /** Feinmodus (Zoom) startet. */
    fun fine(ctx: Context) = play(ctx, VibrationEffect.EFFECT_HEAVY_CLICK, 45, 255)
}
