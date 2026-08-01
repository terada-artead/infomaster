"""②名寄せ — 同じ出来事を報じている複数アイテムを1つに束ねる。

これが無いと「DeepSeek の新モデル」が公式・HN・Reddit・TechCrunch・Simon Willison
から5件並び、同じ内容の要約が5つ出る画面ができあがる。

URL 一致の重複は収集段で潰してあるので、ここで扱うのは
「別々の記事だが同じ出来事を指している」ケース。
"""

from __future__ import annotations

import logging

from pydantic import BaseModel, Field

from .llm import FAST_MODEL, parse
from .models import Cluster, ScoredItem

log = logging.getLogger(__name__)

SYSTEM = """\
あなたはニュース記事の名寄せを行うアシスタントです。

与えられたアイテム一覧から、**同じ出来事を指しているもの**をグループにまとめてください。

# 同じ出来事とみなすもの
- 同一のモデル/製品のリリースを、公式発表・報道・コミュニティ投稿がそれぞれ報じている
- 同一の資金調達/買収を複数のメディアが報じている
- あるリリースと、その直後の性能検証・ベンチマーク結果

# 特に注意：同一モデルの表記ゆれ
以下はすべて**同じモデルの同じリリース**として1つのグループにまとめてください。
- バージョンや日付の接尾辞が違うだけのもの
  例: DeepSeek-V4-Flash と DeepSeek-V4-Flash-0731 は同じ
- 量子化版・変換版・派生配布
  例: Kimi-K3 と Kimi-K3-GGUF、Kimi-K3-AWQ、TheBloke/Kimi-K3-GPTQ は同じ
- 同一モデルのサイズ違いが同時に公開された場合
  例: Solar-Open2-250B と Solar-Open2-30B が同日公開なら同じ
- 公式リポジトリと、それを紹介する記事・投稿・ベンチマーク報告

# 別の出来事とみなすもの
- 同じ企業の話でも、製品リリースと資金調達は別
- 同じジャンルの別モデル（A社の新モデルとB社の新モデル）
- 明確に世代が違うモデル（V3 と V4 は別）
- 関連はするが独立に読む価値がある話題

# 出力の決まり
- すべての index がちょうど1つのグループに属すること。取りこぼしも重複も不可。
- 1件だけのグループも作ってよい（むしろ多くはそうなる）。
- label はそのグループが何の出来事かを表す英語の短い句（60字以内）。
"""


class Group(BaseModel):
    indices: list[int] = Field(description="このグループに属するアイテムの index")
    label: str = Field(description="出来事を表す英語の短い句")


class Groups(BaseModel):
    groups: list[Group]


def cluster_items(scored: list[ScoredItem]) -> list[Cluster]:
    if not scored:
        return []
    # 数件しかないなら束ねる意味がないので API を呼ばない。
    if len(scored) <= 2:
        return [Cluster(items=[s], category=s.category, label=s.item.title) for s in scored]

    try:
        result = parse(
            model=FAST_MODEL,
            system=SYSTEM,
            user=_render(scored),
            output_format=Groups,
            max_tokens=4000,
        )
    except Exception as exc:
        # 名寄せに失敗しても、1件1クラスタとして先に進めればダイジェストは出る。
        log.error("名寄せに失敗しました。1件1クラスタとして続行します: %s", exc)
        return [
            Cluster(items=[s], category=s.category, label=s.item.title) for s in scored
        ]

    clusters: list[Cluster] = []
    assigned: set[int] = set()

    for group in result.groups:
        members = [
            scored[i] for i in group.indices if 0 <= i < len(scored) and i not in assigned
        ]
        if not members:
            continue
        assigned.update(group.indices)
        clusters.append(
            Cluster(
                items=members,
                # クラスタの分類は最高スコアのアイテムのものを採用する
                category=max(members, key=lambda m: m.score).category,
                label=group.label,
            )
        )

    # モデルが取りこぼした index を単独クラスタとして救済する
    for i, item in enumerate(scored):
        if i not in assigned:
            log.debug("名寄せから漏れた index=%d を単独クラスタにします", i)
            clusters.append(
                Cluster(items=[item], category=item.category, label=item.item.title)
            )

    log.info("名寄せ: %d 件 -> %d クラスタ", len(scored), len(clusters))
    return clusters


def limit_source_dominance(
    clusters: list[Cluster], source_kind: str, keep: int
) -> list[Cluster]:
    """特定のソースだけに由来するクラスタを上位 keep 件に絞る。

    Hugging Face のトレンドは毎日10件以上流れてくるため、放っておくと
    ダイジェストが「本日公開されたモデル一覧」になってしまう。

    他ソースの裏付けがあるクラスタ（＝実際に話題になっているリリース）は
    このソースだけに由来しないので、この制限を受けない。
    """
    dominated: list[Cluster] = []
    rest: list[Cluster] = []
    for cluster in clusters:
        if all(s.item.source_kind == source_kind for s in cluster.items):
            dominated.append(cluster)
        else:
            rest.append(cluster)

    if len(dominated) <= keep:
        return clusters

    dominated.sort(key=lambda c: -c.score)
    dropped = len(dominated) - keep
    log.info(
        "%s のみに由来するクラスタを %d 件から %d 件に制限しました（%d 件を除外）",
        source_kind,
        len(dominated),
        keep,
        dropped,
    )
    return rest + dominated[:keep]


def _render(scored: list[ScoredItem]) -> str:
    lines = ["以下のアイテムをグループにまとめてください。", ""]
    for i, s in enumerate(scored):
        lines.append(f"[{i}] {s.item.title}")
        lines.append(f"     source={s.item.source} category={s.category}")
        if s.item.excerpt:
            lines.append(f"     {s.item.excerpt[:200]}")
    return "\n".join(lines)
