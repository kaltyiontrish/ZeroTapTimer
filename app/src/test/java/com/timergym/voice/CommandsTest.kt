package com.timergym.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recogniser itself cannot be tested off-device, but the two pure parts that decide
 * what counts as a command can be: the phrase matcher and the grammar it is built from.
 */
class CommandsTest {

    private fun command(phrase: String) = match(phrase)?.command

    @Test
    fun `maps each command to its word`() {
        assertEquals(VoiceCommand.START, command("timer"))
        assertEquals(VoiceCommand.START, command("start"))
        assertEquals(VoiceCommand.RESTART, command("restart"))
        assertEquals(VoiceCommand.RESTART, command("reset"))
        assertEquals(VoiceCommand.PAUSE, command("pause"))
        assertEquals(VoiceCommand.CONTINUE, command("continue"))
        assertEquals(VoiceCommand.STOP, command("stop"))
    }

    @Test
    fun `number words select an exercise by position`() {
        // "two" has to give back its position, not just that it is a number.
        assertEquals(0, match("one")?.let { NUMBER_WORDS.indexOf(it.keyword) })
        assertEquals(1, match("two")?.let { NUMBER_WORDS.indexOf(it.keyword) })
        assertEquals(4, match("five")?.let { NUMBER_WORDS.indexOf(it.keyword) })
        assertEquals(11, match("twelve")?.let { NUMBER_WORDS.indexOf(it.keyword) })
        assertEquals(19, match("twenty")?.let { NUMBER_WORDS.indexOf(it.keyword) })
    }

    @Test
    fun `number words are not confused with commands`() {
        // "one" and "stop" are unrelated, and the earliest word still wins.
        assertEquals(VoiceCommand.SELECT, command("one"))
        assertEquals(VoiceCommand.STOP, command("stop"))
        assertEquals(VoiceCommand.SELECT, command("go to three"))
    }

    @Test
    fun `a number past twenty is not a command`() {
        assertNull(command("twenty one"))
    }

    @Test
    fun `restart does not also read as start`() {
        // "restart" contains "start" as characters; whole-word matching is what stops
        // saying one word from doing two things.
        assertEquals(VoiceCommand.RESTART, command("restart"))
        assertNull(command("starting"))
    }

    @Test
    fun `the earliest keyword in the phrase wins`() {
        assertEquals(VoiceCommand.RESTART, command("restart the timer"))
        assertEquals(VoiceCommand.STOP, command("stop the timer"))
        assertEquals(VoiceCommand.PAUSE, command("pause the timer"))
    }

    @Test
    fun `is case and punctuation insensitive`() {
        assertEquals(VoiceCommand.STOP, command("STOP!"))
        assertEquals(VoiceCommand.START, command("  Timer,  "))
        assertEquals(VoiceCommand.PAUSE, command("pause."))
    }

    @Test
    fun `only matches whole words`() {
        assertNull(command("stopper"))
        assertNull(command("timerz"))
        assertNull(command("pausing"))
        assertNull(command("continuing"))
    }

    @Test
    fun `ignores unrelated speech`() {
        assertNull(command("what time is it"))
        assertNull(command("hello there"))
        assertNull(command(""))
        assertNull(command("..."))
    }

    @Test
    fun `the grammar carries every command word and the noise token`() {
        // If a word is missing here the recogniser can never emit it, however well it is
        // matched, so this is the invariant most worth pinning down.
        val grammar = grammarJson()
        for (word in listOf("timer", "timers", "start", "restart", "reset",
            "pause", "continue", "resume", "stop")) {
            assertTrue("grammar should contain \"$word\": $grammar", grammar.contains("\"$word\""))
        }
        assertTrue("grammar needs [unk] to reject noise: $grammar", grammar.contains("\"[unk]\""))
    }

    @Test
    fun `the grammar contains no word the matcher does not know`() {
        // Anything in the grammar that match() cannot interpret is a wasted decode slot.
        val words = grammarJson()
            .removePrefix("[").removeSuffix("]").split(",")
            .map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        assertTrue("grammar needs [unk]", words.contains("[unk]"))
        for (word in words.filter { it != "[unk]" }) {
            assertNotNull("\"$word\" is in the grammar but matches nothing", match(word))
        }
    }
}
