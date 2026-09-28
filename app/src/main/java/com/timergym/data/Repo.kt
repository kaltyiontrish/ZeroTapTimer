package com.timergym.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistence on SharedPreferences + org.json (both bundled with Android), so no
 * serialization dependency is needed for a list this small.
 */
class Repo(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("timergym", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun setSteps(steps: List<Step>) = update { it.copy(steps = steps) }
    fun setSound(sound: Sound) = update { it.copy(sound = sound) }
    fun setVolume(volume: Float) = update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    fun setHaptics(on: Boolean) = update { it.copy(haptics = on) }
    fun setVoice(on: Boolean) = update { it.copy(voice = on) }
    fun setVoiceDebug(on: Boolean) = update { it.copy(voiceDebug = on) }
    fun setVoiceTestPanel(on: Boolean) = update { it.copy(voiceTestPanel = on) }
    fun setRestBeepSeconds(seconds: Int) =
        update { it.copy(restBeepSeconds = seconds.coerceAtLeast(0)) }

    private fun update(block: (AppSettings) -> AppSettings) {
        val next = block(_settings.value)
        _settings.value = next
        persist(next)
    }

    private fun load(): AppSettings {
        val raw = prefs.getString(KEY, null) ?: return defaults()
        return runCatching { parse(JSONObject(raw)) }.getOrElse { defaults() }
    }

    private fun parse(o: JSONObject): AppSettings {
        val arr = o.getJSONArray("steps")
        val steps = buildList {
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                add(
                    Step(
                        id = s.getLong("id"),
                        // "type" used to be stored here and is now ignored, so prefs written
                        // by an older build still load.
                        seconds = s.getInt("sec").coerceIn(1, 3600),
                    )
                )
            }
        }
        // An empty list would break TimerEngine, so fall back to the default here.
        return AppSettings(
            steps = steps.ifEmpty { defaults().steps },
            sound = runCatching { Sound.valueOf(o.optString("sound", "BELL")) }
                .getOrDefault(Sound.BELL),
            haptics = o.optBoolean("haptics", true),
            voice = o.optBoolean("voice", false),
            voiceDebug = o.optBoolean("voiceDebug", true),
            voiceTestPanel = o.optBoolean("voiceTestPanel", true),
            restBeepSeconds = o.optInt("restBeep", 0).coerceIn(0, 600),
            // clamp: a hand-edited or corrupted value must not become a gain > 1.
            volume = o.optDouble("vol", 1.0).toFloat().coerceIn(0f, 1f),
        )
    }

    private fun persist(s: AppSettings) {
        val arr = JSONArray()
        s.steps.forEach { step ->
            arr.put(
                JSONObject()
                    .put("id", step.id)
                    .put("sec", step.seconds)
            )
        }
        val json = JSONObject()
            .put("steps", arr)
            .put("sound", s.sound.name)
            .put("vol", s.volume.toDouble())
            .put("haptics", s.haptics)
            .put("voice", s.voice)
            .put("voiceDebug", s.voiceDebug)
            .put("voiceTestPanel", s.voiceTestPanel)
            .put("restBeep", s.restBeepSeconds)
            .toString()
        prefs.edit().putString(KEY, json).apply()
    }

    private fun defaults() = AppSettings(
        // All different: the button row shows only the number, so two timers sharing a
        // length would produce two identical-looking buttons.
        steps = listOf(
            Step(1, 60),
            Step(2, 40),
            Step(3, 45),
        )
    )

    private companion object {
        const val KEY = "settings"
    }
}
