# Third-party notices

ChatLens's own source code is under the MIT License (see [LICENSE](LICENSE)). The components below come from third parties and keep their own licenses. Libraries are fetched from Maven at build time. Models are downloaded by the user at runtime. No model file is included in this repo.

## Libraries (included in the APK)

| Component | Version | License | Source |
|---|---|---|---|
| Kotlin Standard Library | 2.4.10 | Apache 2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.10.2 | Apache 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX (Core, Activity, Lifecycle, Jetpack Compose, Material 3, RecyclerView, ViewPager2) | see build.gradle.kts | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| LiteRT-LM for Android (Google AI Edge) | 0.17.1 | Apache 2.0 | https://github.com/google-ai-edge/LiteRT-LM |
| sherpa-onnx (k2-fsa) | 1.13.8 | Apache 2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (bundled in sherpa-onnx) | | MIT | https://github.com/microsoft/onnxruntime |
| Concentus (Opus decoder in Java) | 1.0.2 | BSD 3-Clause | https://github.com/lostromb/concentus |
| Google ML Kit Text Recognition | 16.0.1 | ML Kit Terms of Service (not open source; freely redistributable binary library) | https://developers.google.com/ml-kit/terms |

## Tools

| Component | License | Source |
|---|---|---|
| Gradle Wrapper (gradlew, gradle-wrapper.jar) | Apache 2.0 | https://github.com/gradle/gradle |
| Android Gradle Plugin | Apache 2.0 | https://developer.android.com/build |
| JUnit 4 (tests only) | EPL 1.0 | https://github.com/junit-team/junit4 |
| Robolectric (tests only) | MIT | https://github.com/robolectric/robolectric |
| org.json (tests only) | Public Domain | https://github.com/stleary/JSON-java |

## Models (not included; optional download in the app)

| Model | License | Source |
|---|---|---|
| Gemma 4 E2B and E4B (LiteRT-LM) | Gemma Terms of Use | https://ai.google.dev/gemma/terms |
| Gemma 3 1B IT (import only) | Gemma Terms of Use | https://ai.google.dev/gemma/terms |
| Qwen3 and Qwen3.5 (LiteRT-LM) | Apache 2.0 | https://huggingface.co/litert-community |
| NVIDIA Parakeet TDT 0.6B v3, ONNX conversion by csukuangfj | CC BY 4.0 | https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 |

Attribution under CC BY 4.0: Parakeet TDT 0.6B v3 by NVIDIA, converted to ONNX by csukuangfj (k2-fsa), https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.

## Selector profiles

The WhatsApp, Telegram, and Signal profiles under `app/src/main/assets/profiles/` contain only view ids from those apps. Notes on where those ids came from (public forums and repos) are in the files themselves. WhatsApp, Telegram, and Signal are trademarks of their respective owners. ChatLens is not affiliated with those providers.
