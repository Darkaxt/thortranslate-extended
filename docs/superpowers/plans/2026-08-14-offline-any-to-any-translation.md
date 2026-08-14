# Offline Any-to-Any Translation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a separately installable ThorLens Extended APK that translates screenshots offline between every source/target pair supported by the intersection of ML Kit OCR and Translation, with Auto source detection and automatic model downloads.

**Architecture:** Introduce a pure language catalog and model-state reducer, then isolate Google Play Services downloads, multi-script OCR, language identification, and pair-specific translation behind focused classes. Keep the existing Japanese dictionary path unchanged and pass the new offline pipeline only through Translate/Offline and Translate/Offline Auto. GitHub Actions owns the private release keystore and publishes signed release artifacts.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Jetpack Compose Material 3, Kotlin coroutines/StateFlow, ML Kit Text Recognition v2, ML Kit Language Identification, ML Kit Translation, Google Play Services ModuleInstallClient, JUnit 4, GitHub Actions.

---

## File structure

**Create:**

- `app/src/main/java/com/kanjilens/offline/OfflineLanguageCatalog.kt` — translation languages, source-script intersection, and display names.
- `app/src/main/java/com/kanjilens/offline/ModelDownloadState.kt` — download state types and pure state transition helpers.
- `app/src/main/java/com/kanjilens/offline/OfflineModelManager.kt` — OCR module availability/downloads and translation model downloads.
- `app/src/main/java/com/kanjilens/offline/MultiScriptTextRecognizer.kt` — recognizer registry, explicit OCR, Auto candidate scoring, and language identification.
- `app/src/main/java/com/kanjilens/offline/OfflineTranslator.kt` — pair-keyed ML Kit translator lifecycle and block translation.
- `app/src/test/java/com/kanjilens/offline/OfflineLanguageCatalogTest.kt` — catalog and script mapping coverage.
- `app/src/test/java/com/kanjilens/offline/ModelDownloadStateTest.kt` — progress and terminal-state reducer coverage.
- `app/src/test/java/com/kanjilens/offline/AutoSourceSelectorTest.kt` — pure Auto candidate selection coverage.
- `.github/workflows/release.yml` — signed build, verification, artifact upload, and tag release.
- `docs/signing/thortranslate-extended-release.pem` — public signing certificate only.

**Modify:**

- `app/build.gradle.kts` — Extended identity/version, ML Kit dependencies, tests, and environment-driven release signing.
- `settings.gradle.kts` — Extended project name.
- `app/src/main/res/values/strings.xml` — Extended app label and generic OCR messages.
- `app/src/main/java/com/kanjilens/KanjiLensApp.kt` — application-scoped model/OCR/translation services.
- `app/src/main/java/com/kanjilens/MainActivity.kt` — inject the new services and close activity-owned resources.
- `app/src/main/java/com/kanjilens/data/models/AppSettings.kt` — persist `source_language`, defaulting to `auto`.
- `app/src/main/java/com/kanjilens/data/models/CaptureState.kt` — carry model labels/progress and structured offline blocks.
- `app/src/main/java/com/kanjilens/translate/OpenAITranslator.kt` — delegate offline work and keep AI output-language prompts.
- `app/src/main/java/com/kanjilens/ui/screens/SettingsScreen.kt` — source/target selectors, automatic download launch, progress, failure, and retry.
- `app/src/main/java/com/kanjilens/ui/screens/MainScreen.kt` — pass source language, show current model progress, and render generic original/translated blocks.
- `app/src/main/java/com/kanjilens/ui/screens/HelpScreen.kt` — document Auto, supported scripts, and on-demand downloads.
- `README.md` — Extended identity, offline coverage, install behavior, and signed release contract.
- `.gitignore` — exclude keystores and local signing files.

### Task 1: Add the pure offline language catalog

