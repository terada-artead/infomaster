"""③執筆 — クラスタから日本語ダイジェストを生成する。

成果物の質がそのまま決まる段なので、ここだけ Sonnet 5 を使う。

出力トークンを抑えるため、重要度によって書く量を変えている:
  high   … 見出し + 2〜3文の要約（何が起きて、なぜ効くのか）
  medium … 見出し + 1文
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Literal

from pydantic import BaseModel, Field

from .llm import WRITER_MODEL, parse
from .models import Cluster, Digest, DigestItem

log = logging.getLogger(__name__)

# このスコア以上を high 扱いにする。
HIGH_SCORE = 70
# high の上限。多すぎると「全部重要」になって選別の意味が消える。
MAX_HIGH = 10

SYSTEM = """\
あなたは、AI業界を追う日本のソフトウェアエンジニア兼プロダクト担当者に向けて、\
海外のAI関連ニュースを日本語で毎朝ダイジェストにして届ける編集者です。

# 書き方
- 事実ベースで書く。原文に無い数字・評価・推測を足さない。
- 誇張しない。「衝撃の」「革命的」といった煽り表現は使わない。
- 読み手はエンジニアなので、専門用語はそのまま使ってよい（LLM、推論、ファインチューニング等）。\
ただし固有名詞以外の英語はできるだけ日本語にする。
- 「〜のようです」「〜と思われます」のような曖昧な語尾は、原文が断定している限り使わない。
- 原文が伝聞・未確認情報である場合はその旨を明示する（「〜と報じられている」）。

# 各項目の書き方
title_ja: 何が起きたかが一読で分かる見出し。25〜40字程度。体言止めでよい。
summary_ja:
  importance=high   … 2〜3文。「何が起きたか」に加えて「実務にどう効くか」を必ず含める。
  importance=medium … 1文。何が起きたかだけ。

# 今日の3行 (highlights)
その日のダイジェスト全体を3行で要約する。各行は「・」を付けず、40字以内。
最も重要な3件を選び、それぞれ1行で書く。項目が3件未満なら、その件数だけ書く。
"""


class WrittenItem(BaseModel):
    index: int = Field(description="入力で提示されたクラスタの番号")
    title_ja: str = Field(description="日本語の見出し")
    summary_ja: str = Field(description="日本語の要約")


class Written(BaseModel):
    highlights: list[str] = Field(description="今日の3行。最大3件。")
    items: list[WrittenItem]


def write_digest_items(
    clusters: list[Cluster], date: datetime, stats: dict[str, int]
) -> Digest:
    ordered = sorted(clusters, key=lambda c: -c.score)
    importance = _assign_importance(ordered)

    if not ordered:
        log.warning("クラスタが0件です。空のダイジェストを出力します。")
        return Digest(
            date=date.strftime("%Y-%m-%d"),
            generated_at=date.isoformat(timespec="seconds"),
            stats={**stats, "published": 0},
            highlights=[],
            items=[],
        )

    written = parse(
        model=WRITER_MODEL,
        system=SYSTEM,
        user=_render(ordered, importance),
        output_format=Written,
        # high 1件あたり日本語で200〜300トークン、medium は 80 程度を見込む。
        max_tokens=max(4000, len(ordered) * 400),
        effort="medium",
    )

    by_index = {w.index: w for w in written.items}
    items: list[DigestItem] = []

    for i, cluster in enumerate(ordered):
        w = by_index.get(i)
        if w is None:
            log.warning("クラスタ %d の執筆結果が欠落しています。スキップします。", i)
            continue
        items.append(
            DigestItem(
                id=cluster.primary.item.id,
                importance=importance[i],
                category=cluster.category,
                title_ja=w.title_ja,
                summary_ja=w.summary_ja,
                sources=_sources(cluster),
            )
        )

    return Digest(
        date=date.strftime("%Y-%m-%d"),
        generated_at=date.isoformat(timespec="seconds"),
        stats={**stats, "published": len(items)},
        highlights=written.highlights[:3],
        items=items,
    )


def _assign_importance(ordered: list[Cluster]) -> list[Literal["high", "medium"]]:
    result: list[Literal["high", "medium"]] = []
    high_count = 0
    for cluster in ordered:
        if (
            cluster.score >= HIGH_SCORE
            and high_count < MAX_HIGH
            and not _community_only(cluster)
        ):
            result.append("high")
            high_count += 1
        else:
            result.append("medium")
    return result


def _community_only(cluster: Cluster) -> bool:
    """裏付けがコミュニティ投稿だけかどうか。

    Reddit や Hacker News の投稿1件だけを根拠にした未確認情報を最上位に置くと、
    噂と確定情報が同じ見た目で並んでしまう。公式発表や報道の裏付けが無いものは
    medium 止まりにする。
    """
    return all(s.item.tier == "community" for s in cluster.items)


def _sources(cluster: Cluster) -> list[dict[str, str]]:
    """クラスタ内の参照元を、一次情報を先頭にして並べる。"""
    ordered = sorted(cluster.items, key=lambda s: (s.item.tier != "primary", -s.score))
    seen: set[str] = set()
    sources: list[dict[str, str]] = []
    for scored in ordered:
        item = scored.item
        if item.source in seen:
            continue
        seen.add(item.source)
        sources.append({"name": item.source, "url": item.url})
    return sources


def _render(
    clusters: list[Cluster], importance: list[Literal["high", "medium"]]
) -> str:
    lines = [
        "以下の各クラスタについて、日本語の見出しと要約を書いてください。",
        "importance の指定に従って要約の長さを変えてください。",
        "すべてのクラスタについて index を対応させて結果を返してください。",
        "",
    ]
    for i, cluster in enumerate(clusters):
        lines.append(f"## [{i}] importance={importance[i]} category={cluster.category}")
        lines.append(f"topic: {cluster.label}")
        for scored in cluster.items:
            item = scored.item
            lines.append(f"- ({item.source}) {item.title}")
            if item.excerpt:
                lines.append(f"  {item.excerpt}")
        lines.append("")
    return "\n".join(lines)
