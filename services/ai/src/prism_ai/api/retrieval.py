from typing import Annotated

from fastapi import APIRouter, Depends, Request

from prism_ai.api.service_auth import require_service_caller
from prism_ai.retrieval.pipeline import RetrievalPipeline
from prism_ai.retrieval.schemas import IndexRequest, IndexResponse, SearchRequest, SearchResponse

router = APIRouter(prefix="/internal/v1/policies", dependencies=[Depends(require_service_caller)])


def retrieval(request: Request) -> RetrievalPipeline:
    instance: RetrievalPipeline = request.app.state.retrieval_pipeline
    return instance


@router.post("/index", response_model=IndexResponse, response_model_by_alias=True)
async def index(
    body: IndexRequest, pipeline: Annotated[RetrievalPipeline, Depends(retrieval)]
) -> IndexResponse:
    """Builds or replaces a published version's search index. Safe to repeat."""
    return await pipeline.index(body)


@router.post("/search", response_model=SearchResponse, response_model_by_alias=True)
async def search(
    body: SearchRequest, pipeline: Annotated[RetrievalPipeline, Depends(retrieval)]
) -> SearchResponse:
    return await pipeline.search(body)
