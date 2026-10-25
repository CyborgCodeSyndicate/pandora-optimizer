"""Provider-neutral launcher for the Pandora runtime used by this skill."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

def main():
    skill = Path(__file__).resolve().parents[1]
    registration = skill / "runtime.json"
    home = os.environ.get("PANDORA_HOME")
    if not home and registration.is_file():
        home = json.loads(registration.read_text(encoding="utf-8"))["home"]
    if not home:
        raise SystemExit("Set PANDORA_HOME or reinstall this skill with scripts/install_skills.py.")
    root = Path(home).expanduser().resolve()
    jar = root / "lib/pandora.jar"
    if not jar.is_file():
        jar = root / "cli/target/pandora.jar"
    if not jar.is_file():
        raise SystemExit("Pandora runtime is not built: " + str(jar))
    java = shutil.which("java")
    if not java:
        raise SystemExit("Java 21+ must be available on PATH.")
    environment = dict(os.environ)
    for worker in (root / "dotnet/Pandora.Adapter.DotNet.dll",
                   root / "adapters/dotnet/bin/Debug/net8.0/Pandora.Adapter.DotNet.dll"):
        if worker.is_file():
            environment.setdefault("PANDORA_DOTNET_ADAPTER", str(worker))
            break
    classpath = str(jar)
    if environment.get("PANDORA_EXTRA_CLASSPATH"):
        classpath += os.pathsep + environment["PANDORA_EXTRA_CLASSPATH"]
    return subprocess.run([java, "-cp", classpath, "io.cyborgcode.pandora.cli.Main", *sys.argv[1:]],
                          env=environment, check=False).returncode

if __name__ == "__main__":
    sys.exit(main())
