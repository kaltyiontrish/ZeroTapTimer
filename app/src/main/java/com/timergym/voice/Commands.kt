package com.timergym.voice

import java.util.Locale

enum class VoiceCommand { START, RESTART, PAUSE, CONTINUE, STOP, SELECT }

/**
 * "one" through "twenty", in order, so the matched keyword gives the exercise position.
 * Twenty is the useful ceiling: past that nobody counts exercises out loud.
 */
internal val NUMBER_WORDS = listOf(
    "one", "two", "three", "four", "five",
    "six", "seven", "eight", "nine", "ten",
    "eleven", "twelve", "thirteen", "fourteen", "fifteen",
    "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
)

/**
 * Spoken before a number to load that timer: "swap three", not "three".
 *
 * The recogniser is given a restricted vocabulary, so it must emit something from that
 * list or [unk]. With bare number words in the grammar, a beat of music was enough to
 * force a false match — the decoder had no more likely candidate. Requiring two words
 * means noise has to complete a two-word phrase before it can trigger anything.
 */
private const val SWAP = "swap"

/** Result of a keyword match: what was heard, and which command it mapped to. */
data class VoiceMatch(val command: VoiceCommand, val spoken: String, val keyword: String)

// English only, because the bundled model is English: a word the acoustic model cannot
// hear is dead weight in the grammar and one more chance for a false trigger.
// Every entry here must be spoken, so the list stays as short as the commands allow.
/**
 * Internal rather than private so [CommandsTest] can assert the grammar and this table
 * agree, which is the invariant that actually breaks in practice.
 */
internal val KEYWORDS: Map<VoiceCommand, List<String>> = mapOf(
    VoiceCommand.START to listOf("timer", "timers", "start"),
    VoiceCommand.RESTART to listOf("restart", "reset"),
    VoiceCommand.PAUSE to listOf("pause"),
    VoiceCommand.CONTINUE to listOf("continue", "resume"),
    VoiceCommand.STOP to listOf("stop"),
    VoiceCommand.SELECT to NUMBER_WORDS.map { "$SWAP $it" },
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
    // Vosk's own example grammar is:
    //   ["oh one two three", "four five six", "seven eight nine zero", "[unk]"]
    // A flat list, where a multi-word entry is one phrase spelled with spaces. The formats
    // that look equally plausible and are wrong:
    //   "swap one"        read as one symbol called "swap one", never heard in audio
    //   ["swap","one"]    an array of two words, not a phrase
    //   ["swap one"]      a nested array; the official grammar has none
    // A bad grammar makes the Recognizer constructor throw rather than degrade, which is
    // why the test below pins the shape.
    words += "[unk]"
    return words.joinToString(",", "[", "]") { "\"$it\"" }
}

/**
 * Index of the first word of [keyword] within [words], or -1.
 *
 * Keywords may be phrases, so this compares a whole run of words rather than one. It
 * returns where the phrase *starts*, which is what keeps "earliest keyword wins" honest
 * when one keyword is two words and another is one.
 */
private fun indexOfSequence(words: List<String>, keyword: String): Int {
    val parts = keyword.split(' ')
    if (parts.size > words.size) return -1
    outer@ for (i in 0..words.size - parts.size) {
        for ((j, part) in parts.withIndex()) {
            if (words[i + j] != part) continue@outer
        }
        return i
    }
    return -1
}

/**
 * Maps a recognized phrase to a command.
 *
 * Whole-word and whole-phrase matching only. The earliest match in the phrase wins, so
 * "restart the timer" restarts rather than starting, and "swap three" swaps rather than
 * starting on "swap". Deliberately free of Android types, which keeps it unit-testable on
 * the JVM.
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
            val i = indexOfSequence(words, keyword)
            if (i >= 0 && i < at) {
                at = i
                found = VoiceMatch(command, phrase, keyword)
            }
        }
    }
    return found
}
