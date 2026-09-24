package com.german.portaljarviswake.core

import org.junit.Assert.*
import org.junit.Test

class WakeMatcherTest {
    @Test fun normalizesAndValidatesAtLeastTwoWords() { assertEquals("hey jarvis", Phrase.normalize(" Hey,  JARVIS! ")); assertFalse(Phrase.isValid("jarvis")); assertTrue(Phrase.isValid("hey jarvis")) }
    @Test fun requiresExactFinalPhraseAndConfidences() { val matcher = WakeMatcher("hey jarvis"); assertTrue(matcher.matches("Hey Jarvis", listOf(.9f, .8f))); assertFalse(matcher.matches("hey jarvis now", listOf(.9f,.8f,.9f))); assertFalse(matcher.matches("hey [unk]", listOf(.9f,.9f))); assertFalse(matcher.matches("hey jarvis", listOf(.7f,.9f))) }
    @Test fun debounceBlocksNearbyMatches() { val gate = Debounce(3000); assertTrue(gate.accept(100)); assertFalse(gate.accept(3099)); assertTrue(gate.accept(3100)) }
}
