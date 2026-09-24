package com.german.portaljarviswake.core

import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

object Phrase {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "").lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")
    fun isValid(value: String) = normalize(value).split(" ").size >= 2
}
data class RecognizedWord(val word: String, val confidence: Float?)
enum class Sensitivity(val lead: Float, val other: Float) { STRICT(.90f, .75f), BALANCED(.80f, .60f), RELAXED(.70f, .50f) }
class WakeMatcher(phrase: String, private val sensitivity: Sensitivity = Sensitivity.BALANCED) {
    private val expected = Phrase.normalize(phrase).split(" ")
    /** Plain Vosk transcripts are deliberately never accepted. */
    fun matches(words: List<RecognizedWord>): Boolean {
        if (words.size != expected.size || words.any { it.confidence == null }) return false
        val actual = words.map { Phrase.normalize(it.word) }
        if (actual != expected || actual.any { it == "unk" || it == "[unk]" }) return false
        return words[0].confidence!! >= sensitivity.lead && words.drop(1).all { it.confidence!! >= sensitivity.other }
    }
    @Deprecated("Use finalized word objects; transcript matching is not used by the service")
    fun matches(text: String, confidences: List<Float>): Boolean = matches(Phrase.normalize(text).split(" ").mapIndexed { index, word -> RecognizedWord(word, confidences.getOrNull(index)) })
}
class Debounce(private val windowMs: Long) { private var last = Long.MIN_VALUE / 2; fun accept(now: Long) = (now - last >= windowMs).also { if (it) last = now } }
enum class WakeState { IDLE, MODEL_MISSING, SCREEN_PAUSED, LISTENING, YIELDING, ASSISTANT_ACTIVE, COOLDOWN, MIC_UNAVAILABLE }
enum class WakeEvent { Start, Phrase, AssistantRecorderSeen, AssistantAbsentFiveSeconds, AcquireTimeout, SafetyTimeout, CooldownFinished, ScreenOff, ScreenOn, Stop, MicFailure }
enum class WakeEffect { StartListening, ReleaseMic, LaunchAssistant, AssistantActive, Cooldown, ScheduleAcquireTimeout, ScheduleSafetyTimeout, ScheduleCooldown, ScheduleAbsentCheck, Publish }
class WakeStateMachine(initial: WakeState = WakeState.IDLE) {
    var state: WakeState = initial
        private set

    fun on(event: WakeEvent): Set<WakeEffect> = when (event) {
        WakeEvent.Start -> when (state) {
            WakeState.IDLE, WakeState.MODEL_MISSING, WakeState.MIC_UNAVAILABLE ->
                transition(WakeState.LISTENING, WakeEffect.StartListening, WakeEffect.Publish)
            else -> emptySet()
        }

        WakeEvent.ScreenOn -> if (state == WakeState.SCREEN_PAUSED) {
            transition(WakeState.LISTENING, WakeEffect.StartListening, WakeEffect.Publish)
        } else {
            emptySet()
        }

        WakeEvent.CooldownFinished -> if (state == WakeState.COOLDOWN) {
            transition(WakeState.LISTENING, WakeEffect.StartListening, WakeEffect.Publish)
        } else {
            emptySet()
        }

        WakeEvent.Phrase -> if (state == WakeState.LISTENING) {
            transition(
                WakeState.YIELDING,
                WakeEffect.ReleaseMic,
                WakeEffect.LaunchAssistant,
                WakeEffect.ScheduleAcquireTimeout,
                WakeEffect.ScheduleSafetyTimeout,
                WakeEffect.Publish,
            )
        } else {
            emptySet()
        }

        WakeEvent.AssistantRecorderSeen -> if (
            state == WakeState.YIELDING || state == WakeState.ASSISTANT_ACTIVE
        ) {
            transition(WakeState.ASSISTANT_ACTIVE, WakeEffect.AssistantActive, WakeEffect.Publish)
        } else {
            emptySet()
        }

        WakeEvent.AssistantAbsentFiveSeconds -> if (state == WakeState.ASSISTANT_ACTIVE) {
            transition(
                WakeState.COOLDOWN,
                WakeEffect.Cooldown,
                WakeEffect.ScheduleCooldown,
                WakeEffect.Publish,
            )
        } else {
            emptySet()
        }

        WakeEvent.AcquireTimeout -> if (state == WakeState.YIELDING) {
            transition(
                WakeState.COOLDOWN,
                WakeEffect.Cooldown,
                WakeEffect.ScheduleCooldown,
                WakeEffect.Publish,
            )
        } else {
            emptySet()
        }

        WakeEvent.SafetyTimeout -> if (
            state == WakeState.YIELDING || state == WakeState.ASSISTANT_ACTIVE
        ) {
            transition(
                WakeState.COOLDOWN,
                WakeEffect.Cooldown,
                WakeEffect.ScheduleCooldown,
                WakeEffect.Publish,
            )
        } else {
            emptySet()
        }

        WakeEvent.ScreenOff -> if (state == WakeState.LISTENING) {
            transition(WakeState.SCREEN_PAUSED, WakeEffect.ReleaseMic, WakeEffect.Publish)
        } else {
            emptySet()
        }

        WakeEvent.Stop -> transition(WakeState.IDLE, WakeEffect.ReleaseMic, WakeEffect.Publish)
        WakeEvent.MicFailure -> transition(
            WakeState.MIC_UNAVAILABLE,
            WakeEffect.ReleaseMic,
            WakeEffect.Publish,
        )
    }