**Files:**
- Create: `app/src/main/java/com/kanjilens/offline/OfflineLanguageCatalog.kt`
- Create: `app/src/test/java/com/kanjilens/offline/OfflineLanguageCatalogTest.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add JUnit and write the failing catalog tests**

Test these contracts:

```kotlin
assertEquals("auto", OfflineLanguageCatalog.AUTO)
assertEquals("Auto", OfflineLanguageCatalog.sourceChoices.first().displayName)
assertEquals(OcrScript.JAPANESE, OfflineLanguageCatalog.requireSource("ja").script)
assertEquals(OcrScript.LATIN, OfflineLanguageCatalog.requireSource("ro").script)
assertEquals(OcrScript.DEVANAGARI, OfflineLanguageCatalog.requireSource("hi").script)
assertTrue(OfflineLanguageCatalog.targets.any { it.tag == "ar" })
assertFalse(OfflineLanguageCatalog.sourceChoices.any { it.tag == "ar" })
assertEquals("English", OfflineLanguageCatalog.displayName("en"))
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./gradlew testDebugUnitTest --tests com.kanjilens.offline.OfflineLanguageCatalogTest`

Expected: compilation fails because the catalog types do not exist.

- [ ] **Step 3: Implement the catalog**

Define:

```kotlin
enum class OcrScript { LATIN, CHINESE, DEVANAGARI, JAPANESE, KOREAN }

data class OfflineLanguage(
    val tag: String,
    val displayName: String,
    val script: OcrScript? = null,
)

object OfflineLanguageCatalog {
    const val AUTO = "auto"
    val targets: List<OfflineLanguage>
    val sourceChoices: List<OfflineLanguage>
    fun source(tag: String): OfflineLanguage?
    fun requireSource(tag: String): OfflineLanguage
    fun target(tag: String): OfflineLanguage?
    fun displayName(tag: String): String
}
```

Populate `targets` with the documented ML Kit Translation tags. Build `sourceChoices` as Auto plus only native-script languages supported by Latin, Chinese, Devanagari, Japanese, or Korean OCR. Keep ordering deterministic and pin Auto first.

- [ ] **Step 4: Run the catalog tests and full unit-test task**

Run: `./gradlew testDebugUnitTest --tests com.kanjilens.offline.OfflineLanguageCatalogTest` then `./gradlew testDebugUnitTest`.

Expected: all tests pass.

- [ ] **Step 5: Commit**

Commit message: `feat: add offline language catalog`.

### Task 2: Persist source selection and rebrand the fork

**Files:**
- Modify: `app/src/main/java/com/kanjilens/data/models/AppSettings.kt`
- Modify: `app/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `.gitignore`

- [ ] **Step 1: Add the source preference**

Add `KEY_SOURCE_LANGUAGE`, a `StateFlow<String>` initialized to `OfflineLanguageCatalog.AUTO`, and:

```kotlin
fun setSourceLanguage(tag: String) {
    val normalized = if (tag == OfflineLanguageCatalog.AUTO || OfflineLanguageCatalog.source(tag) != null) {
        tag
    } else {
        OfflineLanguageCatalog.AUTO
    }
    _sourceLanguage.value = normalized
    prefs.edit().putString(KEY_SOURCE_LANGUAGE, normalized).apply()
}
```

Replace the nine-language constants/list with catalog lookups while retaining `LANG_ENGLISH` as a compatibility constant for AI defaults.

- [ ] **Step 2: Give Extended a separate install identity**

Set `applicationId = "com.kanjilens.extended"`, `versionCode = 3`, `versionName = "0.3.0-extended.1"`, root project name `ThorLensExtended`, and app label `ThorLens Extended`. Keep the Kotlin namespace/package unchanged.

- [ ] **Step 3: Exclude private signing material**

Add `*.jks`, `*.keystore`, `keystore.properties`, and `signing.properties` to `.gitignore`.

- [ ] **Step 4: Build and inspect the manifest**

Run: `./gradlew processDebugMainManifest assembleDebug`.

Expected: success; merged manifest application ID is `com.kanjilens.extended` and app label resolves to `ThorLens Extended`.

- [ ] **Step 5: Commit**

