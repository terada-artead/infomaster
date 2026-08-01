"""Hacker News（Algolia API）からの収集。認証不要・無料。"""

from __future__ import annotations

import logging
from typing import Any

from ..models import Item
from ._http import clip, cutoff, get_json, utc

log = logging.getLogger(__name__)

API = "https://hn.algolia.com/api/v1/search_by_date"


def collect(config: dict[str, Any], lookback_hours: int) -> list[Item]:
    since = cutoff(lookback_hours)
    min_points = config.get("min_points", 80)
    keywords = config.get("keywords", [])

    items: dict[str, Item] = {}

    for keyword in keywords:
        try:
            data = get_json(
                API,
                params={
                    "query": keyword,
                    "tags": "story",
                    "numericFilters": (
                        f"created_at_i>{int(since.timestamp())},points>{min_points}"
                    ),
                    "hitsPerPage": 50,
                },
            )
        except Exception as exc:
            log.warning("hn query %r failed: %s", keyword, exc)
            continue

        for hit in data.get("hits", []):
            # Ask HN など外部 URL を持たない投稿は HN 側のページを原文扱いにする
            object_id = hit.get("objectID")
            url = hit.get("url") or f"https://news.ycombinator.com/item?id={object_id}"
            title = hit.get("title")
            if not title:
                continue
            item = Item(
                source="Hacker News",
                source_kind="hackernews",
                tier="community",
                title=title.strip(),
                url=url,
                published_at=utc(hit["created_at_i"]),
                excerpt=clip(hit.get("story_text") or ""),
                engagement=hit.get("points", 0),
                discussion_url=f"https://news.ycombinator.com/item?id={object_id}",
            )
            # 複数キーワードに引っかかった場合は1件に畳む
            items.setdefault(item.id, item)

    return list(items.values())
