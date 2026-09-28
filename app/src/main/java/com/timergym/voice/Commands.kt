package com.timergym.voice

import java.util.Locale

enum class VoiceCommand { START, RESTART, PAUSE, CONTINUE, STOP, SELECT }

/**
 * "one" through "twenty", in order, so the matched word gives the exercise position.
 * Twenty is the useful ceiling: past that nobody counts exercises out loud.
 */
internal val NUMBER_WORDS = listOf(
    "one", "two", "three", "four", "five",
    "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen",
    "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
)

/** Result of a keyword match: what was heard, and which command it mapped to. */
data class VoiceMatch(val command: VoiceCommand, val spoken: String, val keyword: String)

// English only, because the bundled model is English: a word the acoustic model cannot
// hear is dead weight in the grammar and one more chance for a false trigger.
// Every entry here must be spoken, so the list stays as short as the commands allow.
private val KEYWORDS: Map<VoiceCommand, List<String>> = mapOf(
    VoiceCommand.START to listOf("timer", "timers", "start"),
    VoiceCommand.RESTART to listOf("restart", "reset"),
    VoiceCommand.PAUSE to listOf("pause"),
    VoiceCommand.CONTINUE to listOf("continue", "resume"),
    VoiceCommand.STOP to listOf("stop"),
    VoiceCommand.SELECT to NUMBER_WORDS,
)

/**
 * The grammar handed to Vosk, derived from the table above so there is one source of truth.
 *
 * The single most important invariant in the voice stack: the recogniser can only ever emit
 * words listed here, so anything missing from the grammar is a word the app can never hear,
 * however correctly it is spelt in [match]. `[unk]` lets the decoder reject noise rather
 * than forcing a guess, which is what stops a loud gym triggering something.
 */
internal fun grammarJson(): String {
    val words = LinkedHashSet<String>()
    for (keywords in KEYWORDS.values) words += keywords
    return "[" + words.joinToString(",") { "\"$it\"" } + ",\"[unk]\"]"
}

/**
 * Maps a recognized phrase to a command.
 *
 * Whole-word matching only. The earliest match in the phrase wins, so "restart the
 * timer" restarts rather than starting, and "stop the timer" stops.
 *
 * Deliberately free of Android types, which keeps it unit-testable on the JVM.
 */
fun match(phrase: String): VoiceMatch? {
    val words = phrase.lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return null

    var at = Int.MAX_VALUE
    var found: VoiceMatch? = null
    for ((command, keywords) in KEYWORDS) {
        for (keyword in keywords) {
            val i = words.indexOf(keyword)
            if (i >= 0 && i < at) {
                at = i
                found = VoiceMatch(command, phrase, keyword)
            }
        }
    }
    return found
}
