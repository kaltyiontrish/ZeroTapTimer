<img width="347" height="651" alt="image" src="https://github.com/user-attachments/assets/fa3c6347-60e1-4e88-8cdf-0cde6d425a47" />

# ZeroTapTimer (Human Description) 
Its a basic Timer for gym exercises that allows you to create several timers and jump between them with voice commands.
Start - Starts timer
Pause - Pause timer
Continue/Resume - Resumes the timer
Reset - Reset timer and automatically starts 
Stop - Reset timer and waits for human instructions.
After the end of any timer it starts counting the rest period.
Thats basically it! There are more features but you have to read the AI bs bellow! ;)

// Ai description
# ZeroTapTimer

An Android interval timer for workouts. Kotlin + Jetpack Compose, **no third-party
dependencies and no binary assets** — the end-of-timer sounds are synthesized in code.

## What it does

- **One timer at a time, rest on autopilot.** Tap a button at the top to load a timer.
  When it reaches zero, rest starts by itself — no tapping mid-set — and when rest runs
  out the same timer reloads, ready for the next go. This is a menu of timers to pick
  from, not a sequence that plays through: nothing auto-advances, because nothing is
  ever played in order.
- **Your timer list.** Add, retime and remove timers in the editor. Each is just a length
  in seconds — there are no named muscle groups, because the UI never showed one.
- **A rest reminder**, optionally beeping every 15, 30 or 60 seconds so a long rest is
  more than a number counting up. Off by default.
- **8 end-of-timer sounds** (chime, gong, bleep, double ping, rise, fall, wood block,
  horn), selectable with a volume slider, and each is previewed when you pick it.
- **Voice control, always on and fully offline** — "timer" to start, "pause",
  "continue", "restart", "stop", plus **"one" to "twenty"** to jump straight to a
  timer. The mic stays open permanently, so no word falls through a gap.
- **Cues you can feel and see**: a haptic pulse on every transition, and a dial that
  unwinds clockwise and turns from red to green while resting.
- Screen stays awake and the system bars hide while a set is running.

The timer buttons wrap onto as many rows as they need, so the whole list is visible
at once rather than hidden behind a sideways swipe.

Rest is open-ended: it counts up and only ends when you start the next timer. Sounds
play on the **alarm** stream, so the phone's Alarm volume bar is the one that governs
them and the in-app slider is a multiplier on top of it. An app can never exceed the
system volume, so that bar is the ceiling.

## Build and test

Requires JDK 17 and the Android SDK (compileSdk 34, minSdk 26). Android Studio Ladybug
or newer is the easiest route.

> The Gradle wrapper JAR is a binary and is not checked in. Android Studio will sync the
> project on open using the distribution in `gradle/wrapper/gradle-wrapper.properties`.
> If you prefer the command line, generate the wrapper once with
> `gradle wrapper --gradle-version 8.7`.

```bash
./gradlew test        # unit tests only, no emulator, ~1s
./gradlew installDebug
```

## How to test it

**Fast loop — the logic, no device.** `TimerEngine` imports nothing from Android and reads
an injected clock, so its tests are plain JVM JUnit. Click the gutter ▶ in
`app/src/test/java/com/timergym/timer/TimerEngineTest.kt`, or run `./gradlew test`.
`CommandsTest` does the same for the voice keyword matching. Between them they cover the
exercise-to-rest handover, rest counting down, pausing in both stages, selecting and
skipping, and phrase parsing.

**Fast loop — the app.** Install a debug build and open **Settings → Debug · time speed**,
then pick `10x`. A 30-second exercise plus rest finishes in six seconds, so you can watch
every transition, sound and vibration without waiting. That control is compiled out of
release builds.

**Slow loop — hardware.** Use a real phone over USB, not the emulator: the emulator's
microphone cannot test the voice commands, which is the one thing worth verifying on
hardware. Android Studio's *Apply Changes* makes the rest of the UI iteration fast.

## Layout

```
app/src/main/java/com/timergym/
  MainActivity.kt          one activity, Compose entry point, keep-awake + fullscreen
  data/Models.kt           Step, Sound, AppSettings
  data/Repo.kt             SharedPreferences + org.json
  timer/TimerEngine.kt     PURE state machine: exercise -> rest -> ready, plus reminders
  timer/TimerViewModel.kt  drives the engine, owns sound + haptics
  sound/Cues.kt           PURE cue synthesis: partials, envelopes, PCM out
  sound/SoundPlayer.kt    AudioTrack streaming; owns the hardware, not the waveform
  voice/Commands.kt        PURE phrase -> command matching, and the Vosk grammar
  voice/VoiceController.kt AudioRecord stream + Vosk, all Vosk calls in one file
  ui/                      Theme, TimerScreen, EditTimers, SettingsSheet
```

## Voice control: one manual setup step

Voice runs fully offline on Vosk (`com.alphacephei:vosk-android`), not Android's
`SpeechRecognizer`. The microphone stays open permanently, so there is no start/stop tone
and no gap in which a word is missed.

**You must add the speech model by hand — it is 40 MB and is not in the repository:**

1. Download `vosk-model-small-en-us-0.15` from <https://alphacephei.com/vosk/models>
2. Unzip it
3. Rename the folder to `vosk-model-en-us`
4. Put that folder in `app/src/main/assets/`

It is unpacked to internal storage on first run. If the folder is missing the app says
"Voice model missing from the app" rather than failing quietly.

Commands: **timer**, **start**, **pause**, **continue**/**resume**, **restart**/**reset**,
**stop**, and **one** … **twenty** to load that exercise. English only.

## Known limits

- **The exercise grid is not lazy.** The buttons wrap in a `FlowRow`, so all of them are
  composed at once. That is fine for the two dozen exercises the editor realistically
  produces; a long list would push the dial down the screen.

- **No foreground service.** The session lives in a `ViewModel`, so it survives rotation
  and short backgrounding, but Android can kill the process if the app is swiped away
  mid-set. Upgrade path: a foreground service with a notification, driving the same
  `TimerEngine`.
- **The screen must stay on.** There is no wake lock, so with the display off the process
  may be frozen and time will be lost. `FLAG_KEEP_SCREEN_ON` covers the normal case where
  you are looking at the phone.
- **Voice commands are on-device and best-effort.** Accent, background music and a busy
  gym all affect recognition. The keyword list is a plain `Map` in `voice/Commands.kt` if
  you want to tune it.
- Sound names and most UI strings are hardcoded English, not in `strings.xml`.
