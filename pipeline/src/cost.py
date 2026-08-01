"""API 消費額の記録と残高の見積もり。

Anthropic には残高を照会する API が無い（Admin API のコストレポートは
「使った額」であって残高ではなく、個人アカウントでは使えない）。
そこで、自分が呼んだ分は自分で数える。全ての API 呼び出しがこのパイプライン
経由である限り、これで正確に追える。

Console から手動で使った分（Workbench での試行など）は反映されないので、
残高がずれたら config/budget.yaml の credit_usd を実残高に合わせ直す。
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
# 実際の請求より多めに見積もる方向に倒しておくほうが安全。
PRICES: dict[str, dict[str, float]] = {
    "claude-haiku-4-5": {"input": 1.00, "output": 5.00},
    "claude-sonnet-5": {"input": 3.00, "output": 15.00},
    "claude-opus-5": {"input": 5.00, "output": 25.00},
}

# キャッシュ書き込みは入力の 1.25 倍、読み出しは 0.1 倍。
CACHE_WRITE_MULTIPLIER = 1.25
CACHE_READ_MULTIPLIER = 0.10


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
class BudgetStatus:
    credit_usd: float
    spent_usd: float
    run_cost_usd: float
    average_run_usd: float
    runs_remaining: int
    low: bool
    threshold_runs: int

    @property
    def remaining_usd(self) -> float:
        return max(0.0, self.credit_usd - self.spent_usd)

    def to_dict(self) -> dict[str, Any]:
        return {
            "credit_usd": round(self.credit_usd, 2),
            "spent_usd": round(self.spent_usd, 4),
            "remaining_usd": round(self.remaining_usd, 2),
            "run_cost_usd": round(self.run_cost_usd, 4),
            "average_run_usd": round(self.average_run_usd, 4),
            "runs_remaining": self.runs_remaining,
            "low": self.low,
            "threshold_runs": self.threshold_runs,
        }


def update_ledger(
    ledger: Ledger, budget: dict[str, Any], state_path: Path
) -> BudgetStatus:
    """今回の消費を状態ファイルに積み上げ、残高を見積もる。

    state_path には累計消費と過去の1回あたり消費を保存しておく。
    平均は直近 20 回分から取る（プロンプトを変えると単価が変わるため、
    古い実行を引きずらないようにする）。
    """
    state = _load_state(state_path)

    run_cost = ledger.total_usd
    history: list[float] = state.get("run_costs", [])
    history.append(run_cost)
    history = history[-20:]

    spent = state.get("spent_usd", 0.0) + run_cost
    credit = float(budget.get("credit_usd", 0.0))
    threshold_runs = int(budget.get("warn_when_runs_below", 10))

    average = sum(history) / len(history)
    remaining = max(0.0, credit - spent)
    runs_remaining = int(remaining / average) if average > 0 else 0

    state.update(
        {
            "spent_usd": spent,
            "run_costs": history,
            "last_run_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "last_run_usd": run_cost,
            "by_model": ledger.by_model(),
        }
    )
    _save_state(state_path, state)

    status = BudgetStatus(
        credit_usd=credit,
        spent_usd=spent,
        run_cost_usd=run_cost,
        average_run_usd=average,
        runs_remaining=runs_remaining,
        low=credit > 0 and runs_remaining <= threshold_runs,
        threshold_runs=threshold_runs,
    )

    log.info(
        "今回の消費 $%.4f / 累計 $%.4f / 残高 $%.2f / 残り約 %d 回",
        run_cost,
        spent,
        status.remaining_usd,
        runs_remaining,
    )
    if status.low:
        log.warning(
            "残高が少なくなっています。残り約 %d 回分（$%.2f）です。",
            runs_remaining,
            status.remaining_usd,
        )
    return status


def alert_message(status: BudgetStatus) -> str | None:
    """アプリに出す警告文。残高に余裕があれば None。"""
    if not status.low:
        return None
    if status.runs_remaining <= 0:
        return (
            f"APIクレジットが尽きました（残高 ${status.remaining_usd:.2f}）。"
            "補充しないと明日以降のダイジェストは生成されません。"
        )
    return (
        f"APIクレジット残高が少なくなっています。"
        f"残り約{status.runs_remaining}回分（${status.remaining_usd:.2f}）です。"
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
