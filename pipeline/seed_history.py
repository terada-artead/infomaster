"""既存の digests/*.json から配信履歴を作る（重複排除の導入時に一度だけ使う）。"""

from __future__ import annotations

import json
from pathlib import Path

from src.history import History, PublishedItem

DIGESTS = Path(__file__).resolve().parent.parent / "digests"

items: list[PublishedItem] = []
for path in sorted(DIGESTS.glob("*.json")):
    if not path.stem[:2].isdigit():
        continue
    digest = json.loads(path.read_text(encoding="utf-8"))
    for item in digest.get("items", []):
        items.append(
            PublishedItem(
                date=digest["date"],
                id=item["id"],
                title_ja=item["title_ja"],
                urls=[s["url"] for s in item.get("sources", [])],
            )
        )
    print(f"{path.name}: {len(digest.get('items', []))} 件")

out = DIGESTS / "published.json"
out.write_text(
    json.dumps(History(items=items).to_dict(), ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)
print(f"\n{out} に {len(items)} 件を記録しました。")
print(f"URL の総数: {len({u for i in items for u in i.urls})}")
