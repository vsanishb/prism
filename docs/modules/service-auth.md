# Service Authentication

Trust between core and the AI service. Code: `dev.prism.core.aiclient` and `prism_ai.api.service_auth`. Rules: [security.md](../security.md#inter-service-authentication).

## How it works

`ServiceTokenSigner` signs each request with ES256 and a 60-second lifetime, binding the operation and a SHA-256 of the body. `ServiceTokenVerifier` checks signature, pinned algorithm, issuer, audience, expiry, lifetime, operation, body hash, and replay. Any failure returns `401` with no detail. The reason goes to the AI service log.

`AiServiceClient` is the only way core calls the AI service.

## Behavior to know

- **The client must stay on HTTP/1.1.** The default upgrade attempt loses the body under Uvicorn, and the hash check then refuses the call.
- **Run the service with `python -m prism_ai.serve`, not `uvicorn` directly.** Async psycopg needs a selector event loop on Windows, and the entry point chooses it.
- Replay protection is per process. Short lifetimes and body binding are the main defence.
- `/health/live` is the only unauthenticated route, and API docs are disabled.

## Key rotation

Tokens carry a `kid`, but the AI service currently holds one public key. Overlapping rotation is not built yet.

## Testing

`test_service_auth.py` covers each rejection case. `HttpAiServiceClientTest` checks what actually crosses the wire. System status runs both directions live.
