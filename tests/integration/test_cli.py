"""End-to-end tests of the packaged CLI, worker protocol and both JSON contracts."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import jsonschema

REPO = Path(__file__).resolve().parents[2]
JAR = REPO / "cli/target/pandora.jar"
WORKER = REPO / "adapters/dotnet/bin/Debug/net8.0/Pandora.Adapter.DotNet.dll"

class CliIntegration(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pandora-e2e-")
        self.root = Path(self.temp.name)
        shutil.copytree(REPO / "fixtures", self.root / "fixtures")
        (self.root / "pandora").mkdir()
        config = json.loads((REPO / "pandora/config.json").read_text())
        config["modules"][1]["command"] = ["dotnet", str(WORKER)]
        self.config = config
        self.write_config()

    def tearDown(self):
        self.temp.cleanup()

    def write_config(self):
        (self.root / "pandora/config.json").write_text(json.dumps(self.config), encoding="utf-8")

    def cli(self, *args, code=0):
        result = subprocess.run(["java", "-jar", str(JAR), "--root", str(self.root), *args],
                                text=True, encoding="utf-8", capture_output=True, timeout=120)
        self.assertEqual(code, result.returncode, result.stdout + result.stderr)
        return json.loads(result.stdout if code == 0 else result.stderr)

    def catalog(self):
        manifest = json.loads((self.root / "pandora/generated/manifest.json").read_text())
        return json.loads((self.root / "pandora/generated" / manifest["catalog"]).read_text())

    def test_both_languages_and_parameter_options(self):
        self.cli("scan", "--strict")
        catalog = self.catalog()
        schema = json.loads((REPO / "schemas/catalog.schema.json").read_text())
        jsonschema.Draft202012Validator(schema).validate(catalog)
        lessons = self.cli("teacher", "search", "--category", "service", "--tag", "retry")
        self.assertEqual(2, len(lessons["items"]))
        self.assertEqual({"java", "csharp"}, {s["language"] for s in lessons["items"]})
        for lesson in lessons["items"]:
            result = self.cli("navigator", "show", lesson["id"], "--include-source")
            self.assertEqual("complete", result["sourceStatus"])
            parameters = result["item"]["parameters"]
            self.assertEqual([0, 1, 2], [p["index"] for p in parameters])
            self.assertEqual(["sandbox", "live"], parameters[1]["resolvedOptions"]["values"])
            self.assertEqual(["STANDARD", "EXPRESS"], parameters[2]["resolvedOptions"]["values"])
            self.assertEqual(1, len(result["item"]["usageExamples"]))
        self.cli("validate", "--strict")

    def test_bundle_in_another_repository(self):
        self.cli("scan")
        self.cli("navigator", "bundle", "--output", "bundle.json")
        self.config["modules"] = []
        self.config["resources"] = {"framework-v1": {"uri": "bundle.json"}}
        self.config["catalogs"] = ["framework-v1"]
        self.write_config()
        self.cli("scan")
        items = self.cli("navigator", "search", "--query", "Submit")["items"]
        self.assertTrue(items)
        result = self.cli("navigator", "show", items[0]["id"], "--include-source")
        self.assertEqual("catalog:framework-v1", result["item"]["origin"])
        self.assertEqual("unavailable-for-imported-catalog", result["sourceStatus"])

    def test_failed_scan_preserves_generation_and_reports_error(self):
        self.cli("scan")
        manifest_path = self.root / "pandora/generated/manifest.json"
        original = manifest_path.read_text()
        (self.root / "fixtures/resources/options.json").write_text('{"wrong":[]}')
        self.cli("scan", code=3)
        self.assertEqual(original, manifest_path.read_text())

    def test_if_stale_and_source_change(self):
        self.cli("scan")
        manifest = self.root / "pandora/generated/manifest.json"
        original = manifest.read_text()
        self.cli("scan", "--if-stale")
        self.assertEqual(original, manifest.read_text())
        java = self.root / "fixtures/java/demo/Payments.java"
        java.write_text(java.read_text() + "\n// edited\n")
        self.assertEqual("stale", self.cli("teacher", "search")["freshness"])
        self.cli("scan", "--if-stale")
        self.assertNotEqual(original, manifest.read_text())

    def test_csharp_worker_protocol(self):
        request = {
            "protocolVersion": 1, "workspaceId": "protocol-test", "moduleId": "csharp",
            "root": str(self.root), "sourceRoots": ["fixtures/dotnet"], "references": []
        }
        result = subprocess.run(["dotnet", str(WORKER)], input=json.dumps(request) + "\n",
                                capture_output=True, text=True, encoding="utf-8", timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        response = json.loads(result.stdout)
        schema = json.loads((REPO / "schemas/adapter-response.schema.json").read_text())
        # The response schema embeds the shared definitions to work offline.
        jsonschema.Draft202012Validator(schema).validate(response)
        self.assertTrue(any(s["lesson"] for s in response["symbols"]))

    def check_empty_workspace(self, language):
        self.root = self.root / ("empty-" + language)
        self.root.mkdir()
        self.cli("init", "--language", language)
        if language == "csharp":
            path = self.root / "pandora/config.json"
            config = json.loads(path.read_text())
            config["modules"][0]["command"] = ["dotnet", str(WORKER)]
            path.write_text(json.dumps(config), encoding="utf-8")
        result = self.cli("scan", "--strict")
        self.assertEqual(0, result["symbols"])
        self.assertEqual(0, self.cli("teacher", "search")["total"])
        self.assertEqual(0, self.cli("navigator", "search")["total"])
        self.cli("validate", "--strict")

    def test_empty_java_repository(self):
        self.check_empty_workspace("java")

    def test_empty_dotnet_repository(self):
        self.check_empty_workspace("csharp")

    def test_invalid_imported_catalog_is_rejected(self):
        self.cli("scan")
        invalid = self.catalog()
        invalid["symbols"][0]["compass"] = {"preference": "UNKNOWN"}
        (self.root / "invalid.json").write_text(json.dumps(invalid), encoding="utf-8")
        self.config["modules"] = []
        self.config["resources"] = {"invalid": {"uri": "invalid.json"}}
        self.config["catalogs"] = ["invalid"]
        self.write_config()
        self.cli("scan", code=2)

if __name__ == "__main__":
    unittest.main()
