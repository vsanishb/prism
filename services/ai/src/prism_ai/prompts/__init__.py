"""Prompts are versioned files. Changing one means adding a new version, and re-running the
evaluation set."""

from dataclasses import dataclass
from functools import cache
from pathlib import Path

PROMPT_DIRECTORY = Path(__file__).parent


@dataclass(frozen=True)
class Prompt:
    version: str
    text: str


@cache
def load_prompt(name: str, version: int) -> Prompt:
    path = PROMPT_DIRECTORY / f"{name}.v{version}.md"
    return Prompt(version=f"{name}.v{version}", text=path.read_text(encoding="utf-8"))
