# ThorLens Extended: Offline Any-to-Any Translation Design

## Goal

Extend ThorLens offline translation from Japanese-to-nine-targets into a source-aware, many-to-many pipeline constrained by the models publicly supported by Google ML Kit. Keep JP Dictionary mode Japanese-only and preserve the existing capture flow.

## Product contract

- Rename the distributable app to **ThorLens Extended** and use a distinct application ID so it can coexist with upstream ThorLens.
- Add a **Translate From** setting. `Auto` is the first option and the default.
- Expose every ML Kit translation target using stable BCP-47 language tags and readable English names.
- Expose source languages only when both ML Kit translation and one of its public OCR script models support the language.
- Support the five public OCR script families: Latin, Chinese, Devanagari, Japanese, and Korean.
- Keep Japanese available without a first-run regression. Add Latin as the other baseline script; install Chinese, Devanagari, and Korean OCR modules on demand.
- Selecting a source or target starts all missing downloads immediately. There is no confirmation dialog.
- Show determinate progress when Google Play Services reports OCR module byte progress. Use an indeterminate indicator for translation models, whose API exposes completion but not byte progress.
- Keep navigation and settings usable while downloads run. Translation waits for required models.
- Persist the selected language even if a download fails. Show a compact error and retry action without a modal dialog.
- Never cancel a model download merely because the user changes selection. Track readiness by model identity so concurrent or superseded downloads cannot corrupt current state.
- On restart, query model availability and reconstruct the download/readiness state.

## Language and OCR model catalog

Represent languages as data rather than constants spread through UI and translation code. Each source-capable entry contains:

- BCP-47 translation tag;
- English display name;
- OCR script family;
- whether the OCR script is a baseline or on-demand model.

Target entries come from ML Kit's supported translation tags. A source entry is offered only when its native script is handled by the Latin, Chinese, Devanagari, Japanese, or Korean recognizer. Unsupported screenshot scripts are omitted rather than presented as non-working choices.

## Source selection

An explicit source language maps directly to its OCR recognizer and ML Kit translator source tag.

`Auto` runs the currently available OCR recognizers against the captured image. Each non-empty result is passed through offline ML Kit language identification. Candidate scoring considers language-identification confidence, recognized text volume, and whether the detected language belongs to the recognizer's script family. The highest valid candidate is selected. If no candidate clears the confidence floor, translation returns a focused message asking the user to select a source language.

Installing a script through an explicit selection makes that recognizer available to later `Auto` captures. `Auto` does not silently download every OCR script pack.

## Model downloads and state

Create an application-scoped model manager exposing immutable state for each OCR script and translation language:

- `NotInstalled`
- `Queued`
- `Downloading(progress?)`
- `Installing`
- `Ready`
- `Paused`
- `Failed(message)`

OCR optional modules use `ModuleInstallClient`, including its status listener and byte progress. Translation language packs use `RemoteModelManager` and expose indeterminate progress until their completion task resolves.

Selecting an explicit source ensures its OCR script, source translation model, and selected target model. Selecting a target ensures its translation model and refreshes the active pair. In `Auto`, the detected source model is ensured after detection if it was not already present. If source and target match, return recognized text directly and do not create a translator.

No artificial timeout is used. Paused, failed, and completed states come from the model APIs. Listener registrations are removed at terminal states and resources are closed with application/activity lifecycle ownership.

## Translation pipeline

Split the current combined translator into focused collaborators:

1. `OfflineLanguageCatalog` owns supported language metadata and script mapping.
2. `OfflineModelManager` owns availability, downloads, and progress.
3. `MultiScriptTextRecognizer` owns recognizer instances and Auto candidate selection.
4. `OfflineTranslator` owns `(source, target)` translator creation, reuse, and closure.
5. Existing AI translation remains image-based and uses only the selected output language.

The capture flow becomes: capture/crop image, resolve source and OCR text, ensure the active translation pair, translate each text block, then render original and translated blocks. Offline Auto retains its existing screenshot deduplication behavior.

## UI

Settings places `Translate From` directly before `Output Language`. Both use searchable or scrollable language menus. `Auto` remains pinned first in the source menu.

The selected row and main translation view reflect model state:

- percentage bar while OCR bytes are downloading;
- looping progress indicator while translation models download or OCR installs;
- ready marker when all current requirements are installed;
- inline retry affordance after failure.

The capture button is unavailable only while the currently selected translation requirements are unresolved. Downloads for superseded selections continue in the background without blocking the new selection's UI.

## Signing and release

- Build and sign release APKs in GitHub Actions.
- Store the base64-encoded keystore and its passwords/alias only as GitHub Actions repository secrets.
- Decode the keystore into the runner's temporary directory and delete it at job completion.
- Never commit keystore bytes, passwords, generated property files, or decoded signing material.
- Publish the signed APK, SHA-256 checksum, and public signing certificate/fingerprint in releases.
- Use a distinct package ID and stable signing identity for all Extended releases.

## Verification

Unit tests cover language catalog filtering, script mapping, explicit and Auto source resolution, confidence fallback, same-language passthrough, model-state transitions, pair switching, and persisted selections.

Android tests or fakes cover model progress, pause/failure/retry, recreation during download, and selectors changing during active downloads. Fixture screenshots cover representative Latin, Japanese, Chinese, Korean, and Devanagari text.

Release verification includes clean debug/release builds, APK signature verification, package/version inspection, checksum generation, and a GitHub Actions run using repository secrets. The release is not considered complete until the signed artifact is downloadable from GitHub and its signer fingerprint matches the published certificate.
