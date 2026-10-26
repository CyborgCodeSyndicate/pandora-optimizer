"""Exercise the CodeQL workflow gate through its command-line exit status."""

import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


class CodeQLGateTest(unittest.TestCase):
    def invoke(self, reports):
        script = Path(__file__).resolve().parents[1] / "check_codeql_sarif.py"
        with tempfile.TemporaryDirectory() as directory:
            for name, content in reports.items():
                Path(directory, name).write_text(content, encoding="utf-8")
            return subprocess.run(
                [sys.executable, str(script), directory],
                capture_output=True, text=True, check=False,
            )

    def report(self, results):
        return json.dumps({"version": "2.1.0", "runs": [{"results": results}]})

    def test_clean_report_passes(self):
        result = self.invoke({"java.sarif": self.report([])})
        self.assertEqual(0, result.returncode, result.stderr)

    def test_findings_in_any_report_fail(self):
        result = self.invoke({
            "clean.sarif": self.report([]),
            "java.sarif": self.report([{"ruleId": "java/path-injection", "level": "warning"}]),
        })
        self.assertEqual(1, result.returncode)
        self.assertIn("java/path-injection", result.stdout)

    def test_missing_report_fails(self):
        self.assertEqual(2, self.invoke({}).returncode)

    def test_invalid_or_empty_analysis_fails(self):
        for content in ("not JSON", "{}", '{"version":"2.1.0","runs":[]}',
                        '{"version":"2.1.0","runs":[{"results":null}]}'):
            with self.subTest(content=content):
                self.assertEqual(2, self.invoke({"java.sarif": content}).returncode)


if __name__ == "__main__":
    unittest.main()
