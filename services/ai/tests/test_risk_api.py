"""The risk endpoints: signed calls only, strict request shapes, and camelCase results for core."""

import hashlib
import json
import time
from collections.abc import Iterator
from typing import Any
from uuid import uuid4

import httpx
import jwt
import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient
from pydantic import AnyHttpUrl, SecretStr

from prism_ai.main import create_app
from prism_ai.risk.features import FEATURE_NAMES
from prism_ai.risk.summary import RiskSummarizer
from prism_ai.settings import Settings
from tests.support.scripted import ScriptedProvider, completion

ANOMALY = "/internal/v1/claims/risk/anomaly"
SUMMARY = "/internal/v1/claims/risk/summary"


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


def post(
    client: TestClient, key: ec.EllipticCurvePrivateKey, path: str, payload: dict[str, Any]
) -> httpx.Response:
    content = json.dumps(payload).encode()
    now = int(time.time())
    private = key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()
    )
    token = jwt.encode(
        {
            "iss": "prism-core",
            "aud": "prism-ai",
            "sub": f"POST {path}",
            "iat": now,
            "exp": now + 60,
            "jti": str(uuid4()),
            "body_sha256": hashlib.sha256(content).hexdigest(),
        },
        private,
        algorithm="ES256",
    )
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    response: httpx.Response = client.post(path, content=content, headers=headers)
    return response


def anomaly_body(**values: float | None) -> dict[str, Any]:
    defaults = dict.fromkeys(FEATURE_NAMES, 1.0) | {"claimedAmount": 98_400.0, "daysSinceCoverStart": 187.0}
    merged = defaults | values
    return {
        "claimId": str(uuid4()),
        "featureVersion": "risk-features-1",
        "features": [{"name": name, "value": value} for name, value in merged.items()],
    }


def test_unsigned_calls_are_refused(client: TestClient) -> None:
    assert client.post(ANOMALY, json=anomaly_body()).status_code == 401
    assert client.post(SUMMARY, json={}).status_code == 401


def test_anomaly_scores_come_back_per_detector_in_camel_case(
    client: TestClient, key: ec.EllipticCurvePrivateKey
) -> None:
    response = post(client, key, ANOMALY, anomaly_body())
    assert response.status_code == 200
    body = response.json()
    assert body["modelVersion"] == "anomaly-1"
    assert body["baselineSize"] == 5000
    assert [detector["detector"] for detector in body["detectors"]] == [
        "ISOLATION_FOREST",
        "LOCAL_OUTLIER_FACTOR",
    ]
    assert {"score", "threshold", "percentile", "flagged"} <= set(body["detectors"][0])
    assert "probability" not in json.dumps(body).lower()


@pytest.mark.parametrize(
    "payload",
    [
        anomaly_body() | {"featureVersion": "risk-features-0"},
        anomaly_body() | {"features": [{"name": "claimedAmount", "value": 1.0}]},
        anomaly_body() | {"patientName": "Synthetic Patient"},
    ],
)
def test_malformed_anomaly_requests_are_refused(
    client: TestClient, key: ec.EllipticCurvePrivateKey, payload: dict[str, Any]
) -> None:
    assert post(client, key, ANOMALY, payload).status_code == 422


def test_a_summary_is_returned_with_its_usage(client: TestClient, key: ec.EllipticCurvePrivateKey) -> None:
    provider = ScriptedProvider(
        [completion({"summary": "The invoice total does not match its items [S1].", "signals_cited": ["S1"]})]
    )
    client.app.state.risk_summarizer = RiskSummarizer(provider.gateway(provider.client()))  # type: ignore[attr-defined]
    payload = {
        "claimId": str(uuid4()),
        "band": "MEDIUM",
        "score": 25,
        "route": "STANDARD_REVIEW",
        "signals": [
            {
                "id": "S1",
                "source": "RULE",
                "kind": "DATA_INCONSISTENCY",
                "category": "FINANCIAL",
                "severity": "HIGH",
                "title": "The invoice total does not match its items",
                "explanation": "The line items do not add up to the stated total.",
                "contribution": 25,
            }
        ],
    }
    response = post(client, key, SUMMARY, payload)
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "AVAILABLE"
    assert body["cited"] == ["S1"]
    assert body["promptVersion"] == "risk_summary.v1"
    assert body["runs"][0]["task"] == "risk_summary"
