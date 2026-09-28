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
    fun `starting the next exercise resets the rest counter to zero`() {
        val engine = engine()
        engine.start(0)
        engine.tick(30_000)
        engine.tick(90_000)
        assertEquals(60_000L, engine.state.restElapsedMs)

        val events = engine.start(90_000)

        assertEquals(listOf(SessionEvent.ExerciseStarted(0, exercises[0])), events)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(RunState.RUNNING, engine.state.runState)
        assertEquals(30_000L, engine.state.exerciseRemainingMs)
        assertEquals(0L, engine.state.restElapsedMs)
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
    fun `reminders restart with the rest rather than carrying over`() {
        val engine = resting(restBeepSeconds = 30)
        engine.tick(40_000)
        // Leaving rest and coming back must not fire the reminders the old rest owed.
        engine.start(40_000)
        assertEquals(Stage.EXERCISE, engine.state.stage)
        assertEquals(0L, engine.state.restElapsedMs)
        assertTrue(engine.tick(50_000).isEmpty())
    }

}
