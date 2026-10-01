package com.timergym.timer

import com.timergym.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM JUnit: TimerEngine imports nothing from Android, so this runs in about a
 * second from the IDE gutter or with `./gradlew test`. No emulator, no Robolectric.
 */
class TimerEngineTest {

    private val exercises = listOf(
        Step(1, 30),
        Step(2, 45),
    )

    private fun engine(restBeepSeconds: Int = 0) =
        TimerEngine(exercises, restBeepSeconds)

    /** Run a 30s exercise out so the engine is sitting in rest. */
    private fun resting(restBeepSeconds: Int = 0): TimerEngine {
        val e = engine(restBeepSeconds)
        e.start(0)
        e.tick(30_000)
        assertEquals(Stage.REST, e.state.stage)
        return e
    }

    @Test
    fun `starts ready on the first exercise with rest at zero`() {
        val engine = engine()
        assertEquals(RunState.IDLE, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(0, engine.state.exerciseIndex)
        assertEquals(30_000L, engine.state.exerciseRemainingMs)
        assertEquals(0L, engine.state.restElapsedMs)
    }

    @Test
    fun `the exercise counts down`() {
        val engine = engine()
        engine.start(0)
        assertTrue(engine.tick(10_000).isEmpty())
        assertEquals(20_000L, engine.state.exerciseRemainingMs)
        assertEquals(Stage.EXERCISE, engine.state.stage)
    }

    @Test
    fun `rest starts on its own and the exercise hands over`() {
        val engine = engine()
        engine.start(0)
        val events = engine.tick(30_000)

        assertEquals(listOf(SessionEvent.ExerciseEnded(0, exercises[0])), events)
        assertEquals(Stage.REST, engine.state.stage)
        // Still running: nobody pressed anything.
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(0L, engine.state.exerciseRemainingMs)
        assertEquals(0L, engine.state.restElapsedMs)
    }

    @Test
    fun `rest counts up and never ends by itself`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)

