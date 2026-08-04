"""配信済みトピックの記録と、翌日以降の重複排除。

同じ話題が何日も続けて載るのを防ぐ。とくに Hugging Face のトレンドは
一度上がったモデルが数日居座るため、これが無いと毎朝同じモデルが並ぶ。

二段構えにしている:
  URL 一致   … 完全に同じ記事・同じモデルページ。ここで機械的に落とす
  話題の重複 … 別記事だが同じ出来事の続報。選別段のプロンプトに
              配信済みの見出しを渡して、モデルに判断させる
"""

from __future__ import annotations

import json
import logging
from dataclasses import asdict, dataclass, field
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

from .models import Digest, Item

log = logging.getLogger(__name__)

# 何日分の配信履歴を持つか。長すぎると「久しぶりの続報」まで落ちる。
RETENTION_DAYS = 7

# 選別段に渡す配信済み見出しの対象日数。直近だけで十分効く。
CONTEXT_DAYS = 3


@dataclass
class PublishedItem:
    """過去に配信した1件。URL は出典すべてを持つ。"""

    date: str
    id: str
    title_ja: str
    urls: list[str] = field(default_factory=list)


@dataclass
class History:
    items: list[PublishedItem] = field(default_factory=list)

    def urls(self) -> set[str]:
        """配信済みの URL 全部。収集結果を機械的に落とすのに使う。"""
        return {url for item in self.items for url in item.urls}

    def recent_titles(self, days: int = CONTEXT_DAYS) -> list[str]:
        """直近の配信見出し。選別段のプロンプトに渡す。"""
        cutoff = (date.today() - timedelta(days=days)).isoformat()
        return [i.title_ja for i in self.items if i.date >= cutoff]

    def to_dict(self) -> dict[str, Any]:
        return {"items": [asdict(i) for i in self.items]}


def load(path: Path) -> History:
    if not path.exists():
        return History()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        # 壊れていても実行は止めない。重複排除が一度効かなくなるだけ。
        log.warning("配信履歴 %s を読めませんでした: %s", path, exc)
        return History()
    return History(
        items=[PublishedItem(**i) for i in raw.get("items", [])]
    )


def save(path: Path, history: History, digest: Digest) -> None:
    """今回配信した分を記録し、古い分を捨てる。"""
    today = digest.date
    # 同じ日に再実行された場合は、その日の記録を置き換える
    kept = [i for i in history.items if i.date != today]

    kept.extend(
        PublishedItem(
            date=today,
            id=item.id,
            title_ja=item.title_ja,
            urls=[s["url"] for s in item.sources],
        )
        for item in digest.items
    )

    cutoff = (
        datetime.fromisoformat(today).date() - timedelta(days=RETENTION_DAYS)
    ).isoformat()
    kept = [i for i in kept if i.date >= cutoff]

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(History(items=kept).to_dict(), ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    log.info("配信履歴を更新しました（%d 件を保持）", len(kept))


def drop_published(items: list[Item], history: History) -> list[Item]:
    """すでに配信した URL を持つアイテムを落とす。"""
    published = history.urls()
    if not published:
        return items

    kept = [i for i in items if i.url not in published]
    dropped = len(items) - len(kept)
    if dropped:
        log.info("配信済みの URL に一致した %d 件を除外しました", dropped)
    return kept
