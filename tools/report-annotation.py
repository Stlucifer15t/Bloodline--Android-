#!/usr/bin/env python3
"""Emit a report file as a single GitHub Actions annotation.

The other channels CI can write to are all unreadable from outside the Actions
UI: the log host and the artifact host refuse connections from anywhere but the
UI itself, a job summary lives only on the run page, and a pull request comment
needs a pull request that still exists. This one comes back with `gh run view`.

Percent has to be escaped first, because GitHub reads %0A and friends inside an
annotation message.
"""

from __future__ import annotations

import sys


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: report-annotation.py <report-file>", file=sys.stderr)
        return 2

    try:
        with open(sys.argv[1], encoding="utf-8", errors="replace") as handle:
            report = handle.read().strip()
    except OSError as error:
        print("::warning::no report was produced (%s)" % error.strerror)
        return 0

    if not report:
        print("::warning::the upstream report was empty")
        return 0

    escaped = report.replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print("::notice title=Upstream package verification::" + escaped)
    return 0


if __name__ == "__main__":
    sys.exit(main())
