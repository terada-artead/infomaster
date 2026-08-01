"""生成したダイジェストを JSON として書き出す。

アプリは latest.json だけを見れば最新版が取れる。
日付ごとのファイルは履歴（アプリの過去分表示）用。
"""

from __future__ import annotations

import json
import logging
from pathlib import Path

from .models import Digest

log = logging.getLogger(__name__)

# アプリが一覧を引くための索引。全日付を持つと際限なく育つので直近だけ残す。
INDEX_LIMIT = 60


def write_digest(digest: Digest, out_dir: Path) -> Path:
    out_dir.mkdir(parents=True, exist_ok=True)
    payload = digest.to_dict()

    dated = out_dir / f"{digest.date}.json"
    _dump(dated, payload)
    _dump(out_dir / "latest.json", payload)
    _write_index(out_dir)

    return dated


def _write_index(out_dir: Path) -> None:
    """日付一覧を index.json に書く。アプリの履歴画面が使う。"""
    dates = sorted(
        (p.stem for p in out_dir.glob("*.json") if p.stem[:2].isdigit()),
        reverse=True,
    )[:INDEX_LIMIT]
    _dump(out_dir / "index.json", {"dates": dates})


def _dump(path: Path, payload: dict) -> None:
    path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    log.debug("wrote %s", path)
