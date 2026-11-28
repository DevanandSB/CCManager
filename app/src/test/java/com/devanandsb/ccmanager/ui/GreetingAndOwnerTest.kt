package com.devanandsb.ccmanager.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class GreetingAndOwnerTest {

    @Test
    fun timeOfDayGreetingMatchesLocalHours() {
        assertEquals("Good Morning", timeOfDayGreeting(LocalTime.of(5, 0)))
        assertEquals("Good Morning", timeOfDayGreeting(LocalTime.of(11, 59)))
        assertEquals("Good Afternoon", timeOfDayGreeting(LocalTime.of(12, 0)))
        assertEquals("Good Afternoon", timeOfDayGreeting(LocalTime.of(16, 45)))
        assertEquals("Good Evening", timeOfDayGreeting(LocalTime.of(17, 0)))
        assertEquals("Good Evening", timeOfDayGreeting(LocalTime.of(20, 30)))
        assertEquals("Good Night", timeOfDayGreeting(LocalTime.of(21, 0)))
        assertEquals("Good Night", timeOfDayGreeting(LocalTime.of(2, 15)))
    }

    @Test
    fun detectsCustomOwnerNameVsDefaultTitle() {
        assertEquals("Cardholder", DEFAULT_OWNER_NAME)
        assertFalse(isCustomOwnerName(""))
        assertFalse(isCustomOwnerName("   "))
        assertFalse(isCustomOwnerName(DEFAULT_OWNER_NAME))
        assertFalse(isCustomOwnerName("Reserve Member"))
        assertFalse(isCustomOwnerName("Premier Member"))
        assertFalse(isCustomOwnerName("Private Vault"))
        assertTrue(isCustomOwnerName("Alex"))
    }
}
