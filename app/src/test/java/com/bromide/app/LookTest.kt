package com.bromide.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test
    fun roundTrips() {
        val look = Look(grain = 1.4f, halation = 0.5f, ev = -0.33f, lab = 0f, grainSize = 1.5f, color = 0.8f, warmth = 0.25f, tint = -0.1f)
        assertEquals(look, Look.decode(look.encode()))
    }

    @Test
    fun readsLooksSavedBeforeColorSettings() {
        assertEquals(Look(grain = 1.2f, halation = 2f, ev = 0.5f, lab = 0.3f), Look.decode("1.2,2.0,0.5,0.3"))
    }

    @Test
    fun badValuesFallBackToDefaults() {
        assertTrue(Look.decode(null).isDefault)
        assertTrue(Look.decode("1,2").isDefault)
    }
}
