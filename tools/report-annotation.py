#!/usr/bin/env python3
"""Emit the upstream package report as a single GitHub Actions annotation.

The other channels CI can write to are all unreadable from outside the Actions
UI: the log host and the artifact host refuse connections from anywhere but the
UI itself, a job summary lives only on the run page, and a pull request comment
needs a pull request that still exists.

Takes an optional second argument, the Gradle log of the step that produces the
report. When the report is missing, the tail of that log is annotated instead of
a bare warning, because "no report was produced" on its own does not say whether
the packages moved, the network dropped, or the test was skipped.

Percent has to be escaped first, because GitHub reads %0A and friends inside an
annotation message.
"""

from __future__ import annotations

import sys

TAIL_LINES = 30


def escape(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def annotate(level: str, title: str, message: str) -> None:
    print(f"::{level} title={title}::{escape(message)}")


def tail_of(path: str) -> str:
    try:
        with open(path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return ""
    return "\n".join(lines[-TAIL_LINES:]).strip()


def main() -> int:
    args = sys.argv[1:]
    if not args:
        print("usage: report-annotation.py <report-file> [gradle-log]", file=sys.stderr)
        return 2

    report_path = args[0]
    log_path = args[1] if len(args) > 1 else None

    try:
        with open(report_path, encoding="utf-8", errors="replace") as handle:
            report = handle.read().strip()
    except OSError as error:
        report = ""
        missing_reason = str(error)
    else:
        missing_reason = "the file is empty" if not report else ""

    if report:
        annotate("notice", "Upstream package verification", report)
        return 0

    detail = tail_of(log_path) if log_path else ""
    message = "no report was produced"
    if missing_reason:
        message += f" ({missing_reason})"
    if detail:
        message += "\n" + detail
    annotate("warning", "Upstream package verification", message)
    return 0


if __name__ == "__main__":
    sys.exit(main())
