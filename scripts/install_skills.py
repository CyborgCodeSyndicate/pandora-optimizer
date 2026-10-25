"""Install the same local skill packages into supported project discovery directories."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", required=True, type=Path)
    parser.add_argument("--providers", default="codex,claude")
    parser.add_argument("--runtime-home", type=Path)
    args = parser.parse_args()
    source = Path(__file__).resolve().parents[1]
    target = args.target.resolve()
    runtime = (args.runtime_home or source).resolve()
    if not (target / ".git").exists():
        raise SystemExit("Target must be an existing Git repository.")
    locations = {"codex": ".agents/skills", "claude": ".claude/skills"}
    destinations = []
    for provider in args.providers.split(","):
        if provider not in locations:
            raise SystemExit("Unsupported provider: " + provider)
        for name in ("ai-teacher", "ai-navigator"):
            dest = target / locations[provider] / name
            if dest.exists() and not (dest / "pandora-install.json").is_file():
                raise SystemExit("Existing unmanaged skill was preserved: " + str(dest))
            destinations.append((provider, name, dest))
    for provider, name, dest in destinations:
        src = source / "skills" / name
        shutil.copytree(src, dest, dirs_exist_ok=True,
                        ignore=shutil.ignore_patterns("runtime.json", "pandora-install.json"))
        (dest / "runtime.json").write_text(json.dumps({"home": str(runtime)}, indent=2)+"\n", encoding="utf-8")
        digest = hashlib.sha256((src / "SKILL.md").read_bytes()).hexdigest()
        (dest / "pandora-install.json").write_text(json.dumps({"source": name, "sha256": digest}, indent=2)+"\n", encoding="utf-8")
        print(provider + ": " + str(dest))
    ignore = target / ".gitignore"
    old = ignore.read_text(encoding="utf-8") if ignore.exists() else ""
    patterns = ["pandora/generated/", "pandora/cache/",
                ".agents/skills/*/runtime.json", ".claude/skills/*/runtime.json"]
    missing = [line for line in patterns if line not in old.splitlines()]
    if missing:
        ignore.write_text(old.rstrip()+"\n\n# Pandora Optimizer\n"+"\n".join(missing)+"\n", encoding="utf-8")

if __name__ == "__main__":
    main()
