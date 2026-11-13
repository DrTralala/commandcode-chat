<div align="center">

# Command Code Chat

[![CI](https://github.com/DrTralala/commandcode-chat/actions/workflows/validate.yml/badge.svg)](https://github.com/DrTralala/commandcode-chat/actions/workflows/validate.yml)
[![Version](https://img.shields.io/github/v/release/DrTralala/commandcode-chat?label=version&color=blue&sort=date)](https://github.com/DrTralala/commandcode-chat/releases/latest)
[![Licence](https://img.shields.io/badge/licence-MIT-green.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-11%2B-3DDC84?logo=android&logoColor=white)

An Android chat client for Command Code with a live public model catalogue, offline catalogue fallbacks, and plan-aware usage tracking.

**[Download the latest APK](https://github.com/DrTralala/commandcode-chat/releases/latest/download/CommandCodeChat.apk)**

</div>

## Prerequisites

- Android 11 or newer (API 30+)
- A Command Code API key
- Internet access for chat and quota requests

## Quick start

The downloadable APK is a **debug-signed** convenience build, not a production-signed Play Store build, and it does not provide production release-key identity guarantees. Its signing key may differ between releases, so Android may require the previous build to be uninstalled before an update; uninstalling removes local app data. Review the source and build it yourself when that assurance matters.

1. Download `CommandCodeChat.apk` from the link above.
2. Install it, allowing installation from your browser or file manager if Android prompts.
3. Open the app. If no API key is saved, it opens **Settings**.
4. Enter your Command Code API key and tap **Encrypt and save key**.
5. In **Chat**, choose a model, enter a message, and send it.

A streamed assistant reply confirms that the chat request succeeded.

## Features

- **Streaming chat:** Responses stream directly from Command Code, with an animated Thinking indicator and automatic scrolling that pauses when you scroll back.
- **Persistent conversation branches:** Edits and retries keep alternatives and nested continuations in the same chat instead of replacing earlier work. See [message editing and response branches](#message-editing-and-response-branches).
- **Model and usage information:** The app refreshes a public model catalogue while retaining cached or bundled fallbacks. The Budget view shows monthly use against a plan-based allowance, while the Chat composer shows estimated context-window usage. See [live models and monthly usage](#live-model-catalogue-and-monthly-usage) and [context-window usage](#context-window-usage).
- **Image attachments:** JPEG, PNG and static WebP are available only for the app's fixed model allow-list; discovering another model does not enable image support. App-owned copies are encrypted on this device. See [image attachments](#image-attachments).
- **Privacy controls:** The API key, chat history, and usage data are encrypted locally. ZDR is enabled by default; the app can fail rather than route to a non-ZDR provider, and ZDR-routing failures can be retried or deleted.
- **Native Android interface:** Chat, history, budget, and settings use Jetpack Compose and an AMOLED dark theme with true-black surfaces; existing theme preferences are ignored. Messages use distinct, aligned bubbles: long-press user messages to edit, copy, or select text, and assistant messages to copy, retry, or select text. Selection and copying work inside bubbles. Model-change notices stay local, and the app requests the highest refresh rate supported at the current resolution (Android and device policies control the delivered rate).

### Message editing and response branches

- Editing a past user message creates a new user-message alternative and generates a fresh response. The original message and its continuation remain available.
- Retrying an assistant message regenerates only that historical response point. It does not copy the old continuation into the new branch.
- Regenerating a historical response applies the current model, system prompt, and ZDR setting; model-change notices remain local to the new branch.
- Branch selectors show the selected alternative and total count, such as `2/4`; selections and nested continuations persist when the chat is reopened.
- Editing is inline and retains the original message's image attachments. Navigating away prompts before discarding the memory-only edit draft.
- Conversation usage totals include every attempt, while the context percentage follows the currently selected branch.

### Live model catalogue and monthly usage

- At startup, the app loads the last validated catalogue saved on the device, or the bundled GOAT catalogue if no usable cache exists, then refreshes from Command Code's public model endpoint. A valid refresh replaces the available model list; a failed or invalid refresh leaves the cached or bundled list in use. The model selector shows whether its list is live, cached, or bundled and includes **Refresh models** for a manual refresh.
- The catalogue includes models advertising either Chat Completions (`/chat/completions`) or the Anthropic Messages protocol (`/messages`), including Claude models when listed. For a model advertising both, the app uses Chat Completions. A public listing is not an account-entitlement check and does not guarantee that a model can be used by your account or that the gateway will route it successfully.
- If a refresh removes the selected model, the app keeps that model's historical identity rather than silently switching. Sending, retrying, or submitting an edit is blocked until you select a model that remains available.
- The Budget view shows monthly credits used against an allowance maintained by the app for recognised plans. Used credits are derived from the existing remaining-balance value as `max(0, plan allowance - remaining balance)`; the API does not report this plan-based allowance as a monthly cap. If the plan is unknown, monthly usage is unavailable. Separate remaining, purchased, and free-credit balances are not displayed.
- Five-hour and weekly usage windows and their reset times remain available when returned by the quota API. The Budget view can be refreshed manually.
- When subscription metadata identifies an active, non-cancelling subscription with a future period end, the app may show that date as **Renews**. It is optional subscription metadata, not confirmation that quota resets on that date.

### Context-window usage

- The non-interactive context percentage rounds to the nearest whole number (0.5% rounds up to 1%). There is no popup, prefix, approximation marker, raw token count or token fraction. The displayed value is blue at 0–49%, orange at 50–79%, and red at 80% or above.
- The estimate uses the latest completed response's provider-reported input plus output, divided by that response model's context-window capacity. It does not sum historical prompts or add cached input again.
- Switching models keeps the displayed percentage unchanged until a response supplies new usage; that response then uses its own model's capacity.
- Drafts, unsent attachments and subsequent system-prompt changes are excluded. During streaming, cancellation or missing usage, the last measurement remains. A retry of an earlier message can measure only that earlier part of the conversation.
- New chats and measured models without verified limits show a neutral `—`, not 0%. Values above 100% are not clamped, and the indicator does not block sending or prune history.
- Context capacity comes from the live model catalogue when the API reports it; an exact-ID value in `catalogue/model-context-windows.json` is used as a fallback. Unknown capacities remain unknown rather than inheriting another model's limit. The bundled fallback values were checked against the public [Provider model list](https://api.commandcode.ai/provider/v1/models), documented in the [Provider API reference](https://commandcode.ai/docs/provider); a public listing or context limit does not prove account access.
- Cumulative input, output and cached-input accounting remains internal and separate from context. Received usage is preserved for interrupted or failed requests without treating them as completed context measurements. Missing reports remain unknown, so these aggregates are not billing-grade totals.

### Image attachments

- JPEG, PNG and static WebP, up to 10 MB each and 20 images per chat (decimal MB).
- The app's image allow-list is limited to GPT-5.6 Sol, GPT-5.6 Luna and Gemini 3.7 Flash. Discovering or selecting another model does not infer image support; Messages requests are text-only in this app.
- App-owned image copies are encrypted on this device, like chat history. The source gallery file is unchanged. Providers process sent images; this is not end-to-end encryption against the provider.
- Compact stacked previews stay above the keyboard-aware composer. Validated image content takes precedence over misleading filenames or MIME labels.
- History shows estimated message/attachment storage, can sort largest/smallest first, and supports selecting multiple chats for deletion. Deleting a chat removes its encrypted attachment copies.
- Command Code's request limits are undocumented; large requests may be rejected. PDF/document/audio/video inputs are not supported by this version.

The documented capabilities above are not runtime-validated here; the app limits are not a gateway guarantee.

## How it works

The Android app calls Command Code directly:

- Public model catalogue: `https://api.commandcode.ai/provider/v1/models` (no API key)
- Chat: `https://api.commandcode.ai/provider/v1/chat/completions`
- Messages: `https://api.commandcode.ai/provider/v1/messages`
- Credits: `https://api.commandcode.ai/alpha/billing/credits`
- Optional plan metadata: `https://api.commandcode.ai/alpha/billing/subscriptions`

The app loads its last validated model catalogue from the device, falls back to the bundled `catalogue/goat-models.json` when no usable cache exists, and refreshes the list from the public endpoint at startup. Catalogue refreshes do not use the API key. For models advertised with the Messages protocol, chat uses the Messages endpoint and sets `max_tokens` to 4096 as the app-side output cap; the gateway may impose other limits. Messages requests do not carry images. The API key is sent only to the relevant authenticated Command Code chat or quota endpoint. Accounts without rolling quota windows remain supported, and no intermediary service is required.

The optional Ktor `:server` module is a reference implementation for catalogue and quota routes. It is not part of the Android chat, quota, or model-selection request path and never receives Android chat prompts or responses.

The reference server exposes `GET /v1/goat/models` for its bundled catalogue and `GET /v1/goat/quota`, which requires a Command Code bearer API key.

## Privacy and security

- The API key is encrypted at rest using an Android Keystore-backed key.
- Mutable app-owned API-key copies used for quota requests are wiped after each fetch. OkHttp requires an immutable bearer-header `String`, so the quota client creates one such value per fetch, shares it across both requests, and leaves it subject to garbage collection rather than explicit wiping.
- Room history and usage data are encrypted with SQLCipher using Keystore-protected key material.
- Android backup and device-transfer rules exclude app files, databases, and preferences.
- Cleartext traffic is disabled.
- ZDR adds the Command Code ZDR request header; upstream providers and Command Code still process request content according to their own policies.
- A failed ZDR route is marked with a red exclamation; tap or long-press it to retry with the current model/ZDR settings or delete the failed message.
- Credits and subscription metadata use alpha upstream APIs and may change. Invalid credit responses are rejected rather than trusted. Monthly allowance is plan-based rather than an API-reported monthly cap, and an unknown plan leaves monthly usage unavailable. A displayed subscription period end is not proof of a quota reset date.

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
