"""主要 AI ツールの GitHub リリースからの収集。

未認証だと 60 req/h の制限があり、監視リポジトリ数だとギリギリ収まる。
GitHub Actions 上では GITHUB_TOKEN があるので余裕を持って叩ける。
"""

from __future__ import annotations

import logging
import os
from datetime import datetime
from typing import Any

from ..models import Item
from ._http import client, clip, cutoff

log = logging.getLogger(__name__)


def collect(config: dict[str, Any], lookback_hours: int) -> list[Item]:
    since = cutoff(lookback_hours)
    items: list[Item] = []

    headers = {"Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"

    with client() as http:
        for repo in config.get("repos", []):
            try:
                resp = http.get(
                    f"https://api.github.com/repos/{repo}/releases/latest",
                    headers=headers,
                )
                if resp.status_code == 404:
                    # リリースを切っていないリポジトリは静かに飛ばす
                    continue
                resp.raise_for_status()
                release = resp.json()
            except Exception as exc:
                log.warning("github releases %s failed: %s", repo, exc)
                continue

            published = _published_at(release)
            if published is None or published < since:
                continue

            name = release.get("name") or release.get("tag_name") or "release"
            items.append(
                Item(
                    source=f"GitHub {repo}",
                    source_kind="github",
                    tier="primary",
                    title=f"{repo} released {name}",
                    url=release.get("html_url", f"https://github.com/{repo}/releases"),
                    published_at=published,
                    excerpt=clip(release.get("body") or "", 600),
                )
            )

    return items


def _published_at(release: dict[str, Any]) -> datetime | None:
    raw = release.get("published_at") or release.get("created_at")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
