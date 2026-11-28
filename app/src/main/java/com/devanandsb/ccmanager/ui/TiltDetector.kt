package com.devanandsb.ccmanager.ui

import kotlin.math.abs

/**
 * Stateful tilt-to-flip detector with immediate trigger locking, 15-second cancel cooldown,
 * and neutral-angle re-arming so a single tilt or dismissed dialog never re-fires repeatedly.
 */
class TiltDetector {
    var isTiltLocked: Boolean = false
        private set

    var tiltCooldownUntilMs: Long = 0L
        private set

    private var initialized: Boolean = false
    private var smoothX: Float = 0f
    private var anchorX: Float = 0f

    fun reset() {
        isTiltLocked = false
        tiltCooldownUntilMs = 0L
        initialized = false
        smoothX = 0f
        anchorX = 0f
    }

    /**
     * Suppresses all tilt triggers for 15 seconds and keeps [isTiltLocked] = true
     * until both the cooldown expires and the device returns to a neutral angle.
     */
    fun onDismissed(nowMs: Long) {
        isTiltLocked = true
        tiltCooldownUntilMs = nowMs + CANCEL_COOLDOWN_MS
    }

    /**
     * Processes one gravity/accelerometer X-axis sample and returns `"left"` / `"right"` if a
     * valid tilt transition occurred, or `null` otherwise.
     */
    fun onSample(
        x: Float,
        nowMs: Long,
        blocking: Boolean,
        revealed: Boolean,
        revealDetectAfterMs: Long,
    ): String? {
        if (!initialized) {
            initialized = true
            smoothX = x
            anchorX = x
            isTiltLocked = false
            return null
        }

        smoothX = smoothX * 0.45f + x * 0.55f

        if (blocking) {
            if (!isTiltLocked) {
                anchorX = smoothX
                isTiltLocked = true
            }
            return null
        }

        if (revealed && nowMs < revealDetectAfterMs) {
            isTiltLocked = true
            anchorX = smoothX
            return null
        }

        // Slight tilt reveals card details; high slanting is required to close them.
        val triggerThreshold = if (revealed) 4.8f else 1.25f
        val restDriftMax = if (revealed) 0.9f else 0.55f
        val rearmThreshold = if (revealed) 1.0f else 0.65f
        val delta = smoothX - anchorX
        val absDelta = abs(delta)

        if (nowMs < tiltCooldownUntilMs) {
            return null
        }

        if (isTiltLocked) {
            if (absDelta < rearmThreshold) {
                isTiltLocked = false
            }
            return null
        }

        if (absDelta < restDriftMax) {
            anchorX = anchorX * 0.94f + smoothX * 0.06f
        }

        if (absDelta >= triggerThreshold) {
            isTiltLocked = true
            return if (delta > 0f) "left" else "right"
        }

        return null
    }

    companion object {
        const val CANCEL_COOLDOWN_MS = 15_000L
    }
}
