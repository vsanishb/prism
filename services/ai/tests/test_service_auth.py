"""The AI service must refuse every call that is not a valid, unused, core-signed token for that
exact request."""

import hashlib
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any
from unittest.mock import patch
from uuid import uuid4

import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from pydantic import AnyHttpUrl, SecretStr

from prism_ai.main import create_app
from prism_ai.settings import Settings

PING = "/internal/v1/ping"
BODY = b"{}"


def pem_private(key: ec.EllipticCurvePrivateKey) -> bytes:
    return key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    )


@pytest.fixture(scope="module")
def signing_key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


@pytest.fixture(scope="module")
def client(
    signing_key: ec.EllipticCurvePrivateKey, tmp_path_factory: pytest.TempPathFactory
) -> Iterator[TestClient]:
    public_path: Path = tmp_path_factory.mktemp("keys") / "public.pem"
    public_path.write_bytes(
        signing_key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    settings = Settings.model_construct(
        database_url=SecretStr("postgresql://nobody@127.0.0.1:1/none"),
        service_jwt_public_key_path=public_path,
        service_token_max_lifetime_seconds=60,
        openrouter_api_key=SecretStr("not-a-real-key"),
        openrouter_base_url=AnyHttpUrl("https://openrouter.test/api/v1"),
        model_request_timeout_seconds=1.0,
    )

    async def database_up(_: object) -> bool:
        return True

    with (
        patch("prism_ai.api.internal.knowledge_database_connected", database_up),
        TestClient(create_app(settings)) as test_client,
    ):
        yield test_client


def claims(**overrides: Any) -> dict[str, Any]:
    now = int(time.time())
    base: dict[str, Any] = {
        "iss": "prism-core",
        "aud": "prism-ai",
        "sub": f"POST {PING}",
        "iat": now,
        "exp": now + 60,
        "jti": str(uuid4()),
        "body_sha256": hashlib.sha256(BODY).hexdigest(),
    }
    return {**base, **overrides}


def sign(key: ec.EllipticCurvePrivateKey, payload: dict[str, Any]) -> str:
    return jwt.encode(payload, pem_private(key), algorithm="ES256")


def call(client: TestClient, token: str | None, body: bytes = BODY) -> int:
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    return int(client.post(PING, content=body, headers=headers).status_code)


def test_accepts_valid_token(client: TestClient, signing_key: ec.EllipticCurvePrivateKey) -> None:
    headers = {"Authorization": f"Bearer {sign(signing_key, claims())}"}
    response = client.post(PING, content=BODY, headers=headers)
    assert response.status_code == 200
    assert response.json() == {"service": "prism-ai", "knowledgeDatabaseConnected": True}


def test_health_needs_no_token(client: TestClient) -> None:
    assert client.get("/health/live").status_code == 200


def test_rejects_missing_token(client: TestClient) -> None:
    assert call(client, None) == 401


def test_rejects_garbage_token(client: TestClient) -> None:
    assert call(client, "not-a-token") == 401


def test_rejects_token_signed_by_another_key(client: TestClient) -> None:
    assert call(client, sign(ec.generate_private_key(ec.SECP256R1()), claims())) == 401


def test_rejects_symmetric_algorithm_downgrade(client: TestClient) -> None:
    forged = jwt.encode(claims(), "k" * 64, algorithm="HS256")
    assert call(client, forged) == 401


def test_rejects_unsigned_algorithm_none(client: TestClient) -> None:
    forged = jwt.encode(claims(), key="", algorithm="none")
    assert call(client, forged) == 401


@pytest.mark.parametrize(
    "overrides",
    [
        {"aud": "another-service"},
        {"iss": "someone-else"},
        {"sub": "POST /internal/v1/other"},
        {"body_sha256": hashlib.sha256(b"tampered").hexdigest()},
        {"exp": int(time.time()) - 60, "iat": int(time.time()) - 90},
        {"exp": int(time.time()) + 3600},
    ],
    ids=["audience", "issuer", "operation", "body-hash", "expired", "lifetime-too-long"],
)
def test_rejects_invalid_claims(
    client: TestClient, signing_key: ec.EllipticCurvePrivateKey, overrides: dict[str, Any]
) -> None:
    assert call(client, sign(signing_key, claims(**overrides))) == 401


@pytest.mark.parametrize("missing", ["jti", "exp", "body_sha256", "sub"])
def test_rejects_missing_claims(
    client: TestClient, signing_key: ec.EllipticCurvePrivateKey, missing: str
) -> None:
    payload = claims()
    del payload[missing]
    assert call(client, sign(signing_key, payload)) == 401


def test_rejects_body_changed_after_signing(
    client: TestClient, signing_key: ec.EllipticCurvePrivateKey
) -> None:
    assert call(client, sign(signing_key, claims()), body=b'{"changed":true}') == 401


def test_rejects_replayed_token(client: TestClient, signing_key: ec.EllipticCurvePrivateKey) -> None:
    token = sign(signing_key, claims())
    assert call(client, token) == 200
    assert call(client, token) == 401
