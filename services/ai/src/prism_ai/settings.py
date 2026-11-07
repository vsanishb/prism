from functools import lru_cache
from pathlib import Path
from urllib.parse import urlsplit

from pydantic import AnyHttpUrl, Field, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

REPOSITORY_ENV_FILE = Path(__file__).resolve().parents[4] / ".env"
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost"})


class Settings(BaseSettings):
    """Service configuration. Startup fails if a required value is missing."""

    model_config = SettingsConfigDict(
        env_file=REPOSITORY_ENV_FILE, extra="ignore", protected_namespaces=("model_validate", "model_dump")
    )

    database_url: SecretStr = Field(validation_alias="PRISM_AI_DB_URL")
    service_jwt_public_key_path: Path = Field(validation_alias="PRISM_SERVICE_JWT_PUBLIC_KEY_PATH")
    service_token_max_lifetime_seconds: int = 60
    host: str = Field(default="127.0.0.1", validation_alias="PRISM_AI_HOST")
    port: int = Field(default=8090, validation_alias="PRISM_AI_PORT")
    openrouter_api_key: SecretStr = Field(min_length=1, validation_alias="OPENROUTER_API_KEY")
    openrouter_base_url: AnyHttpUrl = Field(
        default=AnyHttpUrl("https://openrouter.ai/api/v1"), validation_alias="PRISM_OPENROUTER_BASE_URL"
    )
    model_request_timeout_seconds: float = Field(default=120.0, gt=0)

    @field_validator("openrouter_base_url")
    @classmethod
    def encrypted_unless_local(cls, value: AnyHttpUrl) -> AnyHttpUrl:
        """Claim data never crosses a network unencrypted. Plain HTTP is for local test stubs."""
        if value.scheme != "https" and urlsplit(str(value)).hostname not in LOOPBACK_HOSTS:
            raise ValueError("The model endpoint must use HTTPS")
        return value


@lru_cache
def get_settings() -> Settings:
    return Settings()
