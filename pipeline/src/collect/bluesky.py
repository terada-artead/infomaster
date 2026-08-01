"""Bluesky からの収集。

app.bsky.feed.searchPosts は認証必須になったため、認証不要で使える
app.bsky.feed.getAuthorFeed で指定アカウントのタイムラインを読む。

そのため「誰をフォローするか」が収集品質をそのまま決める。
config の accounts に実在確認済みのハンドルだけを並べること。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from ..models import Item
from ._http import clip, cutoff, get_json

log = logging.getLogger(__name__)

API = "https://public.api.bsky.app/xrpc/app.bsky.feed.getAuthorFeed"


def collect(config: dict[str, Any], lookback_hours: int) -> list[Item]:
    since = cutoff(lookback_hours)
    min_likes = config.get("min_likes", 30)
    per_account = config.get("per_account", 20)
    items: dict[str, Item] = {}

    for handle in config.get("accounts", []):
        try:
            data = get_json(
                API,
                params={
                    "actor": handle,
                    "limit": per_account,
                    # 返信を除く（会話の断片は要約材料にならない）
                    "filter": "posts_no_replies",
                },
            )
        except Exception as exc:
            log.warning("bluesky @%s failed: %s", handle, exc)
            continue

        for entry in data.get("feed", []):
            post = entry.get("post", {})
            # リポストは元投稿を別途拾える可能性があるので数えない
            if entry.get("reason"):
                continue

            likes = post.get("likeCount", 0)
            if likes < min_likes:
                continue
            created = _created_at(post)
            if created is None or created < since:
                continue

            text = (post.get("record", {}).get("text") or "").strip()
            if not text:
                continue

            author = post.get("author", {}).get("handle", handle)
            rkey = post.get("uri", "").rsplit("/", 1)[-1]

            item = Item(
                source=f"Bluesky @{author}",
                source_kind="bluesky",
                tier="community",
                # 投稿に見出しは無いので冒頭をタイトル代わりにする
                title=clip(text, 140),
                url=f"https://bsky.app/profile/{author}/post/{rkey}",
                published_at=created,
                excerpt=clip(text),
                engagement=likes + post.get("repostCount", 0),
            )
            items.setdefault(item.id, item)

    return list(items.values())


def _created_at(post: dict[str, Any]) -> datetime | None:
    raw = post.get("record", {}).get("createdAt") or post.get("indexedAt")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
