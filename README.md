<div align="center">

# Command Code Chat

[![CI](https://github.com/DrTralala/commandcode-chat/actions/workflows/validate.yml/badge.svg)](https://github.com/DrTralala/commandcode-chat/actions/workflows/validate.yml)
[![Version](https://img.shields.io/badge/version-v1.0.0-blue)](https://github.com/DrTralala/commandcode-chat/releases/tag/v1.0.0)
[![Licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)

An Android chat client for Command Code with a bundled GOAT model catalogue and live quota tracking.

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
- Bundles a pinned, validated GOAT model catalogue in the APK.
- Shows monthly, five-hour, and weekly quota telemetry with safe stale-data fallback.
- Stores conversations and usage history in an encrypted local database.
- Enables zero data retention (ZDR) by default and can fail rather than use a non-ZDR provider.
- Keeps chat, history, budget, and security settings in one native Jetpack Compose app.

## How it works

The Android app calls Command Code directly:

- Chat: `https://api.commandcode.ai/provider/v1/chat/completions`
- Quota: `https://api.commandcode.ai/alpha/billing/credits`

The API key is sent only to the relevant Command Code endpoint for each request. Model selection uses the bundled `catalogue/goat-models.json`; the app does not fetch a remote catalogue or require an intermediary service.

The optional Ktor `:server` module is a reference implementation for catalogue and quota routes. It is not part of the Android chat, quota, or model-selection request path and never receives Android chat prompts or responses.

## Privacy and security

- The API key is encrypted at rest using an Android Keystore-backed key.
- Room history and usage data are encrypted with SQLCipher using Keystore-protected key material.
- Android backup and device-transfer rules exclude app files, databases, and preferences.
- Cleartext traffic is disabled.
- ZDR adds the Command Code ZDR request header; upstream providers and Command Code still process request content according to their own policies.
- Quota uses an alpha upstream API and may change. Invalid responses are rejected rather than trusted.

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
catalogue/  Bundled GOAT model catalogue
server/     Optional Ktor reference service
```

## Licence

Command Code Chat is available under the [MIT Licence](LICENSE).
