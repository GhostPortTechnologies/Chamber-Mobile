package com.ghostporttechnologies.chamber.drive

/** What driving mode remembers (T-0321). Times are epoch milliseconds; 0 means never. */
data class DriveState(
    /** Turned off completely in Settings, at the person's explicit risk. */
    val off: Boolean = false,
    val offAt: Long = 0,
    /** "I'm driving now", set by hand. */
    val manual: Boolean = false,
    /** The phone's own activity detection says the person is in a vehicle. */
    val inVehicle: Boolean = false,
    val enteredAt: Long = 0,
    val leftAt: Long = 0,
    /** "I'm not driving" (a passenger): paused for this trip. */
    val pausedAt: Long = 0,
    /** Pushes held while driving, for the summary afterwards. */
    val held: Int = 0,
)

/**
 * When driving mode holds things. No Android classes here, so it is unit tested.
 *
 * Driving mode is on unless the person turned it off. It holds everything while they are in a vehicle or said
 * "I'm driving", unless they said "I'm not driving" for this trip. That pause lasts until the vehicle has been
 * stopped for 10 minutes and a new trip starts, or for 12 hours at most.
 */
object DriveRules {
    const val STOPPED_MS = 10 * 60_000L
    const val PAUSE_MAX_MS = 12 * 3_600_000L

    fun paused(s: DriveState, now: Long): Boolean = s.pausedAt != 0L && now - s.pausedAt < PAUSE_MAX_MS

    fun driving(s: DriveState, now: Long): Boolean = !s.off && (s.manual || (s.inVehicle && !paused(s, now)))

    /** The phone says a vehicle trip started. After a real stop, a pause from the last trip no longer counts. */
    fun entered(s: DriveState, now: Long): DriveState {
        val newTrip = s.pausedAt != 0L && s.leftAt > s.pausedAt && now - s.leftAt >= STOPPED_MS
        return s.copy(inVehicle = true, enteredAt = now, pausedAt = if (newTrip) 0 else s.pausedAt)
    }

    fun left(s: DriveState, now: Long): DriveState = s.copy(inVehicle = false, leftAt = now)

    /** "I'm not driving": for this trip only. Also ends "I'm driving" set by hand. */
    fun notDriving(s: DriveState, now: Long): DriveState = s.copy(manual = false, pausedAt = now)

    fun drivingByHand(s: DriveState): DriveState = s.copy(manual = true, pausedAt = 0)

    /** Refused while the phone says the person is in a vehicle: they pull over first. */
    fun turnedOff(s: DriveState, now: Long): DriveState =
        if (s.inVehicle || driving(s, now)) s else s.copy(off = true, offAt = now, manual = false)

    fun turnedOn(s: DriveState): DriveState = s.copy(off = false)
}
