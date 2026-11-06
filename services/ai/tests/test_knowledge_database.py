"""Connects to a real database, using the same event loop the service runs on."""

import asyncio
import os

import pytest
from psycopg_pool import AsyncConnectionPool

from prism_ai.api.internal import knowledge_database_connected
from prism_ai.serve import event_loop_factory
from prism_ai.settings import Settings


def database_url() -> str | None:
    try:
        return Settings().database_url.get_secret_value()
    except ValueError:
        return os.environ.get("PRISM_AI_DB_URL")


async def check(url: str) -> bool:
    async with AsyncConnectionPool(url, min_size=0, max_size=1, open=False) as pool:
        return await knowledge_database_connected(pool)


def test_service_event_loop_can_reach_the_database() -> None:
    url = database_url()
    if not url:
        pytest.skip("No database configured")

    assert asyncio.run(check(url), loop_factory=event_loop_factory()) is True


def test_reports_an_unreachable_database_without_raising() -> None:
    url = "postgresql://nobody:none@127.0.0.1:1/none?connect_timeout=1"

    assert asyncio.run(check(url), loop_factory=event_loop_factory()) is False
