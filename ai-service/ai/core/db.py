"""PostgreSQL 连接（ai_db）。"""

from __future__ import annotations

import psycopg
from psycopg.rows import dict_row

from ..config import get_settings


def connect(**kwargs):
    settings = get_settings()
    return psycopg.connect(settings.database_url, row_factory=dict_row, **kwargs)
