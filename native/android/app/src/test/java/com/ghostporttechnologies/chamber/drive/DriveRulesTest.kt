package com.ghostporttechnologies.chamber.drive

import com.ghostporttechnologies.chamber.drive.DriveRules.PAUSE_MAX_MS
import com.ghostporttechnologies.chamber.drive.DriveRules.STOPPED_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveRulesTest {

    private val t0 = 1_000_000_000L

    @Test fun onByDefaultAndHoldsInAVehicle() {
        val s = DriveState()
        assertFalse(DriveRules.driving(s, t0))
        assertTrue(DriveRules.driving(DriveRules.entered(s, t0), t0))
        assertFalse(DriveRules.driving(DriveRules.left(DriveRules.entered(s, t0), t0 + 1), t0 + 1))
    }

    @Test fun byHandHoldsWithoutDetection() {
        assertTrue(DriveRules.driving(DriveRules.drivingByHand(DriveState()), t0))
    }

    @Test fun passengerPauseLastsThisTripOnly() {
        var s = DriveRules.notDriving(DriveRules.entered(DriveState(), t0), t0 + 60_000)
        assertFalse(DriveRules.driving(s, t0 + 120_000))
        // A short stop (fuel, lights) keeps the pause.
        s = DriveRules.left(s, t0 + 600_000)
        s = DriveRules.entered(s, t0 + 600_000 + STOPPED_MS - 1)
        assertFalse(DriveRules.driving(s, t0 + 600_000 + STOPPED_MS))
        // Stopped for 10 minutes, then a new trip: held again.
        s = DriveRules.left(s, t0 + 2_000_000)
        s = DriveRules.entered(s, t0 + 2_000_000 + STOPPED_MS)
        assertTrue(DriveRules.driving(s, t0 + 2_000_000 + STOPPED_MS))
    }

    @Test fun pauseEndsAfterTwelveHours() {
        val s = DriveRules.notDriving(DriveRules.entered(DriveState(), t0), t0)
        assertFalse(DriveRules.driving(s, t0 + PAUSE_MAX_MS - 1))
        assertTrue(DriveRules.driving(s, t0 + PAUSE_MAX_MS))
    }

    @Test fun notDrivingEndsByHand() {
        val s = DriveRules.notDriving(DriveRules.drivingByHand(DriveState()), t0)
        assertFalse(DriveRules.driving(s, t0))
    }

    @Test fun cannotBeTurnedOffInAVehicle() {
        val inCar = DriveRules.notDriving(DriveRules.entered(DriveState(), t0), t0) // even as a passenger
        assertEquals(inCar, DriveRules.turnedOff(inCar, t0))
        val byHand = DriveRules.drivingByHand(DriveState())
        assertEquals(byHand, DriveRules.turnedOff(byHand, t0))
    }

    @Test fun offIsTheOnlyThingThatStopsItAndIsRecorded() {
        val off = DriveRules.turnedOff(DriveState(), t0)
        assertTrue(off.off)
        assertEquals(t0, off.offAt)
        assertFalse(DriveRules.driving(DriveRules.entered(off, t0), t0))
        assertTrue(DriveRules.driving(DriveRules.entered(DriveRules.turnedOn(off), t0), t0))
    }
}
