"""①選別 — 収集した各アイテムに重要度スコアを付け、大半を捨てる。

このパイプラインで一番効くのがこの段。ここが緩いと
「日本語になっただけの情報の洪水」ができあがる。

読み手の関心は プロダクト/新モデル と ビジネス/業界動向 の2つ。
研究手法や論文、規制の詳細は対象外。
"""

from __future__ import annotations

import logging
from typing import Literal

from pydantic import BaseModel, Field

from .llm import FAST_MODEL, parse
from .models import Item, ScoredItem

log = logging.getLogger(__name__)

# 1回のリクエストで判定する件数。多すぎると判定が雑になり、少なすぎると
# システムプロンプトの再送コストが効いてくる。
BATCH_SIZE = 25

# このスコア未満は捨てる。
THRESHOLD = 45

SYSTEM = """\
あなたは、AI業界を追いかけている日本のソフトウェアエンジニア兼プロダクト担当者のために、\
海外のAI関連情報を選別するアシスタントです。

# 読み手が知りたいこと
1. プロダクト・新モデル
   新しいモデルのリリース、APIや機能のアップデート、価格改定、
   実際に使えるツールやサービスの登場、性能・速度・コストの実測比較。
2. ビジネス・業界動向
   資金調達、買収、提携、主要人物の異動、各社の戦略転換、市場シェアの動き、
   大企業での採用事例や導入規模の話。

# 読み手が知りたくないこと（低スコアにする）
- 論文・研究手法そのものの解説、新アーキテクチャの理論的な話
- 規制・法制度・訴訟の細かい経過（業界構造を変えるレベルなら例外的に可）
- AIと関係のない一般テックニュース
- 個人の感想、ポエム、議論のための議論、AGI論
- 「〜する10の方法」式のハウツー、初心者向けチュートリアル
- 特定モデルの単発の面白出力、ミーム
- 企業のマーケティング色が強いだけで新規性のない発表

# スコアの基準（0-100）
90-100: 業界の前提が変わるレベル。主要ラボの新モデル発表、大型買収。
70-89 : 実務に影響する。使えるモデル/機能の登場、価格改定、注目企業の資金調達。
50-69 : 知っておくと良い。中規模のリリース、業界の傾向を示す動き。
30-49 : 関連はするが読まなくても困らない。
0-29  : 対象外。研究寄り、無関係、ノイズ。

# 判断のヒント
- tier=primary は一次情報（企業公式）。同じ内容なら二次情報より高く評価する。
- Hugging Face のトレンドモデルは玉石混交。無名の派生モデルやマージモデルは低く、\
主要ラボや実績あるチームの新規モデルは高く。
- engagement（HNのポイント、Redditの順位、いいね数）は注目度の参考にするが、\
盛り上がっているだけで実質のない話題は上げない。
- 見出しが煽り気味でも中身が実質的なら評価する。逆も同様。
"""


class Judgement(BaseModel):
    index: int = Field(description="入力で提示されたアイテムの番号")
    score: int = Field(ge=0, le=100, description="重要度スコア")
    category: Literal[
        "new_model", "product", "tool", "funding", "business", "other"
    ] = Field(description="分類")
    reason: str = Field(description="そのスコアにした理由を日本語で20字以内")


class Judgements(BaseModel):
    judgements: list[Judgement]


def score_items(items: list[Item], threshold: int = THRESHOLD) -> list[ScoredItem]:
    """全アイテムを判定し、閾値を超えたものだけ返す。"""
    scored: list[ScoredItem] = []

    for start in range(0, len(items), BATCH_SIZE):
        batch = items[start : start + BATCH_SIZE]
        try:
            result = parse(
                model=FAST_MODEL,
                system=SYSTEM,
                user=_render(batch),
                output_format=Judgements,
                max_tokens=4000,
            )
        except Exception as exc:
            # 1バッチ落ちても他のバッチは活かす。
            log.error("選別バッチ %d-%d が失敗しました: %s", start, start + len(batch), exc)
            continue

        for judgement in result.judgements:
            if not 0 <= judgement.index < len(batch):
                log.warning("範囲外の index=%d を無視します", judgement.index)
                continue
            if judgement.score < threshold:
                continue
            scored.append(
                ScoredItem(
                    item=batch[judgement.index],
                    score=judgement.score,
                    category=judgement.category,
                    reason=judgement.reason,
                )
            )

    scored.sort(key=lambda s: -s.score)
    log.info(
        "選別: %d 件中 %d 件が閾値 %d を超えました", len(items), len(scored), threshold
    )
    return scored


def _render(batch: list[Item]) -> str:
    lines = [
        "以下の各アイテムを判定してください。"
        "すべてのアイテムについて、index を対応させて1件ずつ結果を返してください。",
        "",
    ]
    for i, item in enumerate(batch):
        lines.append(f"## [{i}]")
        lines.append(f"title: {item.title}")
        lines.append(f"source: {item.source} (tier={item.tier})")
        if item.engagement:
            lines.append(f"engagement: {item.engagement}")
        if item.excerpt:
            lines.append(f"excerpt: {item.excerpt}")
        lines.append("")
    return "\n".join(lines)