        // Ten minutes of resting is still fine: there is no fixed rest length.
        assertTrue(engine.tick(35_000).isEmpty())
        assertEquals(5_000L, engine.state.restElapsedMs)
        assertTrue(engine.tick(635_000).isEmpty())
        assertEquals(605_000L, engine.state.restElapsedMs)
        assertEquals(Stage.REST, engine.state.stage)
        assertEquals(RunState.RUNNING, engine.state.runState)
    }

    @Test
    fun `restart leaves the rest and resets the rest counter`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)
        engine.tick(90_000)
        assertEquals(60_000L, engine.state.restElapsedMs)

        engine.restart(90_000)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(30_000L, engine.state.exerciseRemainingMs)
        assertEquals(0L, engine.state.restElapsedMs)
    }

    @Test
    fun `start cannot leave a rest, because it only runs from idle`() {
        val engine = resting()
        // A rest is already RUNNING, so start() is a no-op and the rest keeps going.
        engine.start(40_000)
        assertEquals(Stage.REST, engine.state.stage)
        assertEquals(40_000L, engine.state.restElapsedMs)
    }

    @Test
    fun `pause freezes the exercise and resume continues from it`() {
        val engine = engine()
        engine.start(0)
        engine.tick(10_000)

        engine.pause(10_000)
        assertEquals(RunState.PAUSED, engine.state.runState)
        assertEquals(20_000L, engine.state.exerciseRemainingMs)

        // Wall clock keeps moving while paused; the timer must not.
        engine.tick(600_000)
        assertEquals(20_000L, engine.state.exerciseRemainingMs)

        engine.start(600_000)
        engine.tick(605_000)
        assertEquals(15_000L, engine.state.exerciseRemainingMs)
    }

    @Test
    fun `pause during rest freezes the stopwatch`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)
        engine.tick(40_000)

        engine.pause(40_000)
        assertEquals(10_000L, engine.state.restElapsedMs)
        engine.tick(900_000)
        assertEquals(10_000L, engine.state.restElapsedMs)
    }

    @Test
    fun `selecting an exercise loads it and zeroes rest`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)
        engine.tick(50_000)
        assertEquals(20_000L, engine.state.restElapsedMs)

        engine.select(1, 50_000)
        assertEquals(1, engine.state.exerciseIndex)
        assertEquals(45_000L, engine.state.exerciseRemainingMs)
        assertEquals(RunState.IDLE, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(0L, engine.state.restElapsedMs)
    }

    @Test
    fun `select clamps an out of range index`() {
        val engine = engine()
        engine.select(99, 0)
        assertEquals(1, engine.state.exerciseIndex)
        engine.select(-5, 0)
        assertEquals(0, engine.state.exerciseIndex)
    }

    @Test
    fun `reset returns the current exercise to ready`() {
        val engine = engine()
        engine.select(1, 0)
        engine.start(0)
        engine.tick(20_000)

        engine.reset(20_000)
        assertEquals(1, engine.state.exerciseIndex)
        assertEquals(45_000L, engine.state.exerciseRemainingMs)
        assertEquals(RunState.IDLE, engine.state.runState)
        assertEquals(0L, engine.state.restElapsedMs)
    }

    @Test
    fun `one late tick still lands in rest`() {
        val engine = engine()
        engine.start(0)
        val events = engine.tick(600_000)

        assertEquals(listOf(SessionEvent.ExerciseEnded(0, exercises[0])), events)
        assertEquals(Stage.REST, engine.state.stage)
        // The overshoot belongs to rest, not to the exercise that already ended.
        assertEquals(0L, engine.state.restElapsedMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty timer list is rejected rather than crashing later`() {
        TimerEngine(emptyList())
    }

    // ---- rest reminders -------------------------------------------------------------

    @Test
    fun `no reminder is emitted when the interval is off`() {
        val engine = resting(restBeepSeconds = 0)
        assertTrue(engine.tick(90_000).isEmpty())
    }

    // ---- resume ("continue") ----------------------------------------------------------

    @Test
    fun `continue mid-rest keeps resting instead of starting a fresh exercise`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)            // the 30s exercise ends, rest begins
        engine.pause(35_000)           // paused five seconds into the rest
        engine.resume(40_000)

        // The whole point: still resting, still green, and the rest time is intact.
        assertEquals(Stage.REST, engine.state.stage)
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(5_000L, engine.state.restElapsedMs)
    }

    @Test
    fun `continue mid-exercise resumes the same exercise`() {
        val engine = engine()
        engine.start(0)
        engine.tick(10_000)
        engine.pause(10_000)
        engine.resume(20_000)

        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(20_000L, engine.state.exerciseRemainingMs)
    }

    @Test
    fun `continue on a running timer does nothing`() {
        val engine = engine()
        engine.start(0)
        engine.tick(10_000)
        // Running an exercise: "continue" has nothing to continue.
        assertTrue(engine.resume(15_000).isEmpty())
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
    }


    // ---- the play control -----------------------------------------------------------

    @Test
    fun `the play control resumes a rest in place instead of starting a new exercise`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)        // the exercise ends, a rest begins
        engine.toggle(35_000)      // "Pause"
        assertEquals(RunState.PAUSED, engine.state.runState)
        engine.toggle(40_000)      // "Resume"

        // This is the bug that made rest impossible to sit through: the button said
        // Resume but used to call start(), which reloaded a fresh exercise.
        assertEquals(Stage.REST, engine.state.stage)
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(5_000L, engine.state.restElapsedMs)
    }

    @Test
    fun `the play control starts the exercise from idle`() {
        val engine = engine()
        engine.toggle(0)
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
    }

    @Test
    fun `the play control pauses a running exercise`() {
        val engine = engine()
        engine.start(0)
        engine.toggle(10_000)
        assertEquals(RunState.PAUSED, engine.state.runState)
    }

    @Test
    fun `restart is the way out of a rest`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)        // resting
        engine.reset(35_000)       // what the restart button does first
        engine.start(35_000)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(RunState.RUNNING, engine.state.runState)
    }

    // ---- stop -----------------------------------------------------------------------

    @Test
    fun `stop returns the timer to ready from a rest`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)        // resting
        engine.reset(35_000)
        assertEquals(RunState.IDLE, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(30_000L, engine.state.exerciseRemainingMs)
    }

    @Test
    fun `stop returns the timer to ready from a running exercise`() {
        val engine = engine()
        engine.start(0)
        engine.tick(12_000)        // 18s left on the clock
        engine.reset(12_000)
        assertEquals(RunState.IDLE, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(30_000L, engine.state.exerciseRemainingMs)
    }

    @Test
    fun `stop from a pause returns the timer to ready`() {
        val engine = engine()
        engine.start(0)
        engine.tick(12_000)
        engine.pause(12_000)
        engine.reset(12_000)
        assertEquals(RunState.IDLE, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
    }

    @Test
    fun `a stopped timer does not keep running`() {
        val engine = engine()
        engine.start(0)
        engine.tick(12_000)
        engine.reset(12_000)
        // The wall clock keeps moving; the timer must not.
        engine.tick(600_000)
        assertEquals(30_000L, engine.state.exerciseRemainingMs)
        assertEquals(0L, engine.state.restElapsedMs)
    }

    @Test
    fun `a reminder fires on each whole interval of rest`() {
        val engine = resting(restBeepSeconds = 30)
        // Rest starts at zero, so ten seconds in nothing is due yet.
        assertTrue(engine.tick(10_000).isEmpty())
        // ...but at 31s a whole 30s boundary has passed.
        assertEquals(
            listOf(SessionEvent.RestReminder(30_000)),
            engine.tick(31_000),
        )
    }

    @Test
    fun `a reminder fires once per interval over a long rest`() {
        val engine = resting(restBeepSeconds = 15)
        // 15, 30, 45 and 60s are all due by 70s.
        assertEquals(
            listOf(
                SessionEvent.RestReminder(15_000),
                SessionEvent.RestReminder(30_000),
                SessionEvent.RestReminder(45_000),
                SessionEvent.RestReminder(60_000),
            ),
            engine.tick(70_000),
        )
    }

    @Test
    fun `a long stall cannot produce a burst of reminders`() {
        val engine = resting(restBeepSeconds = 15)
        // Ten minutes of rest in one tick: capped, so the user gets a few reminders
        // rather than forty of them at once.
        val events = engine.tick(600_000)
        assertTrue("capped at 3, was ${events.size}", events.size <= 3)
    }

    @Test
    fun `a new rest period starts at zero again`() {
        val engine = resting(restBeepSeconds = 30)
        engine.tick(40_000)
        engine.restart(40_000)         // leave the rest
        engine.tick(50_000)            // run the exercise
        engine.tick(80_000)            // exercise ends: a brand new rest begins
        assertEquals(Stage.REST, engine.state.stage)
        assertEquals(0L, engine.state.restElapsedMs)
    }

    // ---- the spec, one test per sentence ---------------------------------------------

    @Test
    fun `an idle timer is never in a rest`() {
        val engine = engine()
        assertEquals(Stage.EXERCISE, engine.state.stage)
        engine.start(0)
        engine.tick(30_000)          // resting, still running
        engine.pause(35_000)
        engine.resume(40_000)
        assertTrue(
            "IDLE implies EXERCISE, but was ${engine.state}",
            !(engine.state.runState == RunState.IDLE && engine.state.stage == Stage.REST),
        )
    }

    @Test
    fun `pausing and continuing never change the stage`() {
        val engine = resting()
        val stageBefore = engine.state.stage

        engine.pause(40_000)
        assertEquals(stageBefore, engine.state.stage)
        engine.resume(50_000)
        assertEquals(stageBefore, engine.state.stage)

        // Same again in an exercise, so the rule holds in both modes.
        engine.restart(50_000)
        val exerciseStage = engine.state.stage
        engine.pause(60_000)
        assertEquals(exerciseStage, engine.state.stage)
        engine.resume(70_000)
        assertEquals(exerciseStage, engine.state.stage)
    }

    @Test
    fun `rest has no ceiling and keeps counting`() {
        val engine = resting()
        // An hour in one tick. Nothing anywhere may cap this.
        engine.tick(3_600_000)
        assertEquals(3_600_000L, engine.state.restElapsedMs)
        assertEquals(Stage.REST, engine.state.stage)
    }

    @Test
    fun `pause freezes a rest and continue resumes it`() {
        val engine = resting()
        engine.tick(20_000)
        engine.pause(20_000)
        assertEquals(20_000L, engine.state.restElapsedMs)

        // The wall clock keeps moving while paused; the timer must not.
        engine.tick(600_000)
        assertEquals(20_000L, engine.state.restElapsedMs)
        assertEquals(Stage.REST, engine.state.stage)

        engine.resume(620_000)
        engine.tick(625_000)
        assertEquals(25_000L, engine.state.restElapsedMs)
        assertEquals(Stage.REST, engine.state.stage)
    }

    @Test
    fun `twenty pause and continue cycles never reset the rest`() {
        val engine = resting()
        var now = 0L
        engine.tick(10_000)         // 10s of rest
        now = 10_000
        repeat(20) {
            engine.pause(now)
            assertEquals(Stage.REST, engine.state.stage)
            now += 1_000
            engine.tick(now)
            assertEquals(Stage.REST, engine.state.stage)
            now += 1_000
            engine.resume(now)
            assertEquals(Stage.REST, engine.state.stage)
        }
        // 10s plus twenty two-second cycles: the counter only ever went up.
        assertEquals(50_000L, engine.state.restElapsedMs)
    }

    @Test
    fun `reminders keep going for the whole rest and stop when the user pauses`() {
        val engine = resting(restBeepSeconds = 5)
        // Five minutes of rest: 60 five-second intervals, all of them due.
        var now = 0L
        var heard = 0
        repeat(300) {                       // 300 ticks of 1s = 5 minutes
            now += 1_000
            heard += engine.tick(now).count { it is SessionEvent.RestReminder }
        }
        assertEquals(300_000L, engine.state.restElapsedMs)
        assertEquals("one beep per 5s of a 5 minute rest", 60, heard)

        // Pausing stops the clock, and with it the reminders.
        engine.pause(now)
        assertTrue(engine.tick(now + 60_000).none { it is SessionEvent.RestReminder })
    }

    @Test
    fun `stop returns to a ready timer and does not start playing`() {
        val engine = resting()
        engine.tick(45_000)
        engine.reset(45_000)
        assertEquals(RunState.IDLE, engine.state.runState)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(30_000L, engine.state.exerciseRemainingMs)
        assertEquals(0L, engine.state.restElapsedMs)
    }
}