    private fun transition(next: WakeState, vararg effects: WakeEffect): Set<WakeEffect> {
        state = next
        return effects.toSet()
    }
}
object Backoff { fun delay(attempt: Int) = (500L shl attempt.coerceAtMost(5)).coerceAtMost(10_000L) }
enum class HandoffState { LISTENING, YIELDING, ASSISTANT_ACTIVE, COOLDOWN }
enum class HandoffAction { ReleaseMic, LaunchAssistant, EnterCooldown, None }
object HandoffPolicy { fun next(state: HandoffState, elapsedMs: Long) = when { state == HandoffState.LISTENING -> HandoffAction.ReleaseMic; state == HandoffState.YIELDING && elapsedMs >= 350 -> HandoffAction.LaunchAssistant; state == HandoffState.ASSISTANT_ACTIVE && elapsedMs >= 120_000 -> HandoffAction.EnterCooldown; else -> HandoffAction.None } }
enum class ScreenAction { Stop, KeepListening }
object ScreenPolicy { fun onScreenOff(optIn: Boolean) = if (optIn) ScreenAction.KeepListening else ScreenAction.Stop }
object SafeZip { fun isSafe(path: String) = path.isNotEmpty() && !path.startsWith("/") && !path.split('/').any { it == ".." || it.isEmpty() } }
data class ExtractionResult(val success: Boolean, val error: String? = null)
object SafeZipExtractor {
    fun extract(input: InputStream, destination: File, maxBytes: Long, maxEntries: Int): ExtractionResult = try {
        val root = destination.canonicalFile; var count = 0; var total = 0L
        ZipInputStream(input).use { zip -> var entry = zip.nextEntry; while (entry != null) {
            if (++count > maxEntries || entry.name.startsWith("/") || entry.name.startsWith("\\")) throw IllegalArgumentException("unsafe archive entry")
            val target = File(root, entry.name).canonicalFile
            if (target != root && !target.path.startsWith(root.path + File.separator)) throw IllegalArgumentException("archive traversal")
            if (entry.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { output -> val buffer = ByteArray(8192); var read: Int; while (zip.read(buffer).also { read = it } > 0) { total += read; if (total > maxBytes) throw IllegalArgumentException("archive too large"); output.write(buffer, 0, read) } } }
            zip.closeEntry(); entry = zip.nextEntry
        } }; ExtractionResult(true)
    } catch (t: Throwable) { destination.deleteRecursively(); ExtractionResult(false, t.message) }
}
