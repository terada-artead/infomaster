"""RSS / Atom フィードからの収集。

フィードは黙って URL が変わったり 404 になったりするので、
1本ずつ独立に失敗させて残りを続行する。
"""

from __future__ import annotations

import logging
import re
from datetime import datetime, timezone
from typing import Any

import feedparser

from ..models import Item
from ._http import client, clip, cutoff

log = logging.getLogger(__name__)

_TAG = re.compile(r"<[^>]+>")


def collect(config: list[dict[str, Any]], lookback_hours: int) -> list[Item]:
    since = cutoff(lookback_hours)
    items: list[Item] = []

    with client() as http:
        for feed_config in config:
            name = feed_config["name"]
            try:
                resp = http.get(feed_config["url"])
                resp.raise_for_status()
                parsed = feedparser.parse(resp.content)
            except Exception as exc:
                log.warning("rss feed %s failed: %s", name, exc)
                continue

            if parsed.bozo and not parsed.entries:
                log.warning("rss feed %s returned no parseable entries", name)
                continue

            for entry in parsed.entries:
                published = _published_at(entry)
                # 日付が取れないフィードもある。その場合は落とさず通す
                # （フィードの先頭にあるものは新しいはずなので）。
                if published is not None and published < since:
                    continue
                url = entry.get("link")
                title = entry.get("title")
                if not url or not title:
                    continue
                items.append(
                    Item(
                        source=name,
                        source_kind="rss",
                        tier=feed_config.get("tier", "secondary"),
                        title=title.strip(),
                        url=url,
                        published_at=published,
                        excerpt=clip(_summary(entry)),
                    )
                )

    return items


def _published_at(entry: Any) -> datetime | None:
    for key in ("published_parsed", "updated_parsed"):
        parsed = entry.get(key)
        if parsed:
            return datetime(*parsed[:6], tzinfo=timezone.utc)
    return None


def _summary(entry: Any) -> str:
    raw = entry.get("summary") or ""
    if not raw and entry.get("content"):
        raw = entry["content"][0].get("value", "")
    return _TAG.sub(" ", raw)
