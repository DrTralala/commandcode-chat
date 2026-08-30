# Command Code Chat

Android chat client that calls Command Code directly for chat and quota. The
API key remains device-held, and the pinned GOAT model catalogue is bundled in
the APK. The optional `:server` module is reference-only; it is not an
intermediary in the Android request path.

## Optional reference server

Run the Ktor reference service when exercising its API or server tests:

```bash
./gradlew :server:run
```

The service reads `PORT` (default `8080`) and provides reference catalogue and
quota routes. Android does not require this service for chat, quota, or model
selection.

## Android builds

Build a debug APK:

```bash
./gradlew :app:assembleDebug
```

Build a release APK:

```bash
./gradlew :app:assembleRelease
```

No service URL or deployed intermediary is required by either build type.

## Direct Android API and reference-server boundaries

- Android streams chat directly to
  `https://api.commandcode.ai/provider/v1/chat/completions`.
- Android fetches quota directly from
  `https://api.commandcode.ai/alpha/billing/credits`.
- Both requests use the API key held on the device for that request. The key
  is not sent to the optional reference server.
- Android loads the pinned `goat-models.json` catalogue bundled in the APK and
  does not fetch a remote catalogue.

- `GET /v1/goat/models` is public and cacheable (`public, max-age=300`). It
  returns the validated GOAT catalogue from the optional reference server.
- `GET /v1/goat/quota` requires one pass-through
  `Authorization: Bearer <Command Code key>` header and is non-cacheable
  (`no-store`). The service forwards the key to the quota upstream for the
  request only; it never stores or logs keys.
- The reference server does not handle Android chat prompts or responses.

The quota upstream is alpha and can change. Malformed or otherwise unusable
upstream responses are rejected safely; Android retains a valid stale quota
snapshot when available, or presents an unavailable state when it has none.
Production requires TLS termination and edge rate limiting in front of the
service.

Sandbox execution is not implemented. Its research and proposed boundary are
documented in
[`docs/research/2026-08-30-sandboxed-command-execution.md`](docs/research/2026-08-30-sandboxed-command-execution.md).
