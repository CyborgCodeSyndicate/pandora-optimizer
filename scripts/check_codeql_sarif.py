"""Enforce CodeQL findings when GitHub code-scanning uploads are unavailable."""

import argparse
import json
from pathlib import Path
import sys


def check(directory: Path) -> int:
    reports = sorted(directory.glob("*.sarif"))
    if not reports:
        print("CodeQL failed: no SARIF reports were produced.", file=sys.stderr)
        return 2

    findings = 0
    try:
        for report in reports:
            document = json.loads(report.read_text(encoding="utf-8-sig"))
            runs = document.get("runs")
            if document.get("version") != "2.1.0" or not isinstance(runs, list) or not runs:
                raise ValueError("Expected SARIF 2.1.0 with at least one analysis run")
            for run in runs:
                results = run.get("results", [])
                if not isinstance(results, list):
                    raise ValueError("SARIF run results must be an array")
                findings += len(results)
                for result in results[:20]:
                    print(f"CodeQL finding: {result.get('ruleId', 'unknown rule')}")
    except (OSError, ValueError, TypeError, AttributeError) as error:
        print(f"CodeQL failed: invalid or unreadable report: {error}", file=sys.stderr)
        return 2

    print(f"CodeQL: {findings} findings across {len(reports)} SARIF reports.")
    return 1 if findings else 0


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    sys.exit(check(parser.parse_args().directory))
