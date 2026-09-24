package com.german.portaljarviswake.core

import java.text.Normalizer
import java.util.Locale

object Phrase {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
    fun isValid(value: String) = normalize(value).split(" ").filter { it.isNotBlank() }.size >= 2
}

class WakeMatcher(phrase: String) {
    private val words = Phrase.normalize(phrase).split(" ")
    fun matches(text: String, confidences: List<Float>): Boolean {
        val candidate = Phrase.normalize(text).split(" ").filter { it.isNotBlank() }
        if (candidate != words || confidences.size < words.size || candidate.any { it == "unk" }) return false
        return confidences[0] >= .80f && confidences.drop(1).take(words.size - 1).all { it >= .60f }
    }
}
class Debounce(private val windowMs: Long) { private var last = Long.MIN_VALUE / 2; fun accept(now: Long): Boolean = (now - last >= windowMs).also { if (it) last = now } }
enum class HandoffState { LISTENING, YIELDING, ASSISTANT_ACTIVE, COOLDOWN }
enum class HandoffAction { ReleaseMic, LaunchAssistant, EnterCooldown, None }
object HandoffPolicy { fun next(state: HandoffState, elapsedMs: Long) = when { state == HandoffState.LISTENING -> HandoffAction.ReleaseMic; state == HandoffState.YIELDING && elapsedMs >= 350 -> HandoffAction.LaunchAssistant; state == HandoffState.ASSISTANT_ACTIVE && elapsedMs >= 120_000 -> HandoffAction.EnterCooldown; else -> HandoffAction.None } }
enum class ScreenAction { Stop, KeepListening }
object ScreenPolicy { fun onScreenOff(optIn: Boolean) = if (optIn) ScreenAction.KeepListening else ScreenAction.Stop }
object Backoff { fun delay(attempt: Int) = (500L shl attempt.coerceAtMost(5)).coerceAtMost(10_000L) }
object SafeZip { fun isSafe(path: String): Boolean = path.isNotEmpty() && !path.startsWith("/") && !path.split('/').any { it == ".." || it.isEmpty() } }
