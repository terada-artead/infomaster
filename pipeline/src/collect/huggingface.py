"""Hugging Face の trending モデルからの収集。

新モデルの登場はここに一番早く出る。認証不要。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

from ..models import Item
from ._http import cutoff, get_json

log = logging.getLogger(__name__)

API = "https://huggingface.co/api/models"


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
    for model in data:
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

        pipeline_tag = model.get("pipeline_tag", "")
        downloads = model.get("downloads", 0)
        items.append(
            Item(
                source="Hugging Face",
                source_kind="huggingface",
                tier="primary",
                title=f"New model on Hugging Face: {model_id}",
                url=f"https://huggingface.co/{model_id}",
                published_at=created,
                excerpt=(
                    f"task={pipeline_tag or 'n/a'}, likes={likes}, "
                    f"downloads={downloads}, tags={','.join(model.get('tags', [])[:8])}"
                ),
                engagement=likes,
            )
        )

    return items


def _created_at(model: dict[str, Any]) -> datetime | None:
    raw = model.get("createdAt") or model.get("lastModified")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
