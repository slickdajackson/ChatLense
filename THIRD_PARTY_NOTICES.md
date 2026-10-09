# Hinweise zu Drittanbieter-Komponenten

Der eigene Quellcode von ChatLens steht unter der MIT-Lizenz (siehe [LICENSE](LICENSE)). Die folgenden Komponenten stammen von Dritten und behalten ihre eigenen Lizenzen. Sie werden beim Bauen über Maven geladen oder, im Fall der Modelle, erst zur Laufzeit vom Nutzer heruntergeladen. Keine Modelldatei ist in diesem Repo enthalten.

## Bibliotheken (im APK enthalten)

| Komponente | Version | Lizenz | Quelle |
|---|---|---|---|
| Kotlin Standard Library | 2.4.10 | Apache 2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.10.2 | Apache 2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX (Core, Activity, Lifecycle, Jetpack Compose, Material 3, RecyclerView, ViewPager2) | siehe build.gradle.kts | Apache 2.0 | https://developer.android.com/jetpack/androidx |
| LiteRT-LM für Android (Google AI Edge) | 0.17.1 | Apache 2.0 | https://github.com/google-ai-edge/LiteRT-LM |
| sherpa-onnx (k2-fsa) | 1.13.8 | Apache 2.0 | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (in sherpa-onnx enthalten) | | MIT | https://github.com/microsoft/onnxruntime |
| Concentus (Opus-Decoder in Java) | 1.0.2 | BSD 3-Clause | https://github.com/lostromb/concentus |
| Google ML Kit Text Recognition | 16.0.1 | ML Kit Terms of Service (kein Open Source, frei weitergebbare Binärbibliothek) | https://developers.google.com/ml-kit/terms |

## Werkzeuge

| Komponente | Lizenz | Quelle |
|---|---|---|
| Gradle Wrapper (gradlew, gradle-wrapper.jar) | Apache 2.0 | https://github.com/gradle/gradle |
| Android Gradle Plugin | Apache 2.0 | https://developer.android.com/build |
| JUnit 4 (nur Tests) | EPL 1.0 | https://github.com/junit-team/junit4 |
| Robolectric (nur Tests) | MIT | https://github.com/robolectric/robolectric |
| org.json (nur Tests) | Public Domain | https://github.com/stleary/JSON-java |

## Modelle (nicht enthalten, optionaler Download in der App)

| Modell | Lizenz | Quelle |
|---|---|---|
| Gemma 4 E2B und E4B (LiteRT-LM) | Gemma Terms of Use | https://ai.google.dev/gemma/terms |
| Gemma 3 1B IT (nur per Import) | Gemma Terms of Use | https://ai.google.dev/gemma/terms |
| Qwen3 und Qwen3.5 (LiteRT-LM) | Apache 2.0 | https://huggingface.co/litert-community |
| NVIDIA Parakeet TDT 0.6B v3, ONNX-Umwandlung von csukuangfj | CC BY 4.0 | https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 |

Namensnennung nach CC BY 4.0: Parakeet TDT 0.6B v3 von NVIDIA, umgewandelt nach ONNX von csukuangfj (k2-fsa), https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.

## Selektor-Profile

Die WhatsApp-, Telegram- und Signal-Profile unter `app/src/main/assets/profiles/` enthalten nur Ansichts-IDs der jeweiligen Apps. Hinweise zu deren Herkunft (öffentliche Foren und Repos) stehen in den Dateien selbst. WhatsApp, Telegram und Signal sind Marken ihrer jeweiligen Inhaber. ChatLens steht in keiner Verbindung zu diesen Anbietern.
