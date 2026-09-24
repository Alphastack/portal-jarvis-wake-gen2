package com.german.portaljarviswake.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RegressionTest {
    @Test fun `matcher requires exact finalized words and every confidence`() {
        val matcher = WakeMatcher("Hey Jarvis")
        assertTrue(matcher.matches(listOf(RecognizedWord("hey", .8f), RecognizedWord("jarvis", .6f))))
        assertFalse(matcher.matches(listOf(RecognizedWord("well", .99f), RecognizedWord("hey", .99f), RecognizedWord("jarvis", .99f))))
        assertFalse(matcher.matches(listOf(RecognizedWord("hey", null), RecognizedWord("jarvis", .99f))))
        assertFalse(matcher.matches(listOf(RecognizedWord("hey", .99f), RecognizedWord("[unk]", .99f))))
    }

    @Test fun `zip extractor rejects traversal absolute oversized and too many entries`() {
        fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            ZipOutputStream(out).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
            return out.toByteArray()
        }
        val target = Files.createTempDirectory("zip-test").toFile()
        listOf("../bad" to byteArrayOf(1), "/bad" to byteArrayOf(1)).forEach { entry ->
            assertFalse(SafeZipExtractor.extract(ByteArrayInputStream(zip(entry)), target, 100, 2).success)
        }
        assertFalse(SafeZipExtractor.extract(ByteArrayInputStream(zip("a" to ByteArray(101))), target, 100, 2).success)
        assertFalse(SafeZipExtractor.extract(ByteArrayInputStream(zip("a" to byteArrayOf(), "b" to byteArrayOf(), "c" to byteArrayOf())), target, 100, 2).success)
        target.deleteRecursively()
    }

    @Test fun `state machine waits for confirmed assistant disappearance before cooldown`() {
        val machine = WakeStateMachine()
        machine.on(WakeEvent.Start)
        assertTrue(machine.on(WakeEvent.Phrase).contains(WakeEffect.ReleaseMic))

        val assistantSeen = machine.on(WakeEvent.AssistantRecorderSeen)
        assertTrue(assistantSeen.contains(WakeEffect.AssistantActive))
        assertFalse(assistantSeen.contains(WakeEffect.ScheduleAbsentCheck))
        assertTrue(machine.state == WakeState.ASSISTANT_ACTIVE)

        val repeatedSeen = machine.on(WakeEvent.AssistantRecorderSeen)
        assertFalse(repeatedSeen.contains(WakeEffect.ScheduleAbsentCheck))
        assertTrue(machine.state == WakeState.ASSISTANT_ACTIVE)

        assertTrue(machine.on(WakeEvent.AssistantAbsentFiveSeconds).contains(WakeEffect.Cooldown))
        assertTrue(machine.state == WakeState.COOLDOWN)
    }

    @Test fun `screen events do not interrupt an assistant turn`() {
        val machine = WakeStateMachine()
        machine.on(WakeEvent.Start)
        machine.on(WakeEvent.Phrase)
        machine.on(WakeEvent.AssistantRecorderSeen)

        assertTrue(machine.on(WakeEvent.ScreenOff).isEmpty())
        assertTrue(machine.state == WakeState.ASSISTANT_ACTIVE)
        assertTrue(machine.on(WakeEvent.ScreenOn).isEmpty())
        assertTrue(machine.state == WakeState.ASSISTANT_ACTIVE)
    }
}
