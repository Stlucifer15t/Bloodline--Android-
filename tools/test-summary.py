#!/usr/bin/env python3
"""Print a markdown summary of the JUnit reports Gradle just wrote.

Gradle swallows per-test output, and "BUILD SUCCESSFUL" says nothing about how
many tests that was. This reads the XML the test task leaves behind and prints a
table meant for the GitHub Actions job summary, so the count is part of the
record instead of something that has to be remembered — and so a change that
quietly stops running tests is visible rather than inferred.

It reports and never fails: by the time it runs, the test task has already
decided whether the build is red, and the step is there to say what happened.
"""

from __future__ import annotations

import glob
import os
import sys
import xml.etree.ElementTree as ET

RESULTS = os.path.join("app", "build", "test-results", "testDebugUnitTest")
COUNTS = ("tests", "failures", "errors", "skipped")


def report() -> str:
    files = sorted(glob.glob(os.path.join(RESULTS, "*.xml")))
    if not files:
        return "### Unit tests\n\nNo JUnit reports were written to `%s`." % RESULTS


    totals = dict.fromkeys(COUNTS, 0)
    for path in files:
        root = ET.parse(path).getroot()
        for key in COUNTS:
            totals[key] += int(root.get(key) or 0)

    return "\n".join(
        [
            "### Unit tests",
            "",
            "| report files | tests | failures | errors | skipped |",
            "| --- | --- | --- | --- | --- |",
            "| {files} | {tests} | {failures} | {errors} | {skipped} |".format(
                files=len(files),
                tests=totals["tests"],
                failures=totals["failures"],
                errors=totals["errors"],
                skipped=totals["skipped"],
            ),
        ]
    )


def main() -> int:
    markdown = report()
    print(markdown)

    # Optionally to a file as well, so a later step can attach it to something a
    # human reads. The job summary is not reachable from outside the run.
    if len(sys.argv) > 1:
        with open(sys.argv[1], "w") as handle:
            handle.write(markdown + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
