# ChatLens

ChatLens is an Android app that reads an open WhatsApp chat through the accessibility service, scrolls back through the history, and has a language model evaluate it. By default the model runs locally on the phone (Gemma 4 via LiteRT-LM). ChatLens never sends messages itself. Reply suggestions land at most as a draft in the input field. You always press send.

> Status: MVP (version 0.3.0). Built and checked with unit tests and emulator runs. Only partly tested on real devices. WhatsApp changes its interface regularly, and the selector profiles have to be updated when that happens.

<p align="center">
  <img src="docs/images/wizard-1-willkommen.jpg" width="200" alt="Welcome screen of the setup wizard">
  <img src="docs/images/start-assistent.jpg" width="200" alt="Home screen with the assistant">
  <img src="docs/images/overlay-ring.jpg" width="200" alt="Floating dot with the ring menu">
  <img src="docs/images/overlay-panel-langes-ergebnis.jpg" width="200" alt="Result in the panel over WhatsApp">
</p>

## Features

* **Floating dot**: A small dot sits over WhatsApp. A tap opens a ring with actions such as analysis, suggestion, best, self, update, options, and remove. The result appears in a panel directly over the chat.
* **Read a chat**: The chat is read from the accessibility tree, scrolled backward several times, and assembled into a clean transcript (sender, time, text, your message or theirs).
* **Analysis and reply suggestions**: The transcript goes to the model together with an instruction. If you want, the reply can be placed in the input field as a draft.
* **Local models**: A model catalog with Gemma 4 E2B and E4B, plus Qwen3 and Qwen3.5 as LiteRT-LM files. Downloads show progress and support pause, resume, and a SHA-256 check. The app recommends a model that fits the device's memory.
* **Voice messages**: Optional on-device transcription with NVIDIA Parakeet TDT 0.6B v3 through sherpa-onnx. The audio files come from a folder you grant access to yourself.
* **Text recognition in images**: Offline, through ML Kit.
* **Checkup and selection**: Reads the top chats in the chat list without opening a chat, then lets you choose which chats to work on.
* **Memory**: A profile for each chat, a cautious DISC read of the other person, and a self profile of your own writing style. All of it is encrypted on the device.
* **Setup wizard**: Walks through accessibility, the overlay permission, model choice, and the first checkup.

<p align="center">
  <img src="docs/images/checkup-menu.jpg" width="200" alt="Checkup and chat selection">
  <img src="docs/images/modelle-kompakt.jpg" width="200" alt="Model catalog">
  <img src="docs/images/sprachnachrichten-einstellungen.jpg" width="200" alt="Voice message settings">
</p>

## Layout

```
app/                      Android app (Kotlin, Jetpack Compose)
  src/main/kotlin/app/chatlens/
    service/              ChatAccessibilityService (reads the screen), OverlayService (floating dot), foreground service
    assist/               ReplyActions (write a draft into the input field), transcription, assist actions
    profile/              SelectorProfile (view ids per messenger)
    parse/                Parsers for the chat list and the transcript, merging of scrolled pages
    llm/                  LiteRT-LM backend, OpenAI-compatible backend, prompt and context assembly
    models/               Model catalog, downloader, storage check
    asr/                  Voice messages (Ogg/Opus, Parakeet via sherpa-onnx)
    memory/               Memory, profiles, DISC, self profile, encryption
    vision/               Text recognition and image tools
    ui/                   Interface, wizard, overlay panel
  src/main/assets/
    profiles/             Selector profiles for WhatsApp, Telegram, Signal
    model-catalog.json    Model catalog with pinned revisions and checksums
fakewa/                   WhatsApp stand-in with synthetic test data for emulator tests
tools/                    Emulator regression, catalog check, logo tools
docs/                     Images, changelog, draft privacy policy
```

There are two product flavors:

* `full`: optional API mode (your own OpenAI-compatible server), for development and direct distribution.
* `play`: no API mode and no cleartext HTTP, for a later store build.

## Build

Requirements: JDK 17, Android SDK with platform 36 and build-tools 36.0.0. Create a `local.properties` file with `sdk.dir=/path/to/android-sdk` (it is not committed).

```bash
./gradlew :app:assembleFullDebug        # APK at app/build/outputs/apk/full/debug/
./gradlew :app:assemblePlayDebug        # APK at app/build/outputs/apk/play/debug/
./gradlew :app:testFullDebugUnitTest    # JVM tests on synthetic trees
./gradlew :app:lintFullDebug
```

The app is limited to arm64 (ABI filter) because LiteRT-LM, sherpa-onnx, and ML Kit ship native libraries. Models are not inside the APK. The app downloads them.

## Sideload install

1. Copy the APK to the phone and open it. Allow installation from unknown sources for the file manager or browser.
2. Open ChatLens and follow the wizard.
3. Turn on the accessibility service **ChatLens Chat-Leser** (Settings, Accessibility, Downloaded apps).
4. For the floating dot, allow **display over other apps**.
5. On the Models tab, download a model, preferably on Wi-Fi.

### Note for Xiaomi, HyperOS, and MIUI

On sideloaded apps the accessibility switch is often greyed out. Unlock it like this:

1. Settings, Apps, Manage apps, **ChatLens**.
2. Menu at the top right (three dots), **Allow restricted settings**.
3. Turn the accessibility service on again.

To keep the floating dot reliable, also allow **Autostart** in the app settings, set the battery to **No restrictions**, and allow **display pop-up windows while in background**.

## Privacy

* Chat content is read only when you start a task.
* With a local model, no chat text leaves the device.
* Memory, selection, and settings live in private app storage, encrypted with AES-GCM, key in the Android Keystore. Cloud backup is off.
* No ads, no analytics SDKs, no sharing with third parties.
* Only the `full` flavor lets you enter your own API server. Chat text then goes to that server. That is a transfer of other people's personal data, and you are responsible for it.
* ChatLens never presses the send button.

A draft privacy policy is at [docs/PRIVACY.md](docs/PRIVACY.md).

**Important:** WhatsApp's terms of service prohibit automated access. Your account can be restricted or banned. Use it at your own risk and only with your own account. ChatLens is not affiliated with WhatsApp or Meta.

## Projects built on this

* **[MemEm](https://github.com/slickdajackson/MemEm)**: a meme generator for the chat. MemEm uses the ChatLens foundation (floating dot, accessibility access to WhatsApp, writing into the input field) and turns your message into matching memes with local models.

## License

The project's own code is under the [MIT License](LICENSE). Third-party libraries and models keep their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The changelog for the MVP versions is in [docs/CHANGELOG.md](docs/CHANGELOG.md).
