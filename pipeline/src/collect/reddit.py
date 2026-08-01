"""Reddit からの収集。

Reddit は公開 JSON エンドポイント (/top.json) を UA に関わらず 403 で塞いでいるが、
Atom フィード (/top/.rss) は通る。ただしフィードにはスコアが含まれないため、
「top/day の並び順」を人気の代理指標として使っている。
"""

from __future__ import annotations

import logging
import re
import time
from datetime import datetime, timezone
from typing import Any

import feedparser

from ..models import Item
from ._http import client, clip, cutoff

log = logging.getLogger(__name__)

_TAG = re.compile(r"<[^>]+>")

# サブレディット間の待ち時間と、429 を食らったときの再試行までの待ち時間。
PAUSE_SECONDS = 5.0
RETRY_SECONDS = 15.0


def collect(config: dict[str, Any], lookback_hours: int) -> list[Item]:
    since = cutoff(lookback_hours)
    items: list[Item] = []

    with client() as http:
        for sub in config.get("subreddits", []):
            name = sub["name"]
            take = sub.get("take", 10)
            content = _fetch_with_backoff(http, name)
            if content is None:
                continue
            parsed = feedparser.parse(content)

            kept = 0
            for rank, entry in enumerate(parsed.entries):
                if kept >= take:
                    break
                published = _published_at(entry)
                if published is not None and published < since:
                    continue
                permalink = entry.get("link")
                title = entry.get("title")
                if not permalink or not title:
                    continue

                items.append(
                    Item(
                        source=f"r/{name}",
                        source_kind="reddit",
                        tier="community",
                        title=title.strip(),
                        url=permalink,
                        published_at=published,
                        excerpt=clip(_TAG.sub(" ", entry.get("summary") or "")),
                        # フィードに実スコアが無いため順位を代理指標にする。
                        # 上位ほど大きい値になり、他ソースの engagement と桁を揃える。
                        engagement=max(1, (take - rank) * 25),
                        discussion_url=permalink,
                    )
                )
                kept += 1

            # 公開フィードを連続で叩かない。Reddit のレート制限はかなり厳しく、
            # 1 秒間隔だと 2 つ目以降が 429 になる。
            time.sleep(PAUSE_SECONDS)

    return items


def _fetch_with_backoff(http, name: str) -> bytes | None:
    """r/{name} の Atom フィードを取得する。429 は待って一度だけ再試行する。"""
    url = f"https://www.reddit.com/r/{name}/top/.rss"
    for attempt in range(2):
        try:
            resp = http.get(url, params={"t": "day"})
            if resp.status_code == 429:
                if attempt == 0:
                    wait = float(resp.headers.get("retry-after", RETRY_SECONDS))
                    log.info("reddit r/%s rate limited, waiting %.0fs", name, wait)
                    time.sleep(min(wait, 30.0))
                    continue
                log.warning("reddit r/%s still rate limited, skipping", name)
                return None
            resp.raise_for_status()
            return resp.content
        except Exception as exc:
            log.warning("reddit r/%s failed: %s", name, exc)
            return None
    return None


def _published_at(entry: Any) -> datetime | None:
    for key in ("published_parsed", "updated_parsed"):
        parsed = entry.get(key)
        if parsed:
            return datetime(*parsed[:6], tzinfo=timezone.utc)
    return None
