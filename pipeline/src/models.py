"""パイプライン各段で受け渡すデータ構造。"""

from __future__ import annotations

import hashlib
from dataclasses import dataclass, field, asdict
from datetime import datetime
from typing import Any, Literal

Tier = Literal["primary", "secondary", "community"]
Importance = Literal["high", "medium"]
Category = Literal["new_model", "product", "tool", "funding", "business", "other"]


@dataclass
class Item:
    """収集した生のアイテム1件。"""

    source: str
    source_kind: str  # rss / hackernews / reddit / bluesky / huggingface / github
    tier: Tier
    title: str
    url: str
    published_at: datetime | None = None
    excerpt: str = ""
    # 議論の盛り上がり。HN のポイント、Reddit のスコア、Bluesky のいいね数など。
    engagement: int = 0
    # コメントページなど、原文とは別に参照したい URL
    discussion_url: str | None = None

    @property
    def id(self) -> str:
        return hashlib.sha256(self.url.encode("utf-8")).hexdigest()[:12]

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["id"] = self.id
        d["published_at"] = self.published_at.isoformat() if self.published_at else None
        return d


@dataclass
class ScoredItem:
    """①選別を通過したアイテム。"""

    item: Item
    score: int  # 0-100
    category: Category
    reason: str = ""


@dataclass
class Cluster:
    """②名寄せで束ねた、同一ニュースを指す複数アイテム。"""

    items: list[ScoredItem]
    category: Category
    label: str = ""  # クラスタの英語見出し（執筆段への入力）

    @property
    def score(self) -> int:
        """クラスタの重要度は構成アイテムの最高スコア。"""
        return max(i.score for i in self.items)

    @property
    def primary(self) -> ScoredItem:
        """代表アイテム。一次情報を優先し、次にスコア順。"""
        return sorted(
            self.items,
            key=lambda i: (i.item.tier != "primary", -i.score),
        )[0]


@dataclass
class DigestItem:
    """③執筆が生成した、配信される1件。"""

    id: str
    importance: Importance
    category: Category
    title_ja: str
    summary_ja: str
    sources: list[dict[str, str]] = field(default_factory=list)


@dataclass
class Digest:
    """1日分の配信データ。これがそのまま JSON になる。"""

    date: str
    generated_at: str
    stats: dict[str, int]
    highlights: list[str]
    items: list[DigestItem]
    # これまでの API 消費額。残高と残り回数の計算はアプリ側が行う
    # （購入額はアプリが保持しており、ここでは分からないため）。
    budget: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "date": self.date,
            "generated_at": self.generated_at,
            "stats": self.stats,
            "highlights": self.highlights,
            "items": [asdict(i) for i in self.items],
        }
        if self.budget:
            payload["budget"] = self.budget
        return payload
