"""GitHub Actions の実行状況を確認する（公開リポジトリなので認証不要）。

  python checkrun.py          … 現在の状態を1回表示
  python checkrun.py --wait   … 完了するまで待ってから結果を表示
"""

from __future__ import annotations

import sys
import time

import httpx

REPO = "terada-artead/infomaster"
API = f"https://api.github.com/repos/{REPO}/actions/runs"
RAW = f"https://raw.githubusercontent.com/{REPO}/main/digests/latest.json"

POLL_SECONDS = 20
MAX_WAIT_SECONDS = 420


def latest_run() -> dict | None:
    resp = httpx.get(API, params={"per_page": 1}, timeout=20)
    resp.raise_for_status()
    runs = resp.json().get("workflow_runs", [])
    return runs[0] if runs else None


def show(run: dict) -> None:
    print(f"#{run['run_number']}  {run['name']}")
    print(f"   status     = {run['status']}")
    print(f"   conclusion = {run['conclusion']}")
    print(f"   {run['html_url']}")


def show_digest() -> None:
    resp = httpx.get(RAW, timeout=20)
    print(f"\nlatest.json: HTTP {resp.status_code}")
    if resp.status_code != 200:
        print("  ダイジェストはまだ生成されていません。")
        return
    data = resp.json()
    print(f"  date       = {data.get('date')}")
    print(f"  stats      = {data.get('stats')}")
    print(f"  highlights = {len(data.get('highlights', []))} 行")
    print(f"  items      = {len(data.get('items', []))} 件")


def main() -> int:
    wait = "--wait" in sys.argv
    deadline = time.monotonic() + MAX_WAIT_SECONDS

    while True:
        run = latest_run()
        if run is None:
            print("まだ実行が登録されていません。")
            return 0

        if not wait or run["status"] == "completed":
            show(run)
            show_digest()
            return 0 if run.get("conclusion") in (None, "success") else 1

        if time.monotonic() > deadline:
            print(f"{MAX_WAIT_SECONDS} 秒待ちましたが完了しませんでした。")
            show(run)
            return 2

        print(f"  ... {run['status']} ({POLL_SECONDS}秒後に再確認)")
        time.sleep(POLL_SECONDS)


if __name__ == "__main__":
    raise SystemExit(main())
