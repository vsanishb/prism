"""Verifies tokens signed by the core service. Every /internal route depends on this."""

import hashlib
import hmac
import time
from dataclasses import dataclass
from threading import Lock
from typing import Annotated, Any

import jwt
import structlog
from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

logger = structlog.get_logger()

ISSUER = "prism-core"
AUDIENCE = "prism-ai"
ALGORITHM = "ES256"
CLOCK_LEEWAY_SECONDS = 5

bearer = HTTPBearer(auto_error=False)


class ReplayGuard:
    """Refuses a token ID seen before its expiry. Scoped to one process: it narrows the replay
    window, while the short token lifetime and body hash remain the primary defences."""

    def __init__(self) -> None:
        self._seen: dict[str, float] = {}
        self._lock = Lock()

    def register(self, token_id: str, expires_at: float) -> bool:
        now = time.time()
        with self._lock:
            self._seen = {key: expiry for key, expiry in self._seen.items() if expiry > now}
            if token_id in self._seen:
                return False
            self._seen[token_id] = expires_at
            return True


@dataclass(frozen=True)
class ServiceCaller:
    operation: str
    token_id: str


class ServiceTokenVerifier:
    def __init__(self, public_key: str, max_lifetime_seconds: int) -> None:
        self._public_key = public_key
        self._max_lifetime = max_lifetime_seconds
        self._replay_guard = ReplayGuard()

    def verify(self, token: str, operation: str, body: bytes) -> ServiceCaller:
        """Raises jwt.InvalidTokenError for anything but a valid, unused token for this request."""
        # The algorithm is pinned, so that a token cannot choose how it is verified.
        claims: dict[str, Any] = jwt.decode(
            token,
            self._public_key,
            algorithms=[ALGORITHM],
            audience=AUDIENCE,
            issuer=ISSUER,
            leeway=CLOCK_LEEWAY_SECONDS,
            options={"require": ["exp", "iat", "iss", "aud", "jti", "sub", "body_sha256"]},
        )
        if claims["exp"] - claims["iat"] > self._max_lifetime:
            raise jwt.InvalidTokenError("Token lifetime exceeds the limit")
        if claims["sub"] != operation:
            raise jwt.InvalidTokenError("Token was issued for a different operation")
        if not hmac.compare_digest(claims["body_sha256"], hashlib.sha256(body).hexdigest()):
            raise jwt.InvalidTokenError("Body hash does not match")
        if not self._replay_guard.register(claims["jti"], float(claims["exp"]) + CLOCK_LEEWAY_SECONDS):
            raise jwt.InvalidTokenError("Token has already been used")
        return ServiceCaller(operation=operation, token_id=claims["jti"])


def _unauthorized() -> HTTPException:
    return HTTPException(status.HTTP_401_UNAUTHORIZED, "Unauthorized", {"WWW-Authenticate": "Bearer"})


async def require_service_caller(
    request: Request,
    credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)],
) -> ServiceCaller:
    if credentials is None:
        raise _unauthorized()
    verifier: ServiceTokenVerifier = request.app.state.token_verifier
    operation = f"{request.method} {request.url.path}"
    try:
        return verifier.verify(credentials.credentials, operation, await request.body())
    except jwt.InvalidTokenError as error:
        # The reason is logged but never returned, so callers learn nothing about the check.
        logger.warning("service_token_rejected", operation=operation, reason=type(error).__name__)
        raise _unauthorized() from error
