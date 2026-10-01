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
    fun `swap number words select a timer by position`() {
        // "swap two" has to give back its position, not just that it is a number.
        fun position(phrase: String) =
            match(phrase)?.let { NUMBER_WORDS.indexOf(it.keyword.substringAfterLast(' ')) }
        assertEquals(0, position("swap one"))
        assertEquals(1, position("swap two"))
        assertEquals(4, position("swap five"))
        assertEquals(11, position("swap twelve"))
        assertEquals(19, position("swap twenty"))
    }

    /**
     * The reason the prefix exists. A bare number in the grammar is a false trigger waiting
     * to happen over music, so none of them may match on their own any more.
     */
    @Test
    fun `a bare number is not a command`() {
        for (word in NUMBER_WORDS) {
            assertNull("\"$word\" must not trigger on its own", command(word))
        }
        assertNull(command("go to three"))
    }

    @Test
    fun `the swap prefix alone is not a command`() {
        assertNull(command("swap"))
        assertNull(command("swap the timer"))
    }

    @Test
    fun `number words are not confused with commands`() {
        assertEquals(VoiceCommand.STOP, command("stop"))
        assertEquals(VoiceCommand.SELECT, command("swap three"))
    }

    @Test
    fun `a number past twenty is not a command`() {
        assertNull(command("twenty one"))
        assertNull(command("swap twenty one"))
    }

    @Test
    fun `swap does not disturb the other commands`() {
        assertEquals(VoiceCommand.PAUSE, command("swap the timer pause"))
        assertEquals(VoiceCommand.SELECT, command("swap two pause"))
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

    /**
     * The invariant that actually breaks: anything the grammar can emit must map to a
     * command, and every keyword must reach the grammar. Iterating KEYWORDS rather than
     * re-parsing the JSON means this cannot drift out of step with the table.
     */
    @Test
    fun `every keyword reaches the grammar and maps back to its command`() {
        val grammar = grammarJson()
        assertTrue("grammar needs [unk] to reject noise: $grammar", grammar.contains("\"[unk]\""))
        for ((command, keywords) in KEYWORDS) {
            for (keyword in keywords) {
                assertTrue(
                    "\"$keyword\" is missing from the grammar: $grammar",
                    grammar.contains("\"$keyword\""),
                )
                val m = match(keyword)
                assertNotNull("\"$keyword\" is a keyword but matches nothing", m)
                assertEquals("\"$keyword\" mapped to the wrong command", command, m?.command)
            }
        }
    }

    /**
     * The grammar format, pinned because the failure is silent and misleading: a malformed
     * grammar does not fall back, it makes the Recogniser constructor throw, which the app
     * reports as "recogniser failed to start" with nothing pointing at the grammar.
     */
    @Test
    fun `grammar is a flat list with phrases spelled out as words`() {
        val grammar = grammarJson()
        assertTrue(
            "phrases must be space joined: $grammar",
            grammar.contains("\"swap one\""),
        )
        assertTrue("single words stay bare: $grammar", grammar.contains("\"pause\""))
        // Flat list. A nested array is the format that is easiest to reach for and the
        // official grammar never uses one, so it is pinned here explicitly.
        assertTrue(
            "grammar must not nest an array: $grammar",
            !grammar.drop(1).dropLast(1).contains("["),
        )
        assertTrue("grammar must end with [unk]: $grammar", grammar.endsWith("\"[unk]\"]"))
    }
}