Commit message: `feat: establish ThorLens Extended identity`.

### Task 3: Add testable model download state

**Files:**
- Create: `app/src/main/java/com/kanjilens/offline/ModelDownloadState.kt`
- Create: `app/src/test/java/com/kanjilens/offline/ModelDownloadStateTest.kt`

- [ ] **Step 1: Write failing state tests**

Cover clamped progress, indeterminate translation progress, paused state preservation, completion, and retry:

```kotlin
assertEquals(0.5f, ModelDownloadState.Downloading(50, 100).fraction)
assertNull(ModelDownloadState.Downloading(null, null).fraction)
assertEquals(ModelDownloadState.Ready, reduceModelState(ModelDownloadEvent.Completed))
assertEquals(ModelDownloadState.Queued, reduceModelState(ModelDownloadEvent.Retry))
```

- [ ] **Step 2: Verify the focused test fails**

Run: `./gradlew testDebugUnitTest --tests com.kanjilens.offline.ModelDownloadStateTest`.

Expected: compilation fails because the state types do not exist.

- [ ] **Step 3: Implement the immutable state model and reducer**

Define `NotInstalled`, `Queued`, `Downloading(bytesDownloaded?, totalBytes?)`, `Installing`, `Ready`, `Paused`, and `Failed(message)`. Clamp fractions to `[0f, 1f]` and leave them null when totals are unavailable.

- [ ] **Step 4: Run tests and commit**

Run: `./gradlew testDebugUnitTest`.

Expected: all tests pass.

Commit message: `feat: model offline download states`.

### Task 4: Implement multi-script OCR and Auto source selection

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/com/kanjilens/offline/MultiScriptTextRecognizer.kt`
- Create: `app/src/test/java/com/kanjilens/offline/AutoSourceSelectorTest.kt`

- [ ] **Step 1: Write failing pure candidate-selection tests**

Define a pure `AutoSourceSelector.select(candidates)` contract and test that it prefers a supported, script-compatible high-confidence candidate; rejects unsupported/low-confidence candidates; and returns null for empty OCR.

```kotlin
val selected = AutoSourceSelector.select(
    listOf(
        OcrCandidate(OcrScript.LATIN, listOf("Start Game"), "en", 0.91f),
        OcrCandidate(OcrScript.JAPANESE, listOf("Start Garne"), "en", 0.44f),
    )
)
assertEquals("en", selected?.languageTag)
assertEquals(OcrScript.LATIN, selected?.script)
```

- [ ] **Step 2: Verify failure and implement selection types**

Run the focused test, confirm missing types, then implement `OcrCandidate`, `RecognizedScreenText`, and `AutoSourceSelector` with a minimum identification confidence and deterministic score based on confidence plus non-whitespace text volume.

- [ ] **Step 3: Add ML Kit dependencies**

Keep bundled Japanese OCR, add bundled Latin OCR, add Play Services Chinese/Devanagari/Korean OCR clients, and add bundled language identification. Do not bundle the three optional OCR models.

- [ ] **Step 4: Implement recognizer registry and Auto OCR**

`MultiScriptTextRecognizer` must expose:

```kotlin
suspend fun recognize(bitmap: Bitmap, sourceTag: String): RecognizedScreenText
suspend fun recognizeAuto(bitmap: Bitmap, availableScripts: Set<OcrScript>): RecognizedScreenText
fun optionalApi(script: OcrScript): OptionalModuleApi?
fun close()
```

Explicit mode runs exactly one mapped recognizer. Auto runs available recognizers, identifies each non-empty result, feeds candidates to `AutoSourceSelector`, and reports a focused source-selection error when no candidate qualifies.

- [ ] **Step 5: Run unit tests and a debug build**

Run: `./gradlew testDebugUnitTest assembleDebug`.

Expected: all tests pass and the APK compiles with all recognizer client variants.

- [ ] **Step 6: Commit**

Commit message: `feat: add multi-script offline OCR`.

### Task 5: Implement automatic background model management

**Files:**
- Create: `app/src/main/java/com/kanjilens/offline/OfflineModelManager.kt`
- Modify: `app/src/main/java/com/kanjilens/KanjiLensApp.kt`

- [ ] **Step 1: Implement application-scoped service ownership**

Initialize an application `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, `MultiScriptTextRecognizer`, and `OfflineModelManager` in `KanjiLensApp`. Close recognizers and cancel only when the process terminates; navigation must not interrupt downloads.

