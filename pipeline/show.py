"""生成されたダイジェストを読みやすく表示する（品質確認用）。"""

from __future__ import annotations

import sys

import httpx

REPO = "terada-artead/infomaster"
RAW = f"https://raw.githubusercontent.com/{REPO}/main/digests/latest.json"

data = httpx.get(RAW, timeout=20).json()

print(f"=== {data['date']} のダイジェスト ===")
print(f"収集 {data['stats']['collected']} → 選別 {data['stats']['selected']} "
      f"→ 配信 {data['stats']['published']}\n")

print("--- 今日の3行 ---")
for line in data["highlights"]:
    print(f"  {line}")

for importance in ("high", "medium"):
    items = [i for i in data["items"] if i["importance"] == importance]
    if not items:
        continue
    print(f"\n--- 重要度 {importance} ({len(items)}件) ---")
    for item in items:
        print(f"\n[{item['category']}] {item['title_ja']}")
        print(f"  {item['summary_ja']}")
        names = ", ".join(s["name"] for s in item["sources"])
        print(f"  出典: {names}")
        print(f"        {item['sources'][0]['url']}")

# 文字数の分布。指示（high 2-3文 / medium 1文）が効いているかの確認。
print("\n--- 要約の長さ ---")
for importance in ("high", "medium"):
    lengths = [
        len(i["summary_ja"]) for i in data["items"] if i["importance"] == importance
    ]
    if lengths:
        print(
            f"  {importance:6} n={len(lengths):2}  "
            f"min={min(lengths):3} max={max(lengths):3} "
            f"avg={sum(lengths)//len(lengths):3} 字"
        )
titles = [len(i["title_ja"]) for i in data["items"]]
print(f"  見出し  n={len(titles):2}  min={min(titles):3} max={max(titles):3} "
      f"avg={sum(titles)//len(titles):3} 字")
