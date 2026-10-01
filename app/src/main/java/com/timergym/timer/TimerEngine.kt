package com.timergym.timer

import com.timergym.data.Step

enum class RunState { IDLE, RUNNING, PAUSED }

/** Which counter the dial is currently showing. */
enum class Stage { EXERCISE, REST }

sealed interface SessionEvent {
    /** The exercise reached zero. Rest begins counting up on its own, with no tap. */
    data class ExerciseEnded(val index: Int, val step: Step) : SessionEvent

    /** A rest period has been running for a whole multiple of the reminder interval. */
    data class RestReminder(val elapsedMs: Long) : SessionEvent
}

data class SessionState(
    val runState: RunState = RunState.IDLE,
    val stage: Stage = Stage.EXERCISE,
    val exerciseIndex: Int = 0,
    val exerciseRemainingMs: Long = 0L,
    val exerciseDurationMs: Long = 0L,
    /** Counts up while resting. There is no fixed rest: you decide when you are ready. */
    val restElapsedMs: Long = 0L,
) {
    /** What the big dial reads right now. */
    val dialMs: Long
        get() = if (stage == Stage.REST) restElapsedMs else exerciseRemainingMs
}

/**
 * One exercise, plus the rest that follows it, as a pure function of an injected clock.
 *
 * Imports nothing from Android and starts no coroutines, which is what makes
 * [TimerEngineTest] run on the desktop JVM in about a second. The caller supplies the
 * clock (SystemClock.elapsedRealtime in the app, a fake in tests).
 *
 * Two independent axes, and keeping them independent is the whole design:
 *
 *  - [RunState] asks whether the clock is moving.
 *  - [Stage] asks which counter the dial is showing.
 *
 * **Pausing and continuing never change the stage.** A rest resumes as a rest. That is
 * what "pause and continue work in both modes" means, and it is enforced by construction:
 * [pause] and [resume] only ever write [RunState].
 *
 * Stage therefore has exactly four writers — the clock reaching zero, [restart], [reset]
 * and [select]. Everything else is stage-blind. `IDLE` implies `EXERCISE` because
 * [ready] is the only way into that state and it always says so.
 *
 * Rest is deliberately open-ended. It counts up forever and has no ceiling; the only ways
 * out are the user stopping the timer or asking for the exercise again. Each tick
 * recomputes from the real clock rather than decrementing a counter, so the display
 * cannot drift and a stalled frame cannot lose time.
 */
