# ThorLens Extended

An extended fork of [magiobus/thortranslate](https://github.com/magiobus/thortranslate) for translating and understanding foreign-language game screens in real time. It uses a separate Android package, `com.kanjilens.extended`, so it can be installed alongside upstream ThorLens.

## What it does

**Translate mode** (primary) — Captures a screenshot of the top screen and translates it. AI models accept any language; offline screenshot translation covers ML Kit's five OCR script families. Three translation styles are available with AI models:
- **Auto** (default): Translates and explains what to do next
- **Translate**: Just translates the text, no extra explanation
- **Explain**: Full translation with detailed guidance on how to progress

**JP Dictionary mode** — Offline Japanese word-by-word breakdown. Captures text via OCR, tokenizes it, and looks up each word in a 212K-entry dictionary (JMDict). Shows kanji, reading, meaning, and JLPT level. No internet required.

**Custom capture region** — By default the entire screen is captured. You can select a specific area (e.g. the dialogue box) by tapping "Full" in the top bar, then dragging on the screenshot to draw the region. Useful for reducing noise and improving translation accuracy.

## Supported models

| Model | Provider | Cost | Notes |
|-------|----------|------|-------|
| **Offline (ML Kit)** | Google | Free | Multi-script on-device OCR, Auto source detection, and translation. No internet after selected models download. Default model. |
| **Offline Auto** | Google | Free | Same as Offline, but captures automatically every 1s. Only re-translates when text changes. |
| **Gemini 2.5 Flash** | Google | Free tier available | AI vision model, requires API key |
| **GPT-4o mini** | OpenAI | Pay per use | AI vision model, requires API key |

Switch models from the top bar dropdown or in Settings. Each AI model stores its own API key separately.

## Offline languages

`Translate From` defaults to **Auto**. Offline screenshot sources cover the intersection of ML Kit Translation with its five public OCR script families:

- Latin
- Chinese
- Devanagari
- Japanese
- Korean

Latin and Japanese recognition are available as baseline models. Selecting a Chinese, Devanagari, or Korean source starts its Google Play Services OCR-module download immediately. Once installed, that script also participates in Auto detection.

`Output Language` exposes every language supported by ML Kit Translation. Missing translation packs download automatically in the background and are roughly 30MB per language. A progress bar appears when byte progress is available; otherwise the UI shows a looping indicator. ML Kit translation may support an output language whose script its public OCR API cannot read from screenshots.

## Tech stack

- **Kotlin + Jetpack Compose** — UI and app logic
- **MediaProjection API** — Screen capture (with ForegroundService for Android 14+)
- **ML Kit Translate** — On-device offline translation (~30MB per language)
- **OpenAI GPT-4o-mini / Google Gemini 2.5 Flash** — Vision APIs for AI Translate mode
- **ML Kit Text Recognition v2** — Latin/Japanese baseline OCR plus on-demand Chinese, Devanagari, and Korean OCR
- **ML Kit Language Identification** — Offline Auto source detection
- **Kuromoji** — Japanese morphological analyzer/tokenizer
- **JMDict** — 212,478 entry offline Japanese-English dictionary
- **OkHttp** — HTTP client for API calls

## Setup

### Requirements
- Android SDK (compileSdk 35, minSdk 26)
- An API key only when using Gemini or OpenAI (offline translation needs no key)

### Build
```bash
# Clone
git clone https://github.com/Darkaxt/thortranslate-extended.git
cd thortranslate-extended

# Set your SDK path
echo "sdk.dir=$HOME/Android/sdk" > local.properties

# Build and install
./gradlew installDebug
```

### Configuration
1. Open ThorLens Extended on your device
2. The default model is **Offline (ML Kit)** — works immediately, no API key needed
3. To use AI models: tap **...** (top right) → Settings → choose Gemini Flash or GPT-4o mini → paste your API key
4. Choose Offline Translate From (default: Auto); selecting a missing OCR script downloads it immediately
5. Choose your output language (default: English)
6. Wait for the non-blocking model progress indicator to report ready
7. Choose your preferred translation style for AI models (Auto/Translate/Explain)
8. Adjust text size if needed (S/M/L)

## Usage

1. Run your game on the top screen
2. Open ThorLens Extended on the bottom screen
3. Switch between **Translate** and **JP Dictionary** (offline, Japanese only)
4. Switch models from the top bar dropdown (Offline → Offline Auto → Gemini → GPT-4o)
5. Press the button to capture and translate the top screen (or select Offline Auto for continuous translation)
6. Optionally tap **Full** in the top bar to select a custom capture region
7. Results persist when switching between modes or going to Settings

## Project structure

```
app/src/main/java/com/kanjilens/
├── MainActivity.kt              # Entry point, navigation, state management
├── capture/
│   ├── ScreenCaptureManager.kt  # MediaProjection + VirtualDisplay
│   └── ScreenCaptureService.kt  # ForegroundService for Android 14+
├── ocr/
│   └── TextRecognizer.kt        # Japanese-only dictionary OCR wrapper
├── offline/
│   ├── OfflineLanguageCatalog.kt
│   ├── OfflineModelManager.kt
│   ├── MultiScriptTextRecognizer.kt
│   └── OfflineTranslator.kt
├── analysis/
│   ├── JapaneseTokenizer.kt     # Kuromoji tokenizer wrapper
│   └── DictionaryLookup.kt      # JMDict dictionary lookup
├── translate/
│   └── ScreenTranslator.kt      # Multi-model translator (ML Kit offline + OpenAI + Gemini)
├── data/models/
│   ├── AppSettings.kt           # SharedPreferences with StateFlow
│   └── CaptureState.kt          # State models (WordEntry, AnalysisResult, etc)
└── ui/
    ├── screens/
    │   ├── MainScreen.kt        # Main UI with mode toggle and model selector
    │   ├── SettingsScreen.kt    # Settings (model, language, style, keys, text size)
    │   ├── CropScreen.kt        # Visual capture region selector
    │   └── HelpScreen.kt        # Usage guide and API key instructions
    ├── components/
    │   ├── CaptureButton.kt     # Capture button with loading state
    │   ├── TranslationResult.kt # Dictionary word breakdown view
    │   └── WordCard.kt          # Individual word card (kanji, reading, meaning)
    └── theme/
        └── Theme.kt             # Dark theme (navy + pink)
```

## License

MIT

## Signed releases

Release APKs are built and signed in GitHub Actions. The private keystore and credentials are stored only as encrypted repository secrets; the public certificate and SHA-256 signing fingerprint are published for independent verification.

See [release signing and verification](docs/signing/README.md). The official certificate SHA-256 fingerprint is `3D:89:52:F1:07:47:45:26:64:0D:75:D6:A2:BB:27:9B:1C:F5:4C:19:00:27:62:6C:A0:55:6C:9C:B0:E0:63:32`.
