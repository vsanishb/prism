from typing import Annotated

from fastapi import APIRouter, Depends, Request

from prism_ai.agent.pipeline import PolicyAgent
from prism_ai.agent.schemas import AnalysisRequest, AnalysisResponse
from prism_ai.api.service_auth import require_service_caller

router = APIRouter(prefix="/internal/v1/claims", dependencies=[Depends(require_service_caller)])


def agent(request: Request) -> PolicyAgent:
    instance: PolicyAgent = request.app.state.policy_agent
    return instance


@router.post("/analyze", response_model=AnalysisResponse, response_model_by_alias=True)
async def analyze(
    body: AnalysisRequest, policy_agent: Annotated[PolicyAgent, Depends(agent)]
) -> AnalysisResponse:
    """Analyzes a claim against its policy version's published wording. Assists review; decides nothing."""
    return await policy_agent.run(body)
