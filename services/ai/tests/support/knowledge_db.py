"""A scratch database with the knowledge schema, for tests of the search index.

It is created by the test administrator role and migrated with the project's own migration files,
then used through the AI service's runtime role, so that the tests also prove that role's grants.
Tests are skipped when no database is configured.
"""

from pathlib import Path
from urllib.parse import urlsplit, urlunsplit

import psycopg
from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

from prism_ai.settings import REPOSITORY_ENV_FILE

DATABASE = "prism_ai_test"
MIGRATIONS = Path(__file__).resolve().parents[4] / "database" / "migrations"
# Only the migrations the knowledge schema needs: core's tables belong to core's own tests.
KNOWLEDGE_MIGRATIONS = ("V202611060001__schemas_and_grants.sql", "V202611070005__search_index.sql")


class TestDatabaseSettings(BaseSettings):
    __test__ = False
    model_config = SettingsConfigDict(env_file=REPOSITORY_ENV_FILE, extra="ignore")

    admin_url: str = Field(validation_alias="PRISM_TEST_ADMIN_DB_URL")
    admin_user: str = Field(validation_alias="PRISM_TEST_ADMIN_DB_USER")
    admin_password: SecretStr = Field(validation_alias="PRISM_TEST_ADMIN_DB_PASSWORD")
    service_url: SecretStr = Field(validation_alias="PRISM_AI_DB_URL")


def _admin_conninfo(settings: TestDatabaseSettings, database: str) -> str:
    # The administrator URL is written for JDBC: jdbc:postgresql://host:port/database
    address = urlsplit(settings.admin_url.removeprefix("jdbc:"))
    return psycopg.conninfo.make_conninfo(
        host=address.hostname,
        port=address.port,
        dbname=database,
        user=settings.admin_user,
        password=settings.admin_password.get_secret_value(),
    )


def create_knowledge_database() -> str | None:
    """Recreates the scratch database and returns the service role's URL for it."""
    try:
        settings = TestDatabaseSettings()
    except ValueError:
        return None
    with psycopg.connect(_admin_conninfo(settings, "postgres"), autocommit=True) as admin:
        admin.execute(f"DROP DATABASE IF EXISTS {DATABASE} WITH (FORCE)")
        admin.execute(f"CREATE DATABASE {DATABASE} ENCODING 'UTF8' TEMPLATE template0")
    with psycopg.connect(_admin_conninfo(settings, DATABASE), autocommit=True) as owner:
        # As Flyway runs them, with core as the default schema, so that anything a migration creates
        # without naming a schema lands where it would in a real database.
        owner.execute("CREATE SCHEMA IF NOT EXISTS core")
        owner.execute("SET search_path TO core")
        for name in KNOWLEDGE_MIGRATIONS:
            owner.execute((MIGRATIONS / name).read_text(encoding="utf-8").encode())
    service = urlsplit(settings.service_url.get_secret_value())
    return urlunsplit(service._replace(path=f"/{DATABASE}"))
