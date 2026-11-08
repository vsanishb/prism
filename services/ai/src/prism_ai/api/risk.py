import asyncio
from typing import Annotated

from fastapi import APIRouter, Depends, Request

from prism_ai.api.service_auth import require_service_caller
from prism_ai.risk.anomaly import score
from prism_ai.risk.detectors import AnomalyModels
from prism_ai.risk.schemas import AnomalyRequest, AnomalyResponse, ExplanationRequest, ExplanationResponse
from prism_ai.risk.summary import RiskSummarizer

router = APIRouter(prefix="/internal/v1/claims/risk", dependencies=[Depends(require_service_caller)])


def anomaly_models(request: Request) -> AnomalyModels:
    models: AnomalyModels = request.app.state.anomaly_models
    return models


def summarizer(request: Request) -> RiskSummarizer:
    instance: RiskSummarizer = request.app.state.risk_summarizer
    return instance


@router.post("/anomaly", response_model=AnomalyResponse, response_model_by_alias=True)
async def anomaly(
    body: AnomalyRequest, models: Annotated[AnomalyModels, Depends(anomaly_models)]
) -> AnomalyResponse:
    """Scores a claim's features with Isolation Forest and LOF. Not a fraud probability."""
    return await asyncio.to_thread(score, models, body)


@router.post("/summary", response_model=ExplanationResponse, response_model_by_alias=True)
async def summary(
    body: ExplanationRequest, risk_summarizer: Annotated[RiskSummarizer, Depends(summarizer)]
) -> ExplanationResponse:
    """Summarizes a stored screening's signals. Optional, and cannot change the screening."""
    return await risk_summarizer.summarize(body)
