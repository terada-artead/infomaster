# infomaster

AI関連の最新情報を海外ソースから収集し、日本語の日次ダイジェストにして Android アプリで読むための個人用サービス。

## 構成

```
GitHub Actions (毎日 05:30 JST)
  ├─ 収集    RSS / Hacker News / Reddit / Bluesky / Hugging Face / GitHub Releases
  ├─ ①選別   Haiku 4.5    約400件 → 約60件
  ├─ ②名寄せ  Haiku 4.5    重複ニュースをクラスタ化 → 約25クラスタ
  ├─ ③執筆   Sonnet 5     日本語ダイジェスト生成 → 20件前後
  └─ 出力    digests/YYYY-MM-DD.json, digests/latest.json を commit
                ↓
Android アプリ (Kotlin + Jetpack Compose)
  ├─ WorkManager が朝フェッチ → ローカル通知
  ├─ Room にキャッシュ（オフライン閲覧可）
  └─ 原文リンクは Custom Tabs で開く
```

関心領域は **プロダクト・新モデル** と **ビジネス・業界動向**。論文・研究は対象外。

## ディレクトリ

| パス | 中身 |
|---|---|
| `pipeline/` | 収集・要約パイプライン (Python) |
| `digests/` | 生成された日次ダイジェスト JSON |
| `android/` | Android アプリ (Kotlin) |
| `.github/workflows/` | 日次実行の GitHub Actions |

## ローカル実行

```bash
cd pipeline
python -m venv .venv
.venv/Scripts/activate
pip install -r requirements.txt
```

収集だけ試す（APIキー不要）:

```bash
python -m src.main --collect-only
```

フルパイプライン（`ANTHROPIC_API_KEY` が必要）:

```bash
python -m src.main
```

## Android アプリ

`compileSdk 37 / targetSdk 36 / minSdk 26`。Kotlin + Jetpack Compose。
ダイジェストは端末内のファイルにキャッシュするのでオフラインでも読める。

ビルド（`JAVA_HOME` に Android Studio 同梱の JBR を指定する）:

```bash
cd android && ./gradlew assembleDebug
```

JSON のパースは実データを素材にしたユニットテストで検証している:

```bash
cd android && ./gradlew testDebugUnitTest
```

実機へのインストール（USBデバッグを有効にして接続した状態で）:

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

取得先は `app/build.gradle.kts` の `DIGEST_BASE_URL` で定義している。
リポジトリを変えたらそこだけ直せばよい。
