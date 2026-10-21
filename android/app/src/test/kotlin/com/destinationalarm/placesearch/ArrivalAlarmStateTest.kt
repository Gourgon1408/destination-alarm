package com.destinationalarm.placesearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalAlarmStateTest {
    @Test
    fun arrivalGateRequiresTwoAccurateFixesAndOnlyConfirmsOnce() {
        val gate = ArrivalGate(radiusMetres = 1_000.0)

        // Regression case from Samsung logs: the second fix had 148 m accuracy
        // while the measured distance was only 26 m inside a 1 km radius.
        val first = gate.update(distanceMetres = 28.7, accuracyMetres = 98.4)
        assertFalse(first.confirmed)
        assertEquals(1, first.confirmationCount)

        val second = gate.update(distanceMetres = 26.5, accuracyMetres = 148.6)
        assertTrue(second.confirmed)
        assertEquals("two_accurate_fixes_inside_radius", second.reason)

        val third = gate.update(distanceMetres = 10.0, accuracyMetres = 5.0)
        assertFalse(third.confirmed)
        assertEquals("arrival_already_confirmed", third.reason)
    }

    @Test
    fun oneTemporarilyInaccurateOrUnavailableFixDoesNotResetConfirmation() {
        val gate = ArrivalGate(radiusMetres = 500.0)
        assertEquals(1, gate.update(400.0, 20.0).confirmationCount)
        assertEquals("inaccurate_fix_ignored", gate.update(300.0, 600.0).reason)
        assertEquals(1, gate.confirmationCount)
        assertEquals("accuracy_unavailable_ignored", gate.update(300.0, Double.NaN).reason)
        assertEquals(1, gate.confirmationCount)
        assertTrue(gate.update(400.0, 20.0).confirmed)
    }

    @Test
    fun accurateFixOutsideRadiusResetsConfirmation() {
        val gate = ArrivalGate(radiusMetres = 500.0)
        assertEquals(1, gate.update(400.0, 20.0).confirmationCount)
        assertEquals("confirmation_reset_outside_radius", gate.update(510.0, 20.0).reason)
        assertEquals(0, gate.confirmationCount)
    }

    @Test
    fun staleAndInvalidCoordinateFixesAreRejectedAndResetConfirmation() {
        val gate = ArrivalGate(radiusMetres = 500.0)
        assertEquals(1, gate.update(400.0, 20.0).confirmationCount)

        val staleReason = ArrivalRules.invalidFixReason(12.0, 77.0, 30_001L, 30_000L)
        assertEquals("stale_fix", staleReason)
        val staleReset = gate.resetConfirmation(staleReason!!)
        assertEquals(0, staleReset.confirmationCount)
        assertEquals("confirmation_reset_stale_fix", staleReset.reason)

        assertEquals(1, gate.update(400.0, 20.0).confirmationCount)
        val invalidCoordinates = ArrivalRules.invalidFixReason(91.0, 77.0, 100L, 30_000L)
        assertEquals("invalid_coordinates", invalidCoordinates)
        assertEquals("confirmation_reset_invalid_coordinates", gate.resetConfirmation(invalidCoordinates!!).reason)
        assertEquals(0, gate.confirmationCount)
    }

    @Test
    fun accuracyThresholdAndArrivalWorkForSmallAndLargeRadii() {
        assertEquals(250.0, ArrivalRules.maximumAccuracyMetres(500.0), 0.0)
        assertEquals(250.0, ArrivalRules.maximumAccuracyMetres(5_000.0), 0.0)

        val smallRadiusGate = ArrivalGate(radiusMetres = 500.0)
        assertFalse(smallRadiusGate.update(400.0, 250.0).confirmed)
        assertTrue(smallRadiusGate.update(400.0, 250.0).confirmed)

        val largeRadiusGate = ArrivalGate(radiusMetres = 5_000.0)
        assertFalse(largeRadiusGate.update(4_900.0, 250.0).confirmed)
        assertTrue(largeRadiusGate.update(4_900.0, 250.0).confirmed)
    }

    @Test
    fun arrivalDismissStopsPlaybackButRetainsTheCompletedArrival() {
        val state = AlarmLifecycle()
        assertTrue(state.confirmArrival())
        assertEquals(AlarmPlaybackMode.ARRIVAL, state.playbackMode)
        assertFalse(state.confirmArrival())
        assertEquals(AlarmPlaybackMode.ARRIVAL, state.playbackMode)

        assertTrue(state.dismissArrival())
        assertTrue(state.arrived)
        assertEquals(AlarmPlaybackMode.NONE, state.playbackMode)
        assertFalse(state.dismissArrival())
    }

    @Test
    fun testAlarmDoesNotMutateJourneyArrivalStateAndCanBeStopped() {
        val state = AlarmLifecycle()
        assertTrue(state.beginTest())
        assertEquals(AlarmPlaybackMode.TEST, state.playbackMode)
        assertFalse(state.arrived)

        assertTrue(state.finishTest())
        assertEquals(AlarmPlaybackMode.NONE, state.playbackMode)
        assertFalse(state.arrived)
        assertFalse(state.finishTest())
    }

    @Test
    fun stopJourneyClearsAnyArrivalOrPlaybackState() {
        val state = AlarmLifecycle()
        assertTrue(state.confirmArrival())
        state.clearJourney()
        assertFalse(state.arrived)
        assertEquals(AlarmPlaybackMode.NONE, state.playbackMode)
    }
}
