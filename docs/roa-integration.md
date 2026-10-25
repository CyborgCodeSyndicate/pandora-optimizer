# ROA migration and skill validation

The migration is implemented on local branches:

- roa-libraries: codex/ai-compass-migration, based on origin/pandora-fixes-2 (b9e9416).
- roa-example-projects: codex/pandora-optimizer-skills, based on the existing pandora/base checkout.
- pandora-optimizer: codex/roa-integration.

## Annotation SDK

Java projects depend only on the small annotation artifact:

```xml
<dependency>
    <groupId>io.cyborgcode.pandora</groupId>
    <artifactId>pandora-annotations</artifactId>
    <version>0.1.0</version>
</dependency>
```

The version is a local non-snapshot build because ROA's enforcer rejects snapshot dependencies. Install it from the Optimizer repository with mvnw install. It has not been published to a remote Maven registry by this task. The language-neutral CLI/engine stays outside application dependencies.

Use these imports:

```java
import io.cyborgcode.pandora.annotations.AiCompass;
import io.cyborgcode.pandora.annotations.AiCompassOptions;
import io.cyborgcode.pandora.annotations.CreationKind;
import io.cyborgcode.pandora.annotations.Preference;
```

Example preserving the original framework intent:

```java
@AiCompass(
      description = "Endpoint definitions accepted by API interactions.",
      tags = {"api"},
      creation = CreationKind.ENUM_CONSTANT
)
@AiCompassOptions(
      implementationsOf = Endpoint.class,
      meta = {@AiCompassOptions.Meta(key = "type", value = "endpoint")}
)
public interface Endpoint<T extends Enum<T>> {
    // Existing contract unchanged.
}
```

implementsOf discovery is spelled `implementationsOf`. It searches enum facts from the consuming workspace and configured catalogs, including transitive interface relationships. The default render is NAME; QUALIFIED retains the old fully qualified enum output where required. Fixed enum rules use enumType = SomeEnum.class.

The 140 migrated source files preserve all primary annotation payloads and executable declarations. There are 1,139 Compass annotations (including marker annotations). Forty-nine references to 46 old rule classes become declarative enum/implementation queries. Seven obsolete rule helper files are removed. All 527 usage-file references are removed; no usage JSON files are created.

## Build and refresh

Use JDK 21 for the ROA build. Its existing Lombok configuration depends on annotation processing behavior available there. The Optimizer CLI itself requires Java 21+, while the annotation SDK targets Java 17.

1. In pandora-optimizer: run mvnw install.
2. In roa-libraries on codex/ai-compass-migration: run mvn install with JDK 21.
3. In roa-example-projects on codex/pandora-optimizer-skills: run mvn test-compile with JDK 21. This compiles the live examples without launching browsers or sending their API requests.
4. Refresh framework context from the Optimizer repository:

```powershell
python scripts/refresh_roa_context.py --framework C:\Users\vikto\Documents\Code\roa-libraries --consumer C:\Users\vikto\Documents\Code\roa-example-projects
```

The consumer uses a checked-in framework metadata snapshot at pandora/catalogs/roa-framework.json. It contains API/option metadata, not usage examples. The provenance JSON records the producer branch, commit, dirty state and catalog fingerprint. The refresh script replaces only a catalog owned by the same producer.

Three existing example classes are annotated as GOOD AiLesson references. Maven dependencies in the example modules point to the locally built ROA 0.0.0 artifacts and the new annotation SDK. Existing example runtime code remains unchanged.

## Install the two canonical skills

The authoritative source is skills/ai-teacher and skills/ai-navigator in Pandora Optimizer. Each folder has standard SKILL.md plus a Python launcher. The skill payload does not contain provider API calls.

```powershell
python scripts/install_skills.py --target C:\Users\vikto\Documents\Code\roa-example-projects --providers codex,claude
```

The installer copies identical payloads into .agents/skills for Codex and .claude/skills for Claude Code. A local ignored runtime.json points to the built CLI. It preserves unmanaged skill folders. Other hosts can copy the same skill folders and set PANDORA_HOME to their installed runtime.

Discovery locations are documented by [OpenAI](https://learn.chatgpt.com/docs/customization/overview) and [Claude Code](https://code.claude.com/docs/en/skills). Newly installed Codex skills are available on the next turn in the relevant project.

## Query manually

From roa-example-projects:

```powershell
python .agents/skills/ai-teacher/scripts/pandora.py scan --if-stale
python .agents/skills/ai-teacher/scripts/pandora.py teacher search --category service
python .agents/skills/ai-navigator/scripts/pandora.py navigator search --query "RestService request"
python .agents/skills/ai-navigator/scripts/pandora.py navigator show "<copied-id>"
```

Expected lesson: io.cyborgcode.api.test.framework.service.CustomService. Expected endpoint options include GET_ALL_USERS, GET_USER, POST_CREATE_USER and POST_LOGIN_USER from the example application enums. Parameter order is preserved.

The source-only producer currently reports 11 unresolved external-library types because its configuration deliberately omits dependency JARs. The diagnostics remain visible and API resolution may be partial. The Java compilation and all annotation-driven options are verified separately. Supply resolved dependency JARs in module.references for fuller type resolution.

## Agent tests performed

Both real providers read the installed skills and executed their Python helpers:

- Claude Code 2.1.304: success, approximately 101 seconds.
- Codex CLI 0.164.0 with the existing configured model: success, approximately 171 seconds.

The originally installed Codex CLI 0.120.0 failed before model execution because the configured gpt-6-astra model requires a newer client. The successful retry used an isolated cache installation under Pandora Optimizer's pandora/cache/codex-cli. Global CLI and model configuration were not changed.

Artifacts are retained in the example repository's ignored pandora/cache/agent-tests/claude and pandora/cache/agent-tests/codex-current-cli directories. They include prompts, tool events and results. The old-client failure is retained under codex.

To repeat:

```powershell
python scripts/evaluate_agents.py --provider claude --workspace C:\Users\vikto\Documents\Code\roa-example-projects --executable C:\Users\vikto\.local\bin\claude.exe
python scripts/evaluate_agents.py --provider codex --workspace C:\Users\vikto\Documents\Code\roa-example-projects --executable C:\Users\vikto\Documents\Code\pandora-optimizer\pandora\cache\codex-cli\node_modules\.bin\codex.cmd --run-name codex-current-cli
```

The scripts use existing authenticated accounts, limit the run duration, and prohibit application edits and live test execution in their prompts. If no compatible CLI is installed, install or update it separately using the host's official instructions.

## Validation results

- Optimizer: 32 Java unit tests, 10 C# unit tests, 8 cross-process integration tests passed.
- ROA libraries: 997 discovered tests, 996 executed successfully, 1 skipped, 0 failures/errors; coverage and static checks passed through the reactor build and resumed reactor.
- All three example modules: main and test source compiled successfully.
- Annotation/executable AST audit: passed for all 140 migrated Java files.
- Consumer catalog: 1,229 symbols, 3 lessons, real consumer enum options, zero usage-file references.
- Both provider agent runs: successful, with exact lesson/API IDs and endpoint values in their results.

The user-modified .pandora/config.yaml in the examples repository is unrelated to Optimizer and has been preserved.
