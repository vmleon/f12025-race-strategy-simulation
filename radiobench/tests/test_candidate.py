import types

from radiobench.config import Candidate
from radiobench.candidate import generate_once


class _Delta:
    def __init__(self, content):
        self.content = content


class _Choice:
    def __init__(self, content):
        self.delta = _Delta(content)


class _Chunk:
    def __init__(self, content=None, usage=None):
        self.choices = [_Choice(content)] if content is not None else []
        self.usage = usage


class _Usage:
    completion_tokens = 3


class _FakeClient:
    """Mimics client.chat.completions.create(stream=True) -> iterable of chunks."""
    def __init__(self):
        self.chat = types.SimpleNamespace(
            completions=types.SimpleNamespace(create=self._create))

    def _create(self, **kwargs):
        yield _Chunk(content="Box ")
        yield _Chunk(content="now.")
        yield _Chunk(content=None, usage=_Usage())  # final usage-only chunk


def test_generate_once_collects_output_and_timing():
    cand = Candidate(name="x", base_url="http://h/v1", model="m", temperature=0.4, max_tokens=50)
    res = generate_once(cand, "sys", "user", client=_FakeClient())
    assert res.output == "Box now."
    assert res.completion_tokens == 3
    assert res.total_ms >= 0
    assert res.ttft_ms >= 0
    assert res.tokens_per_sec >= 0
    assert res.error is None
