# Maven parent and shared pipelines

Pandora Optimizer inherits the organization standards from
`io.cyborgcode.utilities:parent-pom`. Its root remains the parent of the four
Java modules, so the same standards apply to every published Maven project:

| Project | Java release |
| --- | --- |
| `pandora-optimizer` reactor parent | 21 |
| `pandora-annotations` | 17 |
| `pandora-engine` | 21 |
| `pandora-adapter-java` | 21 |
| `pandora-cli` | 21 |

The annotation SDK stays compatible with Java 17 consumers. Development and CI
use JDK 21 to build the complete reactor. The .NET SDK and language adapter
remain separate projects; their existing locked restore and tests remain in CI.

## Version ownership

The utilities parent manages Jackson 2, JSON Schema validation, JavaParser,
Picocli, SLF4J, JUnit and the four Optimizer module dependencies. The Jackson 2
BOM remains separate from the parent's existing Jackson 3 BOM. Dependencies in
the Optimizer POMs contain no versions.

Compiler, test, packaging, source/Javadoc, coverage, static analysis and release
plugin versions also come from utilities. The old independent Spotless policy
has been replaced by the shared Checkstyle policy. The CLI retains only its
application-specific Shade configuration and executable entry point.

`pandora-optimizer.version` is `${project.version}` in the reactor. This lets
managed sibling dependencies follow `versions:set` without changing or
accidentally bumping the external utilities parent. Other repositories inherit
the centrally managed Optimizer version from utilities.

The shared rules enforce dependency convergence, release dependencies,
Checkstyle, source/Javadoc artifacts, 90% instruction coverage and SpotBugs.
No local coverage thresholds, scanner exclusions or skip profiles are added.
The PR profile also activates the parent's OWASP dependency check; the repository
contains an empty suppression file, with no vulnerabilities suppressed.

Java sources now use the shared indentation, explicit imports, braces and API
documentation conventions. The additional tests cover annotation defaults,
adapter process failures, source constants, catalog boundaries and CLI flows.
They also exposed static-field metadata extraction that needed to handle the
parser's field-declaration node. Static analysis fixes make resource cache
directories explicit, narrow resource exception handling while retaining error
redaction, and handle filesystem-root workspace names and export directories.

## Local bootstrap and checks

The root parent and `utilities.version` both use the published utilities build
`0.0.0-SNAPSHOT-a41f211-40328682308`, produced from commit `a41f211`.
`shared-static-data.version` follows `utilities.version`, keeping the shared
Checkstyle and SpotBugs resources aligned with the parent. Maven settings need
authenticated access to `https://maven.pkg.github.com/CyborgCodeSyndicate/utilities`
using server ID `github-utilities`. The shared CI setup action configures this
repository using `GH_PACKAGES_USER`/`GH_PACKAGES_PAT` when configured, or the
GitHub Actions actor/token otherwise. Cross-repository access must be allowed
for the utilities packages when using the Actions token.

With JDK 21 on PATH, run in the Optimizer checkout (Windows: use `mvnw.cmd`):

```sh
./mvnw -B -ntp clean install
./mvnw -B -ntp help:effective-pom -Doutput=target/effective-pom.xml
java scripts/CheckMavenInheritance.java target/effective-pom.xml
```

The inheritance check inspects both the declared POMs and all five effective
projects. It rejects local dependency/plugin versions, missing shared plugins,
changed coverage gates, wrong Java targets and stale sibling dependency versions.

JaCoCo reports use the parent's `target/site/jacoco/ut/` location. The parent
also supplies the corresponding Sonar coverage report path. Surefire and
SpotBugs reports retain the shared conventions.

## Hosted pipelines and releases

- `ci.yml` keeps the existing operating-system matrix and non-Maven integration
  checks, using the shared utilities Maven setup action and inheritance check.
- `pr-validator.yml` invokes the shared PR pipeline for Maven verification,
  reporting, OWASP and SonarCloud.
  SonarScanner uses the JDK 21 installed by the shared setup action through
  `SONAR_SCANNER_SKIP_JRE_PROVISIONING=true`. This avoids an additional JRE
  metadata download; analysis, authentication and the quality gate remain active.
  A preflight checks for a missing `SONAR_TOKEN` and validates personal tokens
  with bearer authentication before the Maven/security build. Organization-scoped
  analysis tokens are validated by the scanner rather than a user-token API.
  See [Sonar's scanner configuration](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/analysis-parameters/parameters-not-settable-in-ui).
- `deploy.yml` delegates versioning, tagging and GitHub Packages deployment to
  the shared deployment pipeline.
- `deploy-maven-central.yml` delegates tagged-release publication to the shared
  Central pipeline. The selected modules include the reactor parent and all
  four JAR modules because their published POMs inherit the reactor parent.
- `codeql.yml` follows the ROA manual-build pattern with Java 21 and the shared
  Maven setup action.

The workflow actions still reference
`feature/pandora-inspector-and-version-updates`. The utilities build consumed
here is a development version. Before a production release, publish a stable
utilities parent and static-data version, update both the Optimizer parent and
`utilities.version`, and pin workflow references to the corresponding release tag.

GitHub Packages can use `GH_PACKAGES_USER` and `GH_PACKAGES_PAT`, or the workflow
token with access to the utilities packages. PR analysis needs
`NVD_API_KEY`, `SONAR_TOKEN` and the SonarCloud project
`CyborgCodeSyndicate_pandora-optimizer` in `cyborgcodesyndicate`. Central needs
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_TOKEN`, `GPG_PRIVATE_KEY` and
`GPG_PASSPHRASE`. These are repository configuration prerequisites; no secrets
are stored or provisioned by this change.

Local verification does not publish artifacts, dispatch hosted workflows, or
claim an authenticated NVD/SonarCloud/CodeQL run. Deployment workflows were
validated statically and remain reviewable on the branches.
