"""Connects to a real database, using the same event loop the service runs on."""

import asyncio
import os
from collections.abc import Coroutine
from typing import Any

import psycopg
import pytest

from prism_ai.api.internal import knowledge_database_connected
from prism_ai.database import create_pool
from prism_ai.serve import event_loop_factory
from prism_ai.settings import Settings


def database_url() -> str | None:
    try:
        return Settings().database_url.get_secret_value()
    except ValueError:
        return os.environ.get("PRISM_AI_DB_URL")


def run[T](coroutine: Coroutine[Any, Any, T]) -> T:
    return asyncio.run(coroutine, loop_factory=event_loop_factory())


async def check(url: str) -> bool:
    async with create_pool(url) as pool:
        return await knowledge_database_connected(pool)


async def survives_a_dropped_idle_connection(url: str) -> bool:
    async with create_pool(url) as pool:
        async with pool.connection() as connection:
            cursor = await connection.execute("SELECT pg_backend_pid()")
            row = await cursor.fetchone()
            assert row is not None
            backend_pid = row[0]

        # The connection is now idle in the pool, as it would be when a database restarts.
        async with await psycopg.AsyncConnection.connect(url, autocommit=True) as other:
            await other.execute("SELECT pg_terminate_backend(%s)", (backend_pid,))
        await asyncio.sleep(0.2)

        return await knowledge_database_connected(pool)


def test_service_event_loop_can_reach_the_database() -> None:
    url = database_url()
    if not url:
        pytest.skip("No database configured")

    assert run(check(url)) is True


def test_pool_replaces_a_connection_dropped_while_idle() -> None:
    url = database_url()
    if not url:
        pytest.skip("No database configured")

    assert run(survives_a_dropped_idle_connection(url)) is True


def test_reports_an_unreachable_database_without_raising() -> None:
    url = "postgresql://nobody:none@127.0.0.1:1/none?connect_timeout=1"

    assert run(check(url)) is False
