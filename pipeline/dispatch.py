"""GitHub Actions のワークフローを起動する。

push に使っている資格情報を Git Credential Manager から取り出して使う。
トークンは標準出力に出さない。
"""

from __future__ import annotations

import subprocess
import sys

import httpx

REPO = "terada-artead/infomaster"
WORKFLOW = "digest.yml"


def credential() -> str | None:
    """git が github.com 用に保持しているトークンを取り出す。"""
    request = "protocol=https\nhost=github.com\n\n"
    try:
        result = subprocess.run(
            ["git", "credential", "fill"],
            input=request,
            capture_output=True,
            text=True,
            timeout=30,
        )
    except Exception as exc:
        print(f"資格情報を取得できませんでした: {exc}")
        return None
    if result.returncode != 0:
        print("資格情報を取得できませんでした（git credential fill が失敗）")
        return None
    for line in result.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    return None


def main() -> int:
    token = credential()
    if not token:
        print("Git Credential Manager にトークンがありませんでした。")
        return 2

    resp = httpx.post(
        f"https://api.github.com/repos/{REPO}/actions/workflows/{WORKFLOW}/dispatches",
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
        json={"ref": "main"},
        timeout=30,
    )

    if resp.status_code == 204:
        print("ワークフローを起動しました。")
        return 0

    # 本文にトークンは含まれないので、そのまま出して原因を見る
    print(f"起動できませんでした: HTTP {resp.status_code}")
    print(resp.text[:400])
    return 1


if __name__ == "__main__":
    sys.exit(main())
