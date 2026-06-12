"""Runs the shared conformance fixtures from spec/conformance/."""

import json
from pathlib import Path

import pytest

from triplate import TriplateError, render

FIXTURES = Path(__file__).resolve().parents[2] / "triplate.dev" / "spec" / "conformance"


def _cases():
    for file in sorted(FIXTURES.glob("*.json")):
        for case in json.loads(file.read_text(encoding="utf-8")):
            yield pytest.param(case, id=f"{file.stem}: {case['name']}")


@pytest.mark.parametrize("case", _cases())
def test_conformance(case):
    if "error" in case:
        with pytest.raises(TriplateError) as exc_info:
            render(case["template"], case["context"])
        assert type(exc_info.value).__name__ == case["error"]
    else:
        assert render(case["template"], case["context"]) == case["expected"]
