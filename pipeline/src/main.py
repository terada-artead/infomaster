"""日次ダイジェスト生成のエントリポイント。

  python -m src.main --collect-only   # 収集だけ。APIキー不要。ソースの疎通確認に使う
  python -m src.main                  # フルパイプライン。ANTHROPIC_API_KEY が必要
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import yaml

from .collect import collect_all

JST = timezone(timedelta(hours=9))

ROOT = Path(__file__).resolve().parent.parent
REPO_ROOT = ROOT.parent
DEFAULT_CONFIG = ROOT / "config" / "sources.yaml"
DEFAULT_OUT = REPO_ROOT / "digests"

log = logging.getLogger("infomaster")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="infomaster")
    parser.add_argument(
        "--collect-only",
        action="store_true",
        help="収集だけ行って結果を表示する（Anthropic API を呼ばない）",
    )
    parser.add_argument(
        "--config", type=Path, default=DEFAULT_CONFIG, help="ソース定義 YAML"
    )
    parser.add_argument(
        "--out", type=Path, default=DEFAULT_OUT, help="ダイジェスト JSON の出力先"
    )
    parser.add_argument(
        "--lookback-hours",
        type=int,
        default=26,
        help="何時間前まで遡って収集するか（既定26: 実行間隔の境界を重ねて取りこぼしを防ぐ）",
    )
    parser.add_argument(
        "--max-items",
        type=int,
        default=20,
        help="ダイジェストに載せる最大件数",
    )
    parser.add_argument(
        "--max-huggingface",
        type=int,
        default=3,
        help="他ソースの裏付けが無い Hugging Face 由来の項目の上限",
    )
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)-7s %(name)s: %(message)s",
        stream=sys.stderr,
    )

    config = yaml.safe_load(args.config.read_text(encoding="utf-8"))

    log.info("収集を開始します (lookback=%dh)", args.lookback_hours)
    report = collect_all(config, lookback_hours=args.lookback_hours)
    log.info("収集完了: %d 件", len(report.items))

    for source, count in sorted(report.counts.items(), key=lambda kv: -kv[1]):
        log.info("  %-16s %4d", source, count)
    for source, error in report.failures.items():
        log.warning("  %-16s FAILED: %s", source, error)

    if args.collect_only:
        _print_collect_summary(report)
        return 0

    # LLM を使う段はここで初めて読み込む（--collect-only を APIキー無しで通すため）
    from . import history as history_module
    from .cluster import cluster_items, limit_source_dominance
    from .cost import update_ledger
    from .llm import LEDGER
    from .output import write_digest
    from .score import score_items
    from .write import write_digest_items

    # 昨日までに配信した分を除く。同じ話題が何日も並ぶのを防ぐ。
    history_path = args.out / "published.json"
    history = history_module.load(history_path)
    fresh = history_module.drop_published(report.items, history)

    scored = score_items(fresh, published_titles=history.recent_titles())
    log.info("選別完了: %d 件が通過", len(scored))

    clusters = cluster_items(scored)
    log.info("名寄せ完了: %d クラスタ", len(clusters))

    # Hugging Face のトレンドは件数が多く、放っておくとダイジェストが
    # 「本日公開されたモデル一覧」になってしまう。他ソースの裏付けが無いものは
    # 上位のみ残す（裏付けのあるリリースはこの制限を受けない）。
    clusters = limit_source_dominance(
        clusters, source_kind="huggingface", keep=args.max_huggingface
    )

    clusters = sorted(clusters, key=lambda c: -c.score)[: args.max_items]

    digest = write_digest_items(
        clusters,
        date=datetime.now(JST),
        stats={
            "collected": len(report.items),
            "fresh": len(fresh),
            "selected": len(scored),
        },
    )
    history_module.save(history_path, history, digest)

    # 消費額を積み上げて公開する。残高と残り回数の計算はアプリ側が行う
    # （購入額をアプリが持つため。リポジトリを編集せずに更新できる）。
    # 生成が全部終わってから計算するので、ここまでの全呼び出しが対象になる。
    digest.budget = update_ledger(LEDGER, args.out / "usage.json").to_dict()

    path = write_digest(digest, args.out)
    log.info("出力しました: %s (%d 件)", path, len(digest.items))
    return 0


def _print_collect_summary(report) -> None:
    """収集結果を人間が読める形で標準出力に出す。疎通確認用。"""
    by_source: dict[str, list] = {}
    for item in report.items:
        by_source.setdefault(item.source, []).append(item)

    print(f"\n=== 収集結果: {len(report.items)} 件 / {len(by_source)} ソース ===\n")
    for source, items in sorted(by_source.items(), key=lambda kv: -len(kv[1])):
        print(f"[{source}] {len(items)} 件")
        for item in sorted(items, key=lambda i: -i.engagement)[:3]:
            marker = f" ({item.engagement})" if item.engagement else ""
            print(f"    - {item.title[:90]}{marker}")
        print()

    if report.failures:
        print("=== 取得に失敗したソース ===")
        for source, error in report.failures.items():
            print(f"  {source}: {error}")
        print()

    # ソース疎通の確認が目的なので、JSON でも吐いて後から中身を見られるようにする
    dump = ROOT / "collected.json"
    dump.write_text(
        json.dumps([i.to_dict() for i in report.items], ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"生データを {dump} に保存しました。")


if __name__ == "__main__":
    raise SystemExit(main())
