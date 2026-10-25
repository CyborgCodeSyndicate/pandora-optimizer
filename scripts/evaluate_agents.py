"""Run a bounded, non-editing skill smoke test through an installed coding-agent CLI."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--provider",choices=["codex","claude"],required=True)
    parser.add_argument("--workspace",type=Path,required=True)
    parser.add_argument("--executable",type=Path)
    parser.add_argument("--timeout",type=int,default=300)
    parser.add_argument("--run-name")
    args=parser.parse_args()
    root=args.workspace.resolve()
    name=args.run_name or args.provider
    if not name.replace("-","").replace("_","").isalnum():
        raise SystemExit("Run name must contain letters, digits, dashes or underscores.")
    output=root/"pandora/cache/agent-tests"/name
    output.mkdir(parents=True,exist_ok=True)
    executable=str(args.executable) if args.executable else shutil.which(args.provider)
    if not executable:
        raise SystemExit("Coding-agent CLI not found: "+args.provider)
    prompt=(
        "Use both installed project skills ai-teacher and ai-navigator to test their real workflow. "
        "For Codex, explicitly invoke $ai-teacher and $ai-navigator. For Claude, load the corresponding skills. "
        "This is a read/query evaluation, not a request to implement application changes. "
        "Read each installed SKILL.md and use its Python helper to query Pandora. "
        "Find a service lesson relevant to composing API requests and assertions in this examples repository. "
        "Then inspect the framework RestService.request API and its endpoint parameter options. "
        "Report: the exact lesson ID and source path, an exact API ID, at least three actual endpoint options, "
        "whether usage JSON files are needed for this lookup, and the commands you actually ran. "
        "Do not change application source, POMs, skills or configuration. "
        "Only Pandora generated/cache writes are permitted. Do not run Maven, browsers, live API tests, "
        "or external network requests. Do not read .pandora/config.yaml; it is unrelated user configuration."
    )
    (output/"prompt.txt").write_text(prompt+"\n",encoding="utf-8")
    if args.provider=="codex":
        command=[executable,"exec","--cd",str(root),"--sandbox","workspace-write","--ephemeral",
                 "--json","--output-last-message",str(output/"answer.txt"),"-"]
    else:
        command=[executable,"-p",prompt,"--output-format","stream-json","--verbose","--max-turns","20",
                 "--allowedTools","Read,Glob,Grep,Skill,Bash(python *)"]
    started=time.time()
    with (output/"events.jsonl").open("w",encoding="utf-8") as stdout, (output/"stderr.txt").open("w",encoding="utf-8") as stderr:
        process=subprocess.Popen(command,cwd=root,stdin=subprocess.PIPE,stdout=stdout,stderr=stderr,text=True,encoding="utf-8")
        try:
            process.communicate(prompt if args.provider=="codex" else None,timeout=args.timeout)
            status="completed" if process.returncode==0 else "failed"
        except subprocess.TimeoutExpired:
            if os.name=="nt":
                subprocess.run(["taskkill","/PID",str(process.pid),"/T","/F"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,check=False)
            else:
                process.kill()
            process.wait()
            status="timeout"
    result={"provider":args.provider,"status":status,"exitCode":process.returncode,
            "elapsedSeconds":round(time.time()-started,1),"artifacts":str(output)}
    (output/"result.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(result))
    return 0 if status=="completed" else 1

if __name__=="__main__":
    sys.exit(main())
