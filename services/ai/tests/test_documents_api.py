"""The extraction endpoint: it must refuse unsigned calls, and report failures in a form core can act on."""

import base64
import hashlib
import json
import time
from collections.abc import Iterator
from pathlib import Path
from typing import Any
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from pydantic import AnyHttpUrl, SecretStr, ValidationError

from prism_ai.documents.catalog import DocumentType, fields_for
from prism_ai.documents.pipeline import DocumentPipeline
from prism_ai.main import create_app
from prism_ai.settings import Settings
from tests.support.scripted import Reply, ScriptedProvider, completion

EXTRACT = "/internal/v1/documents/extract"
BILL = Path(__file__).parents[3] / "test-data/claims/appendectomy/hospital-bill.pdf"
ABSENT = {"state": "MISSING", "value": "", "confidence": "HIGH", "page": 0, "quote": ""}


@pytest.fixture(scope="module")
def key() -> ec.EllipticCurvePrivateKey:
    return ec.generate_private_key(ec.SECP256R1())


@pytest.fixture(scope="module")
def client(key: ec.EllipticCurvePrivateKey, tmp_path_factory: pytest.TempPathFactory) -> Iterator[TestClient]:
    public = tmp_path_factory.mktemp("keys") / "public.pem"
    public.write_bytes(
        key.public_key().public_bytes(
            serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
        )
    )
    settings = Settings.model_construct(
        database_url=SecretStr("postgresql://nobody@127.0.0.1:1/none"),
        service_jwt_public_key_path=public,
        service_token_max_lifetime_seconds=60,
        openrouter_api_key=SecretStr("not-a-real-key"),
        openrouter_base_url=AnyHttpUrl("https://openrouter.test/api/v1"),
        model_request_timeout_seconds=1.0,
    )
    with TestClient(create_app(settings)) as test_client:
        yield test_client


def use_script(client: TestClient, script: list[Reply]) -> None:
    provider = ScriptedProvider(script)
    client.app.state.document_pipeline = DocumentPipeline(provider.gateway(provider.client()))  # type: ignore[attr-defined]


def body(**overrides: Any) -> bytes:
    payload = {
        "documentId": str(uuid4()),
        "declaredType": "HOSPITAL_BILL",
        "mediaType": "application/pdf",
        "content": base64.b64encode(BILL.read_bytes()).decode(),
    }
    return json.dumps(payload | overrides).encode()


def signed(key: ec.EllipticCurvePrivateKey, content: bytes) -> dict[str, str]:
    now = int(time.time())
    private = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    )
    token = jwt.encode(
        {
            "iss": "prism-core",
            "aud": "prism-ai",
            "sub": f"POST {EXTRACT}",
            "iat": now,
            "exp": now + 60,
            "jti": str(uuid4()),
            "body_sha256": hashlib.sha256(content).hexdigest(),
        },
        private,
        algorithm="ES256",
    )
    return {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}


def post(client: TestClient, key: ec.EllipticCurvePrivateKey, content: bytes) -> httpx.Response:
    response: httpx.Response = client.post(EXTRACT, content=content, headers=signed(key, content))
    return response


def good_script() -> list[Reply]:
    fields = {spec.name: ABSENT for spec in fields_for(DocumentType.HOSPITAL_BILL)}
    return [
        completion({"document_type": "HOSPITAL_BILL", "confidence": "HIGH"}),
        completion({"fields": fields, "line_items": []}),
    ]


def test_unsigned_extraction_is_refused(client: TestClient) -> None:
    assert client.post(EXTRACT, content=body()).status_code == 401


def test_a_token_for_another_body_is_refused(client: TestClient, key: ec.EllipticCurvePrivateKey) -> None:
    response = client.post(EXTRACT, content=body(), headers=signed(key, b"{}"))
    assert response.status_code == 401


def test_extraction_returns_fields_and_usage_in_camel_case(
    client: TestClient, key: ec.EllipticCurvePrivateKey
) -> None:
    use_script(client, good_script())
    response = post(client, key, body())
    assert response.status_code == 200
    result = response.json()
    assert result["pipelineVersion"] == "doc-1"
    assert result["classification"]["documentType"] == "HOSPITAL_BILL"
    assert result["fields"][0]["rawValue"] is None
    assert result["runs"][0]["promptVersion"] == "classify.v1"
    assert result["runs"][0]["costUsd"] == "0.0012"


def test_an_unknown_field_in_the_request_is_refused(
    client: TestClient, key: ec.EllipticCurvePrivateKey
) -> None:
    assert post(client, key, body(extra="x")).status_code == 422


@pytest.mark.parametrize(
    "overrides",
    [{"mediaType": "text/html"}, {"declaredType": "POLICY_WORDING"}, {"content": "@@not base64@@"}],
)
def test_invalid_requests_are_refused(
    client: TestClient, key: ec.EllipticCurvePrivateKey, overrides: dict[str, str]
) -> None:
    assert post(client, key, body(**overrides)).status_code == 422


def test_a_rate_limit_is_reported_as_retryable_with_usage(
    client: TestClient, key: ec.EllipticCurvePrivateKey
) -> None:
    use_script(client, [httpx.Response(429)])
    response = post(client, key, body())
    assert response.status_code == 503
    failure = response.json()
    assert failure["code"] == "model_unavailable"
    assert failure["retryable"] is True
    assert len(failure["runs"]) == 1


def test_invalid_model_output_is_reported_as_not_retryable(
    client: TestClient, key: ec.EllipticCurvePrivateKey
) -> None:
    use_script(client, [completion("bad"), completion("bad"), completion("bad")])
    response = post(client, key, body())
    assert response.status_code == 422
    assert response.json()["code"] == "model_output_invalid"
    assert response.json()["retryable"] is False


def test_an_unreadable_file_is_reported_as_not_retryable(
    client: TestClient, key: ec.EllipticCurvePrivateKey
) -> None:
    use_script(client, [])
    content = body(content=base64.b64encode(b"not a pdf").decode())
    response = post(client, key, content)
    assert response.status_code == 422
    assert response.json() == {"code": "document_unreadable", "retryable": False, "runs": []}


def test_failures_expose_no_internal_detail(client: TestClient, key: ec.EllipticCurvePrivateKey) -> None:
    use_script(client, [httpx.Response(401, json={"error": "secret detail"})])
    response = post(client, key, body())
    assert response.status_code == 502
    assert "secret detail" not in response.text
    assert "openrouter" not in response.text.lower()


def test_plain_http_is_refused_for_a_remote_model_endpoint() -> None:
    with pytest.raises(ValidationError):
        Settings.model_validate(
            {
                "PRISM_AI_DB_URL": "postgresql://x",
                "PRISM_SERVICE_JWT_PUBLIC_KEY_PATH": "key.pem",
                "OPENROUTER_API_KEY": "not-a-real-key",
                "PRISM_OPENROUTER_BASE_URL": "http://openrouter.example/api/v1",
            }
        )


def test_plain_http_is_allowed_for_a_local_stub() -> None:
    settings = Settings.model_validate(
        {
            "PRISM_AI_DB_URL": "postgresql://x",
            "PRISM_SERVICE_JWT_PUBLIC_KEY_PATH": "key.pem",
            "OPENROUTER_API_KEY": "not-a-real-key",
            "PRISM_OPENROUTER_BASE_URL": "http://127.0.0.1:8099/api/v1",
        }
    )
    assert settings.openrouter_base_url.host == "127.0.0.1"
