"""収集系で共有する HTTP クライアントと時刻ユーティリティ。"""

from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone

import httpx

log = logging.getLogger(__name__)

# Reddit をはじめ、いくつかのエンドポイントは既定の UA を弾く。
USER_AGENT = "infomaster/1.0 (personal AI news digest; +https://github.com/)"

TIMEOUT = httpx.Timeout(20.0, connect=10.0)


def client() -> httpx.Client:
    return httpx.Client(
        timeout=TIMEOUT,
        follow_redirects=True,
        headers={"User-Agent": USER_AGENT},
    )


def get_json(url: str, params: dict | None = None) -> dict | list:
    with client() as c:
        resp = c.get(url, params=params)
        resp.raise_for_status()
        return resp.json()


def cutoff(lookback_hours: int) -> datetime:
    return datetime.now(timezone.utc) - timedelta(hours=lookback_hours)


def utc(ts: float) -> datetime:
    return datetime.fromtimestamp(ts, tz=timezone.utc)


def clip(text: str, limit: int = 400) -> str:
    """抜粋を一定長で切る。選別段に渡すトークン量を抑えるのが目的。"""
    text = " ".join((text or "").split())
    return text if len(text) <= limit else text[: limit - 1] + "…"
