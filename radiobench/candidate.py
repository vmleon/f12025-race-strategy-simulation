"""Call an OpenAI-compatible (vLLM) endpoint with streaming and measure timing."""
from __future__ import annotations

import time
from dataclasses import dataclass

from radiobench.config import Candidate


@dataclass
class GenResult:
    output: str
    ttft_ms: float
    total_ms: float
    completion_tokens: int
    tokens_per_sec: float
    error: str | None = None


def _make_client(candidate: Candidate):
    from openai import OpenAI
    return OpenAI(base_url=candidate.base_url, api_key="dummy")


def generate_once(candidate: Candidate, system: str, user: str,
                  timeout_s: int = 60, client=None) -> GenResult:
    """Stream a completion; capture output text, TTFT, total latency, tokens/sec.

    On any error returns a GenResult with error set and zeroed metrics. `client`
    may be injected for testing; otherwise an OpenAI client is built.
    """
    if client is None:
        client = _make_client(candidate)

    start = time.perf_counter()
    ttft_ms = 0.0
    parts: list[str] = []
    completion_tokens = 0
    try:
        stream = client.chat.completions.create(
            model=candidate.model,
            messages=[{"role": "system", "content": system},
                      {"role": "user", "content": user}],
            temperature=candidate.temperature,
            max_tokens=candidate.max_tokens,
            stream=True,
            stream_options={"include_usage": True},
            timeout=timeout_s,
        )
        for chunk in stream:
            if getattr(chunk, "usage", None) is not None and chunk.usage:
                completion_tokens = chunk.usage.completion_tokens
            if not chunk.choices:
                continue
            delta = chunk.choices[0].delta
            piece = getattr(delta, "content", None)
            if piece:
                if not parts:
                    ttft_ms = (time.perf_counter() - start) * 1000
                parts.append(piece)
    except Exception as e:  # network, timeout, API error — record and continue
        total_ms = (time.perf_counter() - start) * 1000
        return GenResult("", ttft_ms, total_ms, 0, 0.0, error=str(e))

    total_ms = (time.perf_counter() - start) * 1000
    output = "".join(parts)
    if completion_tokens == 0:
        completion_tokens = len(output.split())
    tokens_per_sec = completion_tokens / (total_ms / 1000) if total_ms > 0 else 0.0
    return GenResult(output, ttft_ms, total_ms, completion_tokens, tokens_per_sec)
