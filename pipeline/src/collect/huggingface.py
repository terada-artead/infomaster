"""Hugging Face の trending モデルからの収集。

新モデルの登場はここに一番早く出る。認証不要。

API のメタデータ（いいね数・DL数・タグ）だけでは執筆段に書く材料が無く、
「XがモデルYを公開した」以上のことが書けない要約になってしまうため、
上位モデルについてはモデルカード（README）の冒頭も取得している。
"""

from __future__ import annotations

import logging
import re
from datetime import datetime
from typing import Any

from ..models import Item
from ._http import client, clip, cutoff, get_json

log = logging.getLogger(__name__)

API = "https://huggingface.co/api/models"

# モデルカードを取りに行く上位件数。全件取ると無駄が多いので trending 上位だけ。
CARD_FETCH_LIMIT = 15

# README 先頭の YAML フロントマター（ライセンスやタグの機械可読メタデータ）
_FRONTMATTER = re.compile(r"^---\s*\n.*?\n---\s*\n", re.DOTALL)
# 画像・バッジ・見出し記号など、要約の材料にならない記法
_IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
_LINK = re.compile(r"\[([^\]]+)\]\([^)]*\)")
_HEADING = re.compile(r"^#{1,6}\s*", re.MULTILINE)
_HTML = re.compile(r"<[^>]+>")


def collect(config: dict[str, Any], lookback_hours: int) -> list[Item]:
    since = cutoff(lookback_hours)
    min_likes = config.get("min_likes", 20)

    data = get_json(
        API,
        params={
            "sort": "trendingScore",
            "direction": -1,
            "limit": config.get("limit", 30),
            "full": "true",
        },
    )

    items: list[Item] = []
    for rank, model in enumerate(data):
        model_id = model.get("modelId") or model.get("id")
        if not model_id:
            continue
        likes = model.get("likes", 0)
        created = _created_at(model)
        # trending でも古いモデルが居座ることがあるので、新着だけに絞る。
        # ただし急に伸びた既存モデルは likes 閾値の3倍で拾い直す。
        is_new = created is not None and created >= since
        if not is_new and likes < min_likes * 3:
            continue
        if likes < min_likes:
            continue

        summary = (
            f"task={model.get('pipeline_tag') or 'n/a'}, likes={likes}, "
            f"downloads={model.get('downloads', 0)}, "
            f"tags={','.join(model.get('tags', [])[:8])}"
        )
        # 上位モデルだけモデルカードを読みに行く（下位は要約されるまでもなく落ちる）
        card = _model_card(model_id) if rank < CARD_FETCH_LIMIT else ""
        if card:
            summary = f"{summary}\n{card}"

        items.append(
            Item(
                source="Hugging Face",
                source_kind="huggingface",
                tier="primary",
                title=f"New model on Hugging Face: {model_id}",
                url=f"https://huggingface.co/{model_id}",
                published_at=created,
                excerpt=summary,
                engagement=likes,
            )
        )

    return items


def _model_card(model_id: str) -> str:
    """README.md の説明部分を取り出す。取得できなければ空文字を返す。"""
    url = f"https://huggingface.co/{model_id}/raw/main/README.md"
    try:
        with client() as http:
            resp = http.get(url)
            if resp.status_code != 200:
                return ""
            raw = resp.text
    except Exception as exc:
        log.debug("model card %s failed: %s", model_id, exc)
        return ""

    body = _FRONTMATTER.sub("", raw)
    body = _IMAGE.sub("", body)
    body = _LINK.sub(r"\1", body)
    body = _HTML.sub(" ", body)
    body = _HEADING.sub("", body)
    return clip(body, 900)


def _created_at(model: dict[str, Any]) -> datetime | None:
    raw = model.get("createdAt") or model.get("lastModified")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
