#!/usr/bin/env python3
"""Print failing JUnit cases from a Gradle test-results directory.

CI helper: Gradle buries test failures in a long log, so after the test step
this prints `FAILED class.method: message` lines for each failure instead.
Exits 0 either way — the test task's own exit code is what fails the job.
"""
import glob
import sys
import xml.etree.ElementTree as ET

pattern = sys.argv[1] if len(sys.argv) > 1 else "**/TEST-*.xml"
files = sorted(glob.glob(pattern, recursive=True))
if not files:
    print("no test results found at", pattern)
    sys.exit(0)

total = failed = 0
for path in files:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        total += 1
        for kind in ("failure", "error"):
            node = case.find(kind)
            if node is None:
                continue
            failed += 1
            message = (node.get("message") or node.text or kind).strip().splitlines()
            print(f"FAILED {case.get('classname')}.{case.get('name')}")
            for line in message[:10]:
                print("    " + line)
print(f"\n{total} tests, {failed} failed")
