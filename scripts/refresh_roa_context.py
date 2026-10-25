"""Refresh the ROA framework catalog and resolve it against the examples workspace."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import os

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--framework",type=Path,required=True)
    parser.add_argument("--consumer",type=Path,required=True)
    args=parser.parse_args()
    home=Path(__file__).resolve().parents[1]
    framework=args.framework.resolve(); consumer=args.consumer.resolve()
    jar=home/"cli/target/pandora.jar"
    def cli(root,*arguments):
        result=subprocess.run(["java","-jar",str(jar),"--root",str(root),*arguments],text=True,encoding="utf-8",capture_output=True)
        if result.returncode:
            raise SystemExit(result.stderr)
        return json.loads(result.stdout)
    producer=cli(framework,"scan","--if-stale")
    generated=(framework/"pandora/generated").resolve()
    manifest=json.loads((generated/"manifest.json").read_text(encoding="utf-8"))
    catalog=(generated/manifest["catalog"]).resolve()
    if not catalog.is_relative_to(generated):raise SystemExit("Catalog path escapes generated directory")
    payload=catalog.read_bytes()
    document=json.loads(payload)
    destination=consumer/"pandora/catalogs/roa-framework.json"
    destination.parent.mkdir(parents=True,exist_ok=True)
    if destination.exists():
        old=json.loads(destination.read_text(encoding="utf-8"))
        if old.get("workspaceId")!=document["workspaceId"]:
            raise SystemExit("Existing catalog belongs to a different producer; it was preserved.")
    with tempfile.NamedTemporaryFile(dir=destination.parent,delete=False) as output:
        output.write(payload); temp=Path(output.name)
    try:os.replace(temp,destination)
    finally:
        if temp.exists():temp.unlink()
    def git(*arguments):
        return subprocess.check_output(["git","-C",str(framework),*arguments],text=True,encoding="utf-8").strip()
    provenance={"producerWorkspace":document["workspaceId"],"sourceBranch":git("branch","--show-current"),
                "sourceCommit":git("rev-parse","HEAD"),"workingTreeDirty":bool(git("status","--porcelain")),
                "catalogFingerprint":document["fingerprint"]}
    destination.with_suffix(".provenance.json").write_text(json.dumps(provenance,indent=2)+"\n",encoding="utf-8")
    result=cli(consumer,"scan","--if-stale")
    print(json.dumps({"frameworkSymbols":producer["symbols"],"consumerSymbols":result["symbols"],
                      "catalog":str(destination),"diagnostics":len(result.get("diagnostics",[]))},indent=2))

if __name__=="__main__":
    main()
