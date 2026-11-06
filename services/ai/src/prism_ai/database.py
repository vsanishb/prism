from psycopg_pool import AsyncConnectionPool


def create_pool(database_url: str) -> AsyncConnectionPool:
    """Builds the service's connection pool, unopened.

    Connections are validated before use because idle ones die silently: Neon closes them when a
    compute scales to zero, and any database restart does the same.
    """
    return AsyncConnectionPool(
        database_url,
        min_size=0,
        max_size=5,
        open=False,
        check=AsyncConnectionPool.check_connection,
    )
