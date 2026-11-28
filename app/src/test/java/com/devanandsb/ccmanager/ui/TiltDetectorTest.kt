package com.devanandsb.ccmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TiltDetectorTest {

    @Test
    fun locksImmediatelyOnValidTiltAndRespects15sCooldownAndNeutralRearm() {
        val detector = TiltDetector()

        // 1. Initial resting neutral samples
        assertNull(detector.onSample(0f, 1_000L, blocking = false, revealed = false, revealDetectAfterMs = 0L))
        assertNull(detector.onSample(0.05f, 1_050L, blocking = false, revealed = false, revealDetectAfterMs = 0L))
        assertFalse(detector.isTiltLocked)

        // 2. Valid tilt triggers once and immediately sets isTiltLocked = true
        val dir = detector.onSample(3.2f, 1_100L, blocking = false, revealed = false, revealDetectAfterMs = 0L)
        assertEquals("left", dir)
        assertTrue(detector.isTiltLocked)

        // 3. Subsequent tilted samples (while prompt is open OR before dismiss) must NEVER re-trigger
        assertNull(detector.onSample(3.2f, 1_150L, blocking = true, revealed = false, revealDetectAfterMs = 0L))
        assertNull(detector.onSample(3.5f, 1_200L, blocking = true, revealed = false, revealDetectAfterMs = 0L))
        assertTrue(detector.isTiltLocked)

        // 4. User taps Cancel / Dismiss at t = 2,000ms -> 15s cooldown until 17,000ms
        detector.onDismissed(2_000L)
        assertTrue(detector.isTiltLocked)
        assertEquals(17_000L, detector.tiltCooldownUntilMs)

        // 5. Even if device returns to neutral during the 15s cooldown, triggers stay suppressed & locked
        for (step in 0..10) {
            assertNull(detector.onSample(0f, 3_000L + step * 1_000L, blocking = false, revealed = false, revealDetectAfterMs = 0L))
        }
        assertNull(detector.onSample(3.2f, 16_500L, blocking = false, revealed = false, revealDetectAfterMs = 0L))
        assertTrue(detector.isTiltLocked)

        // 6. After 15s cooldown expires (t = 17,500ms), staying tilted does NOT re-arm yet
        for (i in 0..5) {
            assertNull(detector.onSample(3.2f, 17_500L + i * 50L, blocking = false, revealed = false, revealDetectAfterMs = 0L))
        }
        assertTrue(detector.isTiltLocked)

        // 7. Return to neutral angle (0f) re-arms detector (isTiltLocked -> false) without triggering
        for (i in 0..10) {
            assertNull(detector.onSample(0f, 18_000L + i * 50L, blocking = false, revealed = false, revealDetectAfterMs = 0L))
        }
        assertFalse(detector.isTiltLocked)

        // 8. Next valid tilt triggers cleanly again
        val second = detector.onSample(-3.4f, 19_000L, blocking = false, revealed = false, revealDetectAfterMs = 0L)
        assertEquals("right", second)
        assertTrue(detector.isTiltLocked)
    }
}
