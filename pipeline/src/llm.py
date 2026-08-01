"""Anthropic API 呼び出しの共通処理。

モデルの使い分け:
  選別・名寄せ  Haiku 4.5  — 件数が多く、判断が単純な段
  執筆          Sonnet 5   — 日本語の質がそのまま成果物の質になる段
"""

from __future__ import annotations

import logging
import os
import time
from typing import Any, TypeVar

import anthropic
from pydantic import BaseModel

log = logging.getLogger(__name__)

# 選別・名寄せ用。安価で件数を捌ける。
FAST_MODEL = "claude-haiku-4-5"
# 執筆用。日本語要約の品質が直接ここで決まる。
WRITER_MODEL = "claude-sonnet-5"

T = TypeVar("T", bound=BaseModel)


class MissingApiKey(RuntimeError):
    pass


def client() -> anthropic.Anthropic:
    if not os.environ.get("ANTHROPIC_API_KEY"):
        raise MissingApiKey(
            "ANTHROPIC_API_KEY が設定されていません。"
            "ローカルでは環境変数に、GitHub Actions では Secrets に設定してください。"
            "（収集だけ試すなら --collect-only を使ってください）"
        )
    return anthropic.Anthropic()


def parse(
    *,
    model: str,
    system: str,
    user: str,
    output_format: type[T],
    max_tokens: int = 8000,
    effort: str | None = None,
    max_attempts: int = 3,
) -> T:
    """構造化出力つきで1回問い合わせ、検証済みオブジェクトを返す。

    過負荷やレート制限は SDK も再試行するが、モデルが schema を満たさない
    レスポンスを返すケースはこちらで拾い直す必要がある。
    """
    kwargs: dict[str, Any] = {
        "model": model,
        "max_tokens": max_tokens,
        "system": system,
        "messages": [{"role": "user", "content": user}],
        "output_format": output_format,
    }
    # effort は Haiku 4.5 では未対応なので、指定があるときだけ渡す。
    if effort is not None:
        kwargs["output_config"] = {"effort": effort}

    api = client()
    last_error: Exception | None = None

    for attempt in range(1, max_attempts + 1):
        try:
            response = api.messages.parse(**kwargs)
        except anthropic.APIStatusError as exc:
            # 4xx は投げ直しても直らないので即座に諦める（429 は SDK が再試行済み）
            if exc.status_code < 500 and exc.status_code != 429:
                raise
            last_error = exc
        else:
            if response.stop_reason == "refusal":
                raise RuntimeError(
                    f"モデルが応答を拒否しました: {response.stop_details}"
                )
            if response.stop_reason == "max_tokens":
                # 途中で切れた出力は使えない。トークンを増やして再試行する。
                log.warning(
                    "max_tokens に達したため出力が切れました (attempt %d)。増やして再試行します。",
                    attempt,
                )
                kwargs["max_tokens"] = min(int(kwargs["max_tokens"] * 1.6), 64000)
                last_error = RuntimeError("max_tokens に達しました")
            elif response.parsed_output is None:
                log.warning("構造化出力の検証に失敗しました (attempt %d)", attempt)
                last_error = RuntimeError("parsed_output が None です")
            else:
                return response.parsed_output

        if attempt < max_attempts:
            wait = 2.0**attempt
            log.info("%.0f 秒待って再試行します", wait)
            time.sleep(wait)

    raise RuntimeError(f"{max_attempts} 回試行しても成功しませんでした: {last_error}")
