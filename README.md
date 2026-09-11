<div align="center">

# Command Code Chat

[![CI](https://github.com/DrTralala/commandcode-chat/actions/workflows/validate.yml/badge.svg)](https://github.com/DrTralala/commandcode-chat/actions/workflows/validate.yml)
[![Version](https://img.shields.io/badge/version-v3.0.0-blue)](https://github.com/DrTralala/commandcode-chat/releases/tag/v3.0.0)
[![Licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)

An Android chat client for Command Code with a bundled GOAT model catalogue and plan-aware quota tracking.

**[Download the latest APK](https://github.com/DrTralala/commandcode-chat/releases/latest/download/CommandCodeChat.apk)**

</div>

## Requirements

- Android 11 or newer (API 30+)
- A Command Code API key
- Internet access for chat and quota requests

## Quick start

1. Download `CommandCodeChat.apk` from the link above.
2. Allow installation from your browser or file manager when Android prompts.
3. Open the app, go to **Settings**, and save your Command Code API key.
4. Select a model and start a chat.

The downloadable APK is **debug-signed**, not a production-signed Play Store build. Its signing key may differ between releases, so Android may require the previous build to be uninstalled before an update; uninstalling removes local app data.

## Features

- Streams chat responses directly from Command Code.
- Shows an animated Thinking indicator while waiting for response text, with automatic scrolling that pauses when you scroll back.
- Bundles a pinned, validated GOAT model catalogue in the APK.
- Shows plan-aware credit usage, including rolling windows when the account provides them, with safe stale-data fallback.
- Stores conversations and usage history in an encrypted local database.
- Shows estimated context-window usage as a right-aligned percentage before the composer's attachment and send controls.
- Enables zero data retention (ZDR) by default and can fail rather than use a non-ZDR provider.
- Marks ZDR-routing failures with a red exclamation; long-press or tap it to Retry with current model/ZDR settings or Delete the failed message.
- Persists local model-change notices without sending them to the provider.
- Requests the highest supported refresh rate at the current resolution; Android and device policies still control the delivered rate.
- Uses AMOLED dark mode exclusively, with true-black backgrounds and surfaces, including startup and system bars. Existing theme preferences are ignored automatically.
- Uses distinct, aligned message bubbles for user and assistant messages.
- Long-presses past user messages to edit, copy, or select text, and assistant messages to copy, retry, or select text.
- Keeps edits and retried responses as persistent alternatives within the same chat, with nested branch navigation using `<` and `>` controls.
- Regenerates historical responses from their selected conversation point while applying the current model, system prompt, and ZDR setting; settings-change notices remain local to the new branch.
- Supports native in-bubble substring selection and copying without opening a separate text window.
- Keeps chat, history, budget, and security settings in one native Jetpack Compose app.

### Message editing and response branches

- Editing a past user message creates a new user-message alternative and generates a fresh response. The original message and its continuation remain available.
- Retrying an assistant message regenerates only that historical response point. It does not copy the old continuation into the new branch.
- Branch selectors show the selected alternative and total count, such as `2/4`; selections and nested continuations persist when the chat is reopened.
- Editing is inline and retains the original message's image attachments. Navigating away prompts before discarding the memory-only edit draft.
- Conversation usage totals include every attempt, while the context percentage follows the currently selected branch.

### Context-window usage

- The non-interactive context percentage rounds to the nearest whole number (0.5% rounds up to 1%). There is no popup, prefix, approximation marker, raw token count or token fraction. The displayed value is blue at 0–49%, orange at 50–79%, and red at 80% or above.
- The estimate uses the latest completed response's provider-reported input plus output, divided by that response model's context-window capacity. It does not sum historical prompts or add cached input again.
- Switching models keeps the displayed percentage unchanged until a response supplies new usage; that response then uses its own model's capacity.
- Drafts, unsent attachments and subsequent system-prompt changes are excluded. During streaming, cancellation or missing usage, the last measurement remains. A retry of an earlier message can measure only that earlier part of the conversation.
- New chats and measured models without verified limits show a neutral `—`, not 0%. Values above 100% are not clamped, and the indicator does not block sending or prune history.
- Exact-model capacities are pinned in `catalogue/model-context-windows.json`, verified on 10 September 2026 against the public [Provider model list](https://api.commandcode.ai/provider/v1/models), documented in the [Provider API reference](https://commandcode.ai/docs/provider). Unlisted free variants remain unknown rather than inheriting paid-model limits. This metadata is bundled, not fetched at runtime.
- Cumulative input, output and cached-input accounting remains internal and separate from context. Received usage is preserved for interrupted or failed requests without treating them as completed context measurements. Missing reports remain unknown, so these aggregates are not billing-grade totals.

### Image attachments

- JPEG, PNG and static WebP, up to 10 MB each and 20 images per chat (decimal MB).
- Initially enabled for GPT-5.6 Sol, GPT-5.6 Luna and Gemini 3.7 Flash based on documentation; other models remain disabled until verified.
- App-owned image copies are encrypted on this device, like chat history. The source gallery file is unchanged. Providers process sent images; this is not end-to-end encryption against the provider.
- Compact stacked previews stay above the keyboard-aware composer. Validated image content takes precedence over misleading filenames or MIME labels.
- History shows estimated message/attachment storage, can sort largest/smallest first, and supports selecting multiple chats for deletion. Deleting a chat removes its encrypted attachment copies.
- Command Code's request limits are undocumented; large requests may be rejected. PDF/document/audio/video inputs are not supported by this version.

The documented capabilities above are not runtime-validated here; the app limits are not a gateway guarantee.

## How it works

The Android app calls Command Code directly:

- Chat: `https://api.commandcode.ai/provider/v1/chat/completions`
- Credits: `https://api.commandcode.ai/alpha/billing/credits`
- Optional plan metadata: `https://api.commandcode.ai/alpha/billing/subscriptions`

The app reads credits and optional subscription metadata directly from Command Code. Accounts without rolling quota windows remain supported. The API key is sent only to the relevant Command Code endpoint for each request. Model selection uses the bundled `catalogue/goat-models.json`; the app does not fetch a remote catalogue or require an intermediary service.

The optional Ktor `:server` module is a reference implementation for catalogue and quota routes. It is not part of the Android chat, quota, or model-selection request path and never receives Android chat prompts or responses.

## Privacy and security

- The API key is encrypted at rest using an Android Keystore-backed key.
- Mutable app-owned API-key copies used for quota requests are wiped after each fetch. OkHttp requires an immutable bearer-header `String`, so the quota client creates one such value per fetch, shares it across both requests, and leaves it subject to garbage collection rather than explicit wiping.
- Room history and usage data are encrypted with SQLCipher using Keystore-protected key material.
- Android backup and device-transfer rules exclude app files, databases, and preferences.
- Cleartext traffic is disabled.
- ZDR adds the Command Code ZDR request header; upstream providers and Command Code still process request content according to their own policies.
- Credits and subscription metadata use alpha upstream APIs and may change. Invalid credit responses are rejected rather than trusted; unavailable plan metadata falls back to generic usage wording.

The downloadable APK is a convenience debug build. Debug signing does not provide the release-key identity guarantees of a production-signed distribution. Review the source and build it yourself when that assurance matters.

## Build from source

Install JDK 17 and an Android SDK containing API 37, then create `local.properties` with your SDK path if Gradle cannot discover it automatically.

```bash
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Run the optional reference server with:

```bash
./gradlew :server:run
```

It reads `PORT` (default `8080`). A public deployment requires TLS termination and edge rate limiting.

## Verification

Run the same focused checks used by CI:

```bash
python3 -m unittest discover -s .github/tests -v
./gradlew :app:testDebugUnitTest :server:test :app:lintDebug :app:assembleDebug :app:verifyComposeDependencyFamily :app:verifyNoServiceUrlConfiguration
```

## Project structure

```text
app/        Android application, local storage, API clients, and UI
catalogue/  Bundled GOAT model catalogue and context-window capacities
server/     Optional Ktor reference service
```

## Licence

Command Code Chat is available under the [MIT Licence](LICENSE).
