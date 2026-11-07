from typing import Annotated

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse

from prism_ai.api.service_auth import require_service_caller
from prism_ai.documents.pipeline import DocumentPipeline
from prism_ai.documents.schemas import ExtractRequest, ExtractResponse, ProcessingFailure
from prism_ai.gateway.errors import ProcessingError

router = APIRouter(prefix="/internal/v1/documents", dependencies=[Depends(require_service_caller)])


def pipeline(request: Request) -> DocumentPipeline:
    instance: DocumentPipeline = request.app.state.document_pipeline
    return instance


@router.post("/extract", response_model=ExtractResponse, response_model_by_alias=True)
async def extract(
    body: ExtractRequest, documents: Annotated[DocumentPipeline, Depends(pipeline)]
) -> ExtractResponse:
    return await documents.run(body)


def processing_failure(_request: Request, error: Exception) -> JSONResponse:
    """Gives core what it needs to retry or flag, and usage to record. The message stays out, as
    it is for this service's logs."""
    assert isinstance(error, ProcessingError)  # noqa: S101 - registered for this type only
    failure = ProcessingFailure(code=error.code, retryable=error.retryable, runs=error.runs)
    return JSONResponse(failure.model_dump(mode="json", by_alias=True), status_code=error.status_code)
