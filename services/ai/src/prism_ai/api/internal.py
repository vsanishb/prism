from typing import Annotated

import psycopg
import structlog
from fastapi import APIRouter, Depends, Request
from psycopg_pool import AsyncConnectionPool, PoolTimeout
from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel

from prism_ai.api.service_auth import ServiceCaller, require_service_caller

logger = structlog.get_logger()

router = APIRouter(prefix="/internal/v1", dependencies=[Depends(require_service_caller)])


class PingResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    service: str
    knowledge_database_connected: bool


async def knowledge_database_connected(pool: AsyncConnectionPool) -> bool:
    try:
        async with pool.connection(timeout=3) as connection:
            await connection.execute("SELECT 1")
        return True
    except (psycopg.Error, PoolTimeout) as error:
        logger.warning("knowledge_database_unavailable", reason=type(error).__name__)
        return False


@router.post("/ping", response_model=PingResponse, response_model_by_alias=True)
async def ping(
    request: Request,
    caller: Annotated[ServiceCaller, Depends(require_service_caller)],
) -> PingResponse:
    logger.info("service_ping", token_id=caller.token_id)
    connected = await knowledge_database_connected(request.app.state.db_pool)
    return PingResponse(service="prism-ai", knowledge_database_connected=connected)