- [ ] **Step 2: Implement OCR availability and download progress**

Use `ModuleInstallClient.areModulesAvailable(optionalApi)` during refresh. For manual source selection, call `installModules()` immediately with an `InstallStatusListener`. Map `STATE_PENDING`, `STATE_DOWNLOADING`, `STATE_INSTALLING`, `STATE_DOWNLOAD_PAUSED`, `STATE_COMPLETED`, and `STATE_FAILED` into `ModelDownloadState`. Feed reported byte counts into determinate progress and unregister listeners at terminal states.

- [ ] **Step 3: Implement translation model management**

Use `RemoteModelManager.getDownloadedModels(TranslateRemoteModel::class.java)` to reconstruct readiness. `ensureTranslationLanguage(tag)` immediately launches `download(model, DownloadConditions.Builder().build())`, exposes indeterminate `Downloading`, and transitions to `Ready` or `Failed`.

- [ ] **Step 4: Implement selection-level requirements**

Expose:

```kotlin
fun selectSource(tag: String)
fun selectTarget(tag: String)
fun retryCurrentSelection()
val selectionState: StateFlow<SelectionModelState>
val availableOcrScripts: StateFlow<Set<OcrScript>>
```

Explicit source selection downloads its OCR script and source translation model. Target selection downloads its translation model. Auto downloads nothing by itself and uses installed scripts. Changing selections never cancels earlier work.

- [ ] **Step 5: Build, inspect state wiring, and commit**

Run: `./gradlew testDebugUnitTest assembleDebug`.

Expected: success with no leaked listener/compiler warnings.

Commit message: `feat: download offline models on selection`.

### Task 6: Generalize offline translation and integrate the UI

**Files:**
- Create: `app/src/main/java/com/kanjilens/offline/OfflineTranslator.kt`
- Modify: `app/src/main/java/com/kanjilens/translate/OpenAITranslator.kt`
- Modify: `app/src/main/java/com/kanjilens/MainActivity.kt`
- Modify: `app/src/main/java/com/kanjilens/data/models/CaptureState.kt`
- Modify: `app/src/main/java/com/kanjilens/ui/screens/SettingsScreen.kt`
- Modify: `app/src/main/java/com/kanjilens/ui/screens/MainScreen.kt`
- Modify: `app/src/main/java/com/kanjilens/ui/screens/HelpScreen.kt`

- [ ] **Step 1: Implement pair-keyed offline translation**

`OfflineTranslator.translate(bitmap, sourceTag, targetTag)` resolves OCR explicitly or through Auto, returns recognized blocks unchanged when source equals target, otherwise obtains a translator keyed by `sourceTag to targetTag`, calls `downloadModelIfNeeded`, translates each block, and closes a replaced translator.

- [ ] **Step 2: Delegate the existing offline branches**

Pass `sourceLanguage` into `ScreenTranslator.translateScreen`. Offline and Offline Auto delegate to `OfflineTranslator`; Gemini/OpenAI remain image-based and use the existing target-language prompt. Replace encoded alternating lines with structured `OfflineTranslationBlock(original, translated)` data.

- [ ] **Step 3: Add source and expanded target selectors**

Settings displays `Translate From` before `Output Language`, pins Auto first, and uses catalog entries. Calling either setter must immediately call the corresponding model-manager selection method. Show a percentage `LinearProgressIndicator(progress = { fraction })` when OCR byte progress exists and an indeterminate indicator otherwise. Show inline failure text and Retry.

- [ ] **Step 4: Update the main screen state contract**

