#!/usr/bin/env python3
"""Emit the unit test count as a GitHub Actions annotation.

tools/test-summary.py writes the number into the job summary, which is only
readable in the Actions UI. This prints the same number where `gh run view`
returns it, so the count can be checked without opening a browser.
"""

from __future__ import annotations

import re
import sys


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: test-annotation.py <test-summary.md>", file=sys.stderr)
        return 2

    try:
        with open(sys.argv[1], encoding="utf-8") as handle:
            table = handle.read()
    except OSError:
        # No summary means no tests ran, which the build reports elsewhere.
        return 0

    # The lookahead keeps the closing pipe available, so consecutive cells
    # are all matched instead of every other one.
    cells = re.findall(r"\|\s*(\d+)\s*(?=\|)", table)
    if len(cells) < 5:
        print("::warning::the test summary had no count to annotate")
        return 0

    files, tests, failures, errors, skipped = cells[:5]
    print(
        "::notice title=Unit tests::"
        f"{tests} run, {failures} failed, {errors} errored, {skipped} skipped, "
        f"in {files} report files"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
