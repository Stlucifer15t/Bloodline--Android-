"""Prints every failing unit test, with the assertion message.

Gradle's console output points at an HTML report that nobody can read from a
pull request comment, so the failure report reads the JUnit XML instead.
"""

import glob
import sys
import xml.etree.ElementTree as ET

pattern = sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results/testDebugUnitTest/*.xml"

printed = False
for path in sorted(glob.glob(pattern)):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue

    for case in root.iter("testcase"):
        failures = list(case.findall("failure")) + list(case.findall("error"))
        for failure in failures:
            printed = True
            print("{}.{}".format(case.get("classname"), case.get("name")))
            message = (failure.get("message") or "").strip()
            if message:
                print(message[:800])
            body = (failure.text or "").strip()
            if body:
                print(body[:800])
            print()

if not printed:
    print("No failing test was recorded in the JUnit XML.")
