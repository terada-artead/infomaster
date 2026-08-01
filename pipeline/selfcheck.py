"""APIキー無しで検証できる範囲の自己チェック。

構造化出力のスキーマが生成できるか、重要度の割り当てが意図どおりかを確認する。
LLM を呼ぶ部分はここでは検証できない。
"""

import importlib

for name in [
    "src.models",
    "src.llm",
    "src.score",
    "src.cluster",
    "src.write",
    "src.output",
    "src.main",
    "src.collect",
]:
    importlib.import_module(name)
print("imports OK")

from src.cluster import Groups
from src.score import Judgements
from src.write import MAX_HIGH, Written, _assign_importance

for model in (Judgements, Groups, Written):
    schema = model.model_json_schema()
    print(f"{model.__name__:12} schema fields = {list(schema.get('properties', {}))}")

from src.models import Cluster, Item, ScoredItem


def cluster_with(score: int) -> Cluster:
    item = Item(
        source="s",
        source_kind="rss",
        tier="primary",
        title="t",
        url=f"http://example.com/{score}",
    )
    return Cluster(
        items=[ScoredItem(item=item, score=score, category="product")],
        category="product",
    )


scores = [95, 90, 85, 80, 78, 76, 74, 72, 71, 70, 69, 68, 50]
importance = _assign_importance([cluster_with(s) for s in scores])
print("scores    :", scores)
print("importance:", importance)
assert importance.count("high") == MAX_HIGH, importance
assert importance[10] == "medium", "70未満は medium になるはず"
print("importance assignment OK")

# 重複排除: 同一 URL が一次情報として畳まれ、engagement は最大値が残ること
from src.collect import dedupe

a = Item(
    source="TechCrunch",
    source_kind="rss",
    tier="secondary",
    title="x",
    url="http://example.com/a",
    engagement=10,
)
b = Item(
    source="OpenAI News",
    source_kind="rss",
    tier="primary",
    title="x",
    url="http://example.com/a",
    engagement=3,
)
merged = dedupe([a, b])
assert len(merged) == 1, merged
assert merged[0].tier == "primary", "一次情報が残るはず"
assert merged[0].engagement == 10, "engagement は最大値が残るはず"
print("dedupe OK")

# クラスタ代表の選択: 一次情報がスコアで負けていても代表になること
low_primary = ScoredItem(
    item=Item(
        source="OpenAI News",
        source_kind="rss",
        tier="primary",
        title="official",
        url="http://example.com/p",
    ),
    score=60,
    category="new_model",
)
high_secondary = ScoredItem(
    item=Item(
        source="Hacker News",
        source_kind="hackernews",
        tier="community",
        title="discussion",
        url="http://example.com/h",
    ),
    score=95,
    category="new_model",
)
cluster = Cluster(items=[high_secondary, low_primary], category="new_model")
assert cluster.primary is low_primary, "一次情報が代表になるはず"
assert cluster.score == 95, "クラスタのスコアは最高値"
print("cluster primary/score OK")

print("\nすべて通過しました。")
