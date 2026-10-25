"""Dependency-free packaging check for the two canonical skills."""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
for name in ("ai-teacher", "ai-navigator"):
    path = root / "skills" / name / "SKILL.md"
    text = path.read_text(encoding="utf-8")
    assert text.startswith("---\n"), f"{name}: frontmatter missing"
    header = text.split("---", 2)[1]
    assert f"name: {name}\n" in header, f"{name}: incorrect name"
    assert "description:" in header, f"{name}: description missing"
    assert "pandora " in text and "TODO" not in text, f"{name}: unfinished skill"
print("Validated both skill packages.")
