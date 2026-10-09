# Changelog

History of the MVP versions. References to PLAN.md point at an internal planning document that is not part of this repo.

## Version 0.2.8-mvp

Fixed: in 0.2.7 the checkup did not scroll and wrongly reported "Listenende erreicht". List selection now takes the nearest scrollable ancestor of the chat rows, or their geometry (never a pager or a horizontal list). The swipe still runs when there is no list node. "Listenende" applies only after a scroll attempt whose content did not change. Otherwise the status is "Scrollen nicht moeglich", with an error state and a warning. New logo (draft 2a): a speech bubble in a turquoise-to-violet gradient with three cut-out AI sparkles, no magnifying glass.

Tests: `./gradlew :app:testDebugUnitTest` (355 tests, 1 skipped). Nothing tested on a device. Details in PLAN.md section 22.

## Version 0.2.7-mvp

New: the floating dot can be turned off from the ring with "Entfernen" (turn it back on in Settings, "Schwebender Punkt"). New logo (magnifying glass with a speech bubble and a spark, three variants, default B, switch with `python3 tools/make_logo.py --default a|b|c`). "Analysieren" reads the chat and then asks whether to evaluate it as usual or with your own prompt. Progress display with a step bar, a counter, and seconds on the model step (no token counter, because output is not streamed). New Models tab with compact rows and dot ratings (our judgment, kept separate from measured values). Parakeet downloads itself when voice messages are turned on (Wi-Fi, or mobile data after a confirmation). The "Analyse" (task) tab is gone. Its settings are now under Settings, and the status is on the home screen. A profile per chat of up to 6000 characters in 9 sections, a DISC read of the other person (a cautious estimate), a self profile (the user's style across chats, with a filter against chat content), and a self analysis across several chats with confirmation before it is applied. Setup, self analysis, and Auto are available only after a checkup (assistant on the home screen).

Tests: `./gradlew :app:testDebugUnitTest` (337 tests, 1 skipped). Render images land in `app/build/renders`. Nothing tested on a device. Details in PLAN.md section 21.

## Version 0.2.6-mvp

New: voice messages are transcribed locally. Model: NVIDIA Parakeet TDT 0.6B v3 (25 European languages including German, license CC-BY-4.0, attribution to NVIDIA; ONNX conversion by k2-fsa/csukuangfj), INT8, four files totaling 670478772 bytes, downloadable on the Models tab (catalog entry with a SHA-256 per file, pinned Hugging Face revision). Runtime: sherpa-onnx 1.13.8 (JitPack), Opus decoding with a custom Ogg reader and Concentus (Java), fallback to MediaCodec. Audio files come from the folder chosen through the Storage Access Framework, `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Voice Notes`. A message is matched to a file by time and duration. The text then appears in the transcript as "Transkript" for the LLM. Settings: a "Sprachnachrichten" section (switch, folder, limits, self-test). Log: `STIMME:` lines and a "Sprachnachrichten" section in the Markdown log, without texts or file names. APK 104669932 bytes (plus 32.9 MB, because of the native libraries). 266 unit tests, including real recognition with the model on the development machine (skipped when the model is absent). Not tested on a phone: folder access under HyperOS, matching against real files, speed, and memory. See PLAN.md section 20.

Attribution (CC-BY-4.0): Parakeet TDT 0.6B v3 by NVIDIA, https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 ; sherpa-onnx by k2-fsa (Apache-2.0); Concentus (BSD-3-Clause).

## Version 0.2.5-mvp

New: checkup and selection menu. The checkup reads the top X chats in the chat list (default 50, vertical only, inside the safety window, no chat is opened, the Chats tab is checked), at app start (switch, on by default) or via the "Checkup jetzt" button. In the menu you tick chats (search, all, none, order, newly marked, previous selection preselected, top 10, 20, 30 as shortcuts). Setup works only on the ticked chats. The selection (names only) is stored encrypted. See ../PLAN.md section 19. Not tested on a device.

## Version 0.2.4-mvp

Fixes after the 0.2.3 device test: swipes stay strictly inside the safety window (12 percent edge margin, status bar plus 150 px, gesture zone plus 250 px, vertical only, at most two swipes), a check after every swipe that closes the notification shade or the launcher and records the trigger in the log, never paging tabs (pagers excluded, only ACTION_SCROLL_UP/DOWN), the Chats tab is checked and tapped on purpose, opening without a second tap, short-name diagnostics, an error state with a reason and a pause after 2 errors, a Markdown log (buttons on the Debug tab and the Start tab, automatic save under `runlogs`). See ../PLAN.md section 18. Not tested on a device.

## Version 0.2.3-mvp

Fixes after the 0.2.2 device log: encryption (the Android Keystore rejects caller-supplied IVs on encrypt; memory and the queue could never be saved), a foreground guard (before every read, click, and back it checks that WhatsApp is in front; otherwise it brings WhatsApp back by intent up to 3 times, then aborts cleanly, and never clicks in someone else's window), the chat list (detection via node ids, back only from a chat or an active search, rows via ids, automatic debug tree on the Debug tab), contrast (light text on dark glass, cards 70 percent opaque, WCAG AA covered by a test). See ../PLAN.md section 17. Not tested on a device. Preview images: `./gradlew :app:testDebugUnitTest --tests 'app.chatlens.render.RenderTest'`.

## Version 0.2.2-mvp

New: Qwen3.5 in the model catalog (4B mixed INT4 as a comparison candidate next to Gemma 4 E4B, 4B INT8 experimental, 0.8B INT8 CPU only; 2B stays experimental). All of them with the full commit and LFS SHA-256 from the Hugging Face API, text only, KV budget 4096 tokens (character limit 8988), marked "Deutsch nicht belegt". The recommendation card shows the comparison candidate next to the default. `/no_think` is appended only for Qwen3, not for Qwen3.5. Qwen3.6 has no `.litertlm` file and was not included (PLAN.md 16.10). Details: PLAN.md 16.9 through 16.13. Not tested on a device.

## Version 0.2.1-mvp

New: a "Modelle" tab (model catalog). Gemma 4 E4B (default), Gemma 4 E2B, and Qwen3 variants (LiteRT-LM) with size, license, RAM need, suitability, and a recommendation for the device. Download with progress, pause, resume, SHA-256, storage and Wi-Fi checks. Open the source. Use as the backend. Gated models as a link only. Parakeet as a preview with a link. Catalog: `app/src/main/assets/model-catalog.json`, checked with `tools/verify_catalog.py --check` (Hugging Face API). Details: PLAN.md section 16. Not tested on a device.

Real download test (optional, 345 MB): `CHATLENS_NET_TEST=1 ./gradlew :app:testDebugUnitTest --tests 'app.chatlens.models.RealDownloadTest'`.

## Version 0.2.0-mvp

New: setup as the first step (the newest 10, 20, 30, or a freely chosen number of chats are opened automatically from the chat list, read, and stored as a profile in memory; progress, cancel, skip the error for one chat, resume, overview), name matching with a confirmation, auto mode (incremental), encrypted memory (Android Keystore, AES-GCM, export and delete), an adviser, reply suggestions (nothing is sent automatically; placement into the input field; an experimental send button that is off and behind a dialog), a floating dot with a ring (overlay), a glass design, preparation for voice messages (interface only). Details: PLAN.md section 15. Installation: INSTALL-XIAOMI.md 5a and 5b. Not tested on a device.

Preview of the interface without an emulator: `./gradlew :app:testDebugUnitTest --tests 'app.chatlens.render.RenderTest'` writes PNG files to `app/build/renders`.

## Version 0.1.4-mvp

End of the loaded history: detection of "Inhalt unverändert" (no lost overlap, no mode change), waiting for more content for up to 6 s with up to 3 swipe attempts, a clean stop with a reason and then analysis of what was captured, hard limits (4 steps with no new lines, 6 recoveries with no progress). A counter-step with shift 0 does not count as success. Debug switch "Bei unverändertem Inhalt beenden". Details: PLAN.md section 14. Not tested on a device.

## Version 0.1.3-mvp

Controlled scrolling: a long swipe gesture (450 ms plus hold time) with no fling, self-calibration from the measured image shift (pixels in the log), target overlap 35 percent, a counter-step instead of a gap when overlap is lost, an accurate mode (micro swipes), methods selectable on the Debug tab, more robust alignment (longest common subsequence), merging of opposite cut-off edges, statistics and a status line. Details: PLAN.md section 13. Not tested on a device.

## Version 0.1.2-mvp

A target count (default 100 messages) instead of only N scrolls, a swipe of at most 70 percent of the step, adaptive waiting instead of pauses (default as fast as possible, adjustable on the Debug tab), stop at the start of the chat only after 3 failed attempts and a signal, edge messages marked as cut off and replaced on the next scroll, "Mehr lesen" marked as truncated. Details: PLAN.md section 12. Not tested on a device.

## Version 0.1.1-mvp

Mode "Chat ist schon geöffnet": no more launching WhatsApp by intent. Instead a countdown (Debug tab) or "Jetzt lesen" in the notification. Search navigation picks only hits in the "Chats" section (title comparison), clicks with ACTION_CLICK, and falls back to a tap gesture, then checks the header. Details: PLAN.md section 11. Not tested on a device.
