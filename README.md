# Command Code Chat

Android chat client with a small Ktor service for the GOAT model catalogue and
live quota telemetry. The service reads `PORT` (default `8080`) and does not
handle chat prompts or responses.

## Local server and Android builds

Run the Ktor service:

```bash
./gradlew :server:run
```

Build a debug APK for an Android emulator using the host service:

```bash
./gradlew :app:assembleDebug -PcommandCodeChatServiceUrl=http://10.0.2.2:8080
```

Build a release APK with an explicit HTTPS service URL:

```bash
./gradlew :app:assembleRelease -PcommandCodeChatServiceUrl=https://your-service.example
```

Release builds reject a missing or non-HTTPS `commandCodeChatServiceUrl`.
The URL is build-time configuration, not an Android user setting. The debug
default is `http://10.0.2.2:8080`; production deployments must use HTTPS.

## Service API and boundaries

- `GET /v1/goat/models` is public and cacheable (`public, max-age=300`). It
  returns the validated GOAT catalogue.
- `GET /v1/goat/quota` requires one pass-through
  `Authorization: Bearer <Command Code key>` header and is non-cacheable
  (`no-store`). The service forwards the key to the quota upstream for the
  request only; it never stores or logs keys.
- Chat remains direct: the Android client streams to
  `https://api.commandcode.ai/provider/v1/chat/completions`. Chat prompts and
  responses do not pass through this service.

The quota upstream is alpha and can change. Malformed or otherwise unusable
upstream responses are rejected safely; Android retains a valid stale quota
snapshot when available, or presents an unavailable state when it has none.
Production requires TLS termination and edge rate limiting in front of the
service.

Sandbox execution is not implemented. Its research and proposed boundary are
documented in
[`docs/research/2026-08-30-sandboxed-command-execution.md`](docs/research/2026-08-30-sandboxed-command-execution.md).
