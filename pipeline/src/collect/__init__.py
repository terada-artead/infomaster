"""各ソースからの収集。

収集は「1ソースの失敗が全体を止めない」ことを最優先にしている。
どのソースが何件返したか / どこで落ちたかは collect_all の戻り値で確認できる。
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Any, Callable

from ..models import Item
from . import bluesky, github_releases, hackernews, huggingface, reddit, rss

log = logging.getLogger(__name__)

# (設定キー, 収集関数) の並び。設定に該当キーが無ければそのソースは飛ばす。
COLLECTORS: list[tuple[str, Callable[[Any, int], list[Item]]]] = [
    ("rss", rss.collect),
    ("hacker_news", hackernews.collect),
    ("reddit", reddit.collect),
    ("bluesky", bluesky.collect),
    ("huggingface", huggingface.collect),
    ("github_releases", github_releases.collect),
]


@dataclass
class CollectReport:
    items: list[Item]
    counts: dict[str, int]
    failures: dict[str, str]


def collect_all(config: dict[str, Any], lookback_hours: int = 26) -> CollectReport:
    """設定に書かれた全ソースから収集する。

    lookback_hours を 24 ではなく 26 にしているのは、前回実行との境界で
    取りこぼしが出ないようにするため。重複は後段の名寄せで吸収される。
    """
    items: list[Item] = []
    counts: dict[str, int] = {}
    failures: dict[str, str] = {}

    for key, collector in COLLECTORS:
        if key not in config:
            continue
        try:
            collected = collector(config[key], lookback_hours)
        except Exception as exc:  # 1ソースの失敗で全体を止めない
            log.warning("collector %s failed: %s", key, exc)
            failures[key] = str(exc)
            counts[key] = 0
            continue
        counts[key] = len(collected)
        items.extend(collected)
        log.info("collected %d items from %s", len(collected), key)

    return CollectReport(items=dedupe(items), counts=counts, failures=failures)


def dedupe(items: list[Item]) -> list[Item]:
    """URL が同一のものを1件に畳む。engagement は最大値を残す。

    同じ記事が RSS と HN の両方から来ることは普通にあるので、ここで軽く潰しておく。
    「同じ出来事を報じた別記事」の名寄せは後段の LLM が担当する。
    """
    by_id: dict[str, Item] = {}
    for item in items:
        existing = by_id.get(item.id)
        if existing is None:
            by_id[item.id] = item
            continue
        # 一次情報を優先して残し、engagement は高いほうを採る
        if existing.tier != "primary" and item.tier == "primary":
            item.engagement = max(item.engagement, existing.engagement)
            by_id[item.id] = item
        else:
            existing.engagement = max(existing.engagement, item.engagement)
    return list(by_id.values())
