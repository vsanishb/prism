"""Service entry point. Run with `python -m prism_ai.serve`."""

import asyncio
import sys
from collections.abc import Callable

import uvicorn

from prism_ai.main import create_app
from prism_ai.settings import get_settings


def event_loop_factory() -> Callable[[], asyncio.AbstractEventLoop] | None:
    """Async psycopg cannot run on Windows' default ProactorEventLoop."""
    return asyncio.SelectorEventLoop if sys.platform == "win32" else None


def main() -> None:
    settings = get_settings()
    config = uvicorn.Config(create_app, factory=True, host=settings.host, port=settings.port)
    asyncio.run(uvicorn.Server(config).serve(), loop_factory=event_loop_factory())


if __name__ == "__main__":
    main()