class TimerEngine(
    steps: List<Step>,
    restBeepSeconds: Int = 0,
) {

    val steps: List<Step> = steps.toList()

    /**
     * Beep this often during rest; 0 disables it. Settable rather than a val, so Settings
     * can change it on a live session: rebuilding the engine to pick up a new interval
     * would silently reset a timer that is halfway through an exercise.
     */
    var restBeepSeconds: Int = restBeepSeconds.coerceAtLeast(0)
        private set

    fun setRestBeep(seconds: Int) {
        restBeepSeconds = seconds.coerceAtLeast(0)
    }

    init {
        require(this.steps.isNotEmpty()) { "TimerEngine needs at least one timer" }
    }

    private companion object {
        /**
         * Ceiling on reminders from one tick, so a long stall cannot produce a burst.
         * Long, not Int: it is compared against a tick budget in milliseconds.
         */
        const val MAX_REMINDERS = 3L
    }

    var state: SessionState = ready(0)
        private set

    /** Clock reading of the previous tick, so each tick can measure a true delta. */
    private var lastTick = 0L

    /** Pick an exercise from the menu. Loads it and zeroes the rest counter. */
    fun select(index: Int, now: Long) {
        state = ready(index.coerceIn(0, steps.lastIndex))
        lastTick = now
    }

    /**
     * Begin a session. IDLE only, and deliberately blind to the stage.
     *
     * This used to carry a branch that left a rest and reloaded the exercise, which is
     * what made "start" mean two different things depending on where the user was. Stage
     * now has exactly four writers — the clock reaching zero, [restart], [reset] and
     * [select] — and nothing else can move it.
     *
     * IDLE implies EXERCISE, because [ready] is the only way in and it always says so,
     * so this never has to think about the stage at all.
     */
    fun start(now: Long) {
        if (state.runState != RunState.IDLE) return
        state = state.copy(runState = RunState.RUNNING)
        lastTick = now
    }

    /**
     * Make sure the clock is moving, without changing the stage.
     *
     * "Continue" is this, and so is "start" away from a rest. From a rest it resumes the
     * rest, not a new exercise: the rest runs until the user stops it, and nothing about
     * resuming may quietly end it.
     */
    fun resume(now: Long): List<SessionEvent> = when (state.runState) {
        RunState.RUNNING -> emptyList()
        RunState.PAUSED -> {
            state = state.copy(runState = RunState.RUNNING)
            lastTick = now
            emptyList()
        }
        // IDLE implies EXERCISE, so resuming an idle timer is just starting it.
        RunState.IDLE -> {
            start(now)
            emptyList()
        }
    }

    /**
     * Run this exercise again from the top, leaving any rest.
     *
     * Voice "start" and "restart" reach this. The stop button deliberately does not:
     * stopping is not the same as starting again, and conflating them was half of the
     * mess this replaces.
     */
    fun restart(now: Long) {
        state = ready(state.exerciseIndex).copy(runState = RunState.RUNNING)
        lastTick = now
    }

    /** Settles the partial tick first, so the frozen value is exact, not 50ms stale. */
    fun pause(now: Long): List<SessionEvent> {
        if (state.runState != RunState.RUNNING) return emptyList()
        val events = tick(now)
        state = state.copy(runState = RunState.PAUSED)
        return events
    }

    /**
     * The play control. Pausing when running, otherwise [resume] — which never changes the
     * stage, so a paused rest resumes as a rest.
     */
    fun toggle(now: Long): List<SessionEvent> =
        if (state.runState == RunState.RUNNING) pause(now) else resume(now)

    /** Back to the timer, loaded and ready, not running. The stop button, and voice "stop". */
    fun reset(now: Long) {
        state = ready(state.exerciseIndex)
        lastTick = now
    }

    /** Advances to [now], crossing at most one boundary per call. */
    fun tick(now: Long): List<SessionEvent> {
        if (state.runState != RunState.RUNNING) {
            // Keep the mark current so a resume does not see a bogus delta.
            lastTick = now
            return emptyList()
        }
        val budget = (now - lastTick).coerceAtLeast(0L)
        lastTick = now
        if (budget <= 0L) return emptyList()

        if (state.stage == Stage.REST) {
            // A stopwatch, not a countdown: it keeps going until the user says stop.
            val before = state.restElapsedMs
            val after = before + budget
            state = state.copy(restElapsedMs = after)
            return restReminders(before, after)
        }

        val left = state.exerciseRemainingMs
        if (budget < left) {
            state = state.copy(exerciseRemainingMs = left - budget)
            return emptyList()
        }
        val step = steps[state.exerciseIndex]
        state = state.copy(
            stage = Stage.REST,
            exerciseRemainingMs = 0L,
            restElapsedMs = 0L,
        )
        return listOf(SessionEvent.ExerciseEnded(state.exerciseIndex, step))
    }

    /**
     * One reminder per whole interval crossed between [before] and [after].
     *
     * Derived from [SessionState.restElapsedMs] rather than a separate counter, so
     * starting or ending a rest resets the reminder phase for free — there is no second
     * piece of state to fall out of step with the clock.
     *
     * Capped because the tick is a plain elapsedRealtime delta: after a long stall, such
     * as the app being resumed, an uncapped loop would fire a burst of beeps at once.
     */
    private fun restReminders(before: Long, after: Long): List<SessionEvent> {
        val interval = restBeepSeconds * 1000L
        if (interval <= 0L) return emptyList()
        val first = before / interval + 1
        val last = after / interval
        if (last < first) return emptyList()
        // coerceAtMost stays in Long because the budget is in milliseconds; List() wants
        // an Int, so the conversion belongs here rather than on the constant.
        val count = (last - first + 1).coerceAtMost(MAX_REMINDERS).toInt()
        return List(count) { SessionEvent.RestReminder((first + it) * interval) }
    }

    /** Ready state: exercise loaded, rest at zero, waiting for play. */
    private fun ready(index: Int) = SessionState(
        runState = RunState.IDLE,
        stage = Stage.EXERCISE,
        exerciseIndex = index,
        exerciseRemainingMs = steps[index].seconds * 1000L,
        exerciseDurationMs = steps[index].seconds * 1000L,
        restElapsedMs = 0L,
    )
}
