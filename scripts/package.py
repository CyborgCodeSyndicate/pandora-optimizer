"""Assemble an unpack-and-run distribution. Requires Java 21+ and .NET 8 at runtime."""
import argparse
from pathlib import Path
import shutil
import subprocess
import zipfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, default=Path("dist"), help="Fresh distribution parent directory")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
jar = root / "cli/target/pandora.jar"
if not jar.is_file():
    raise SystemExit("Build first: mvnw verify")
dist = (root / args.output).resolve()
dist.mkdir(parents=True, exist_ok=True)
package = dist / "pandora-optimizer-0.1.0"
if package.exists():
    raise SystemExit(f"Package directory already exists: {package}. Use --output with a fresh directory.")
(package / "lib").mkdir(parents=True)
shutil.copy2(jar, package / "lib/pandora.jar")
subprocess.run(["dotnet", "publish", str(root / "adapters/dotnet/Pandora.Adapter.DotNet.csproj"),
                "-c", "Release", "--no-self-contained", "-o", str(package / "dotnet")], check=True)
for name in ("pandora.cmd", "pandora.sh", "README.md"):
    shutil.copy2(root / name, package / name)
(package / "pandora.sh").chmod(0o755)
for name in ("skills", "schemas", "docs"):
    shutil.copytree(root / name, package / name)
with zipfile.ZipFile(dist / "pandora-optimizer-0.1.0.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(package.rglob("*")):
        if path.is_file():
            archive.write(path, Path(package.name) / path.relative_to(package))
print(f"Created {dist / 'pandora-optimizer-0.1.0.zip'}")
