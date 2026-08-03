"""ワークフローの予定時刻と実際の開始時刻のずれを見る（遅延の実態調査用）。"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import httpx

REPO = "terada-artead/infomaster"
JST = timezone(timedelta(hours=9))

runs = httpx.get(
    f"https://api.github.com/repos/{REPO}/actions/runs",
    params={"per_page": 20},
    timeout=30,
).json()

print(f"{'#':>3}  {'trigger':<18} {'開始(JST)':<17} {'結果':<10} 予定からの遅れ")
for run in runs.get("workflow_runs", []):
    started = datetime.fromisoformat(run["run_started_at"].replace("Z", "+00:00"))
    local = started.astimezone(JST)

    delay = ""
    if run["event"] == "schedule":
        # 予定は 20:30 UTC（= 05:30 JST）
        scheduled = started.replace(hour=20, minute=30, second=0, microsecond=0)
        if started < scheduled:
            scheduled -= timedelta(days=1)
        delay = f"+{int((started - scheduled).total_seconds() // 60)} 分"

    print(
        f"{run['run_number']:>3}  {run['event']:<18} "
        f"{local.strftime('%m-%d %H:%M:%S'):<17} {str(run['conclusion']):<10} {delay}"
    )