Collect selection download state. Disable only the translation capture action while current requirements are unresolved. Keep Settings/Help/navigation usable. Render source and target names while processing and render every offline block as original text plus bold translated text without Japanese/English-specific comments or labels.

- [ ] **Step 5: Preserve Offline Auto behavior**

Keep the existing one-second loop and screenshot deduplication. A model download suspends translation work without cancelling the loop or introducing a timeout; subsequent captures resume once requirements become ready.

- [ ] **Step 6: Update Help and README**

Document Auto, the five OCR scripts, dynamic model downloads, unsupported screenshot scripts, separate install identity, and GitHub-signed release assets.

- [ ] **Step 7: Run verification and commit**

Run: `./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease`.

Expected: unit tests pass; lint and both variants build; local release may be unsigned when signing environment variables are absent.

Commit message: `feat: support offline source auto-detection`.

### Task 7: Add GitHub-only signing and release automation

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `.github/workflows/release.yml`
- Create: `docs/signing/thortranslate-extended-release.pem`
- Modify: `README.md`

- [ ] **Step 1: Add environment-driven signing configuration**

Read `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD`. Configure the release signing config only when all four are present so local unsigned validation remains possible. Never read a committed properties file.

- [ ] **Step 2: Generate one persistent Extended signing identity**

Generate the keystore under `D:\Temp`, using cryptographically random store/key passwords and alias `thortranslate_extended`. Export only the RFC-7468 public certificate into `docs/signing/thortranslate-extended-release.pem` and record its SHA-256 signer fingerprint in README.

- [ ] **Step 3: Upload signing values as repository secrets**

Create `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` in `Darkaxt/thortranslate-extended`. Do not print their values. Delete the temporary keystore after confirming the secret names exist.

- [ ] **Step 4: Add the release workflow**

On `workflow_dispatch` and `v*` tags: check out, set up JDK 17 and Gradle, decode the keystore to `$RUNNER_TEMP`, run unit tests/lint/`assembleRelease`, verify the APK with `apksigner`, copy it to `ThorLens-Extended.apk`, generate SHA-256 and signer reports, upload the three artifacts, and create a GitHub prerelease on tags. Use `always()` cleanup for decoded keystore material.

- [ ] **Step 5: Validate workflow syntax and local signed build**

Run a local signed `assembleRelease` with environment variables, then run `apksigner verify --verbose --print-certs` and `apkanalyzer manifest application-id/version-name`. Confirm package `com.kanjilens.extended`, version `0.3.0-extended.1`, and the certificate fingerprint exported in the repository.

- [ ] **Step 6: Commit**

Commit message: `ci: sign and publish Extended releases`.

### Task 8: Publish and verify the first Extended release

**Files:**
- Modify only if verification finds a defect.

- [ ] **Step 1: Run the complete local verification suite**

Run: `./gradlew clean testDebugUnitTest lintDebug assembleDebug assembleRelease`, `git diff --check`, and `git status --short`.

Expected: all Gradle tasks pass, no whitespace errors, and only intentional commits are present.

- [ ] **Step 2: Push main and run signing CI**

Push the complete main branch to `origin`, trigger `release.yml` with `workflow_dispatch`, and inspect the run through `gh run watch`/`gh run view --log-failed` until it completes.

- [ ] **Step 3: Create and push the release tag**

Create annotated tag `v0.3.0-extended.1` only after the workflow-dispatch build passes, push it, and verify the tag-triggered release workflow succeeds.

- [ ] **Step 4: Verify the published user-facing contract**

Download `ThorLens-Extended.apk` from the GitHub release into `D:\Temp`, verify its SHA-256, package/version, and signer fingerprint independently, then remove the temporary download. If an AYN Thor is connected and authorized, install the Extended package and verify cold launch plus source/target selector visibility without replacing upstream ThorLens.

- [ ] **Step 5: Record release evidence**

Report the repository URL, release URL, commit/tag, Actions run, artifact checksum, signer fingerprint, local-vs-public deployment state, and any device flow not verified.
