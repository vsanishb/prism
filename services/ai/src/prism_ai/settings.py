from functools import lru_cache
from pathlib import Path

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

REPOSITORY_ENV_FILE = Path(__file__).resolve().parents[4] / ".env"


class Settings(BaseSettings):
    """Service configuration. Startup fails if a required value is missing."""

    model_config = SettingsConfigDict(env_file=REPOSITORY_ENV_FILE, extra="ignore")

    database_url: SecretStr = Field(validation_alias="PRISM_AI_DB_URL")
    service_jwt_public_key_path: Path = Field(validation_alias="PRISM_SERVICE_JWT_PUBLIC_KEY_PATH")
    service_token_max_lifetime_seconds: int = 60


@lru_cache
def get_settings() -> Settings:
    return Settings()
