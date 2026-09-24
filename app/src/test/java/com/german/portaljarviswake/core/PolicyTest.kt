package com.german.portaljarviswake.core

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun handoffReleasesThenWaitsThenLaunches() { assertEquals(HandoffAction.ReleaseMic, HandoffPolicy.next(HandoffState.LISTENING, 0)); assertEquals(HandoffAction.LaunchAssistant, HandoffPolicy.next(HandoffState.YIELDING, 350)); assertEquals(HandoffAction.EnterCooldown, HandoffPolicy.next(HandoffState.ASSISTANT_ACTIVE, 120_000)) }
    @Test fun screenOffPolicyRequiresOptIn() { assertEquals(ScreenAction.Stop, ScreenPolicy.onScreenOff(false)); assertEquals(ScreenAction.KeepListening, ScreenPolicy.onScreenOff(true)) }
    @Test fun exponentialBackoffIsCapped() { assertEquals(500L, Backoff.delay(0)); assertEquals(4000L, Backoff.delay(3)); assertEquals(10_000L, Backoff.delay(8)) }
}
