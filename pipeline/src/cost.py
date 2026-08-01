"""API 消費額の記録。

Anthropic には残高を照会する API が無い（Admin API のコストレポートは
「使った額」であって残高ではなく、個人アカウントでは使えない）。
そこで、自分が呼んだ分は自分で数える。全ての API 呼び出しがこのパイプライン
経由である限り、これで正確に追える。

購入額をここで持たないのは、クレジットを買い足したときに
リポジトリを編集しないと更新できなくなるため。購入額はアプリ側が保持し、
このモジュールは「これまでにいくら使ったか」だけを公開する。
残高と残り回数の計算はアプリが行う。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

log = logging.getLogger(__name__)

# 100万トークンあたりの価格（USD）。
# Sonnet 5 には 2026-08-31 までの導入価格（$2/$10）があるが、
# ここは残高警告のための見積もりなので、高い側の通常価格で計算する。
# 実際の請求より多めに見積もる方向に倒しておくほうが、警告が遅れなくて安全。
PRICES: dict[str, dict[str, float]] = {
    "claude-haiku-4-5": {"input": 1.00, "output": 5.00},
    "claude-sonnet-5": {"input": 3.00, "output": 15.00},
    "claude-opus-5": {"input": 5.00, "output": 25.00},
}

# キャッシュ書き込みは入力の 1.25 倍、読み出しは 0.1 倍。
CACHE_WRITE_MULTIPLIER = 1.25
CACHE_READ_MULTIPLIER = 0.10

# 1回あたりの平均を取る対象。プロンプトを変えると単価が変わるので、
# 古い実行をいつまでも引きずらないように直近だけ見る。
AVERAGE_WINDOW = 20


@dataclass
class Usage:
    """1回の API 呼び出しのトークン使用量。"""

    model: str
    input_tokens: int = 0
    output_tokens: int = 0
    cache_creation_input_tokens: int = 0
    cache_read_input_tokens: int = 0

    def cost_usd(self) -> float:
        price = PRICES.get(self.model)
        if price is None:
            # 知らないモデルは最も高い価格で見積もる（警告を早める方向）
            log.warning("価格表に無いモデル %s。最高価格で見積もります。", self.model)
            price = max(PRICES.values(), key=lambda p: p["output"])
        per_input = price["input"] / 1_000_000
        per_output = price["output"] / 1_000_000
        return (
            self.input_tokens * per_input
            + self.output_tokens * per_output
            + self.cache_creation_input_tokens * per_input * CACHE_WRITE_MULTIPLIER
            + self.cache_read_input_tokens * per_input * CACHE_READ_MULTIPLIER
        )


@dataclass
class Ledger:
    """実行ごとの消費を積み上げる台帳。"""

    usages: list[Usage] = field(default_factory=list)

    def record(self, usage: Usage) -> None:
        self.usages.append(usage)

    @property
    def total_usd(self) -> float:
        return sum(u.cost_usd() for u in self.usages)

    def by_model(self) -> dict[str, float]:
        totals: dict[str, float] = {}
        for u in self.usages:
            totals[u.model] = totals.get(u.model, 0.0) + u.cost_usd()
        return totals


@dataclass
class SpendSummary:
    """アプリに渡す消費の要約。残高計算に必要なのはこれだけ。"""

    spent_usd: float
    run_cost_usd: float
    average_run_usd: float
    runs_recorded: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "spent_usd": round(self.spent_usd, 4),
            "run_cost_usd": round(self.run_cost_usd, 4),
            "average_run_usd": round(self.average_run_usd, 4),
            "runs_recorded": self.runs_recorded,
        }


def update_ledger(ledger: Ledger, state_path: Path) -> SpendSummary:
    """今回の消費を状態ファイルに積み上げ、要約を返す。"""
    state = _load_state(state_path)

    run_cost = ledger.total_usd
    history: list[float] = state.get("run_costs", [])
    history.append(run_cost)
    history = history[-AVERAGE_WINDOW:]

    spent = state.get("spent_usd", 0.0) + run_cost
    average = sum(history) / len(history) if history else 0.0

    state.update(
        {
            "spent_usd": spent,
            "run_costs": history,
            "runs_recorded": state.get("runs_recorded", 0) + 1,
            "last_run_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "last_run_usd": run_cost,
            "by_model": ledger.by_model(),
        }
    )
    _save_state(state_path, state)

    log.info(
        "今回の消費 $%.4f / 累計 $%.4f / 1回あたり平均 $%.4f",
        run_cost,
        spent,
        average,
    )
    return SpendSummary(
        spent_usd=spent,
        run_cost_usd=run_cost,
        average_run_usd=average,
        runs_recorded=state["runs_recorded"],
    )


def _load_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        # 壊れていても実行は止めない。累計はそこからやり直しになる。
        log.warning("消費記録 %s を読めませんでした: %s", path, exc)
        return {}


def _save_state(path: Path, state: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
