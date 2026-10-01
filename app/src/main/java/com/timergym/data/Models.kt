package com.timergym.data

/**
 * One timer. Just a length: this is a menu of timers to pick from, not a sequence to
 * play through, so a muscle group would be decoration the UI never reads.
 */
data class Step(
    val id: Long,
    val seconds: Int,
)

/**
 * End-of-timer sounds. Each is an MP3 in `app/src/main/assets/sounds`, named after its
 * enum constant, so [asset] is derived rather than written out twice.
 *
 * The enum names never change, because they are what gets written to storage; only the
 * [display] strings are user-facing.
 */
enum class Sound(val display: String) {
    BELL("Bell"),
    BELL_2("Bell 2"),
    BUZZER("Buzzer"),
    DOUBLE_BELL("Double bell"),
    FAH("Fah"),
    NOTIFICATION_1("Notification 1"),
    POP("Pop"),
    SUS("Sus"),
    WOW("Wow"),
    ;

    /** Where the audio lives in the APK. */
    val asset: String get() = "sounds/$name.mp3"
}

data class AppSettings(
    val steps: List<Step>,
    val sound: Sound = Sound.BELL,
    /** Cue loudness, 0..1. Applied to the track, not baked into the samples. */
    val volume: Float = 1f,
    val haptics: Boolean = true,
    val voice: Boolean = false,
    /**
     * "Show debug options". The raw diagnostics: status, frame and utterance counters,
     * time speed. Separate from [voiceTestPanel] so the noisy read-outs can go without
     * losing the voice feedback, which is still useful once voice is working.
     */
    val voiceDebug: Boolean = true,
    /**
     * "Show voice commands". One switch for everything that reports what the recognizer
     * actually heard: the "Heard phrase" box and command chips in Settings, the live mic
     * level bar, and the "Heard ..." bar in the app. Split from [voiceDebug] so switching
     * the wording feedback off does not also silence the mic, and vice versa.
     */
    val voiceTestPanel: Boolean = true,
    /**
     * Beep this many seconds into each rest period, and every n seconds after that, so a
     * long rest is not just a number ticking up. 0 means no reminder.
     */
    val restBeepSeconds: Int = 0,
)
