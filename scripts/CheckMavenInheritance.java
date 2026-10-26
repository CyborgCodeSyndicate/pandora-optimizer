import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.*;
import org.w3c.dom.*;

/** Checks declared version ownership and the actual effective Maven configuration. */
class CheckMavenInheritance {
    static final XPath XPATH = XPathFactory.newInstance().newXPath();
    static final List<String> MODULES = List.of(".", "annotations/java", "engine", "adapters/java", "cli");
    static final Set<String> ARTIFACTS = Set.of("pandora-optimizer", "pandora-annotations", "pandora-engine", "pandora-adapter-java", "pandora-cli");

    static Document read(Path file) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(file.toFile());
    }
    static String text(Node node, String query) throws Exception {
        return XPATH.evaluate(query, node).trim();
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    static String managed(Node project, String artifact) throws Exception {
        return text(project, "dependencyManagement/dependencies/dependency[artifactId='" + artifact + "']/version");
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: java scripts/CheckMavenInheritance.java <effective-pom.xml>");
        for (String module : MODULES) {
            var declared = read(Path.of(module, "pom.xml"));
            require(text(declared, "count(//dependency/version)").equals("0"), module + " declares a dependency version");
            require(text(declared, "count(//plugin/version)").equals("0"), module + " declares a plugin version");
        }
        var document = read(Path.of(args[0]));
        var projects = (NodeList) XPATH.evaluate("/projects/project | /project", document, XPathConstants.NODESET);
        Set<String> found = new HashSet<>();
        for (int i = 0; i < projects.getLength(); i++) {
            Node project = projects.item(i);
            String artifact = text(project, "artifactId");
            if (!ARTIFACTS.contains(artifact)) continue;
            found.add(artifact);
            String release = artifact.equals("pandora-annotations") ? "17" : "21";
            require(text(project, "properties/java.version").equals(release), artifact + " has the wrong Java target");
            require(text(project, "properties/maven.compiler.release").equals(release), artifact + " has the wrong compiler release");
            require(text(project, "properties/jacoco.minimum.coverage").equals("0.90"), artifact + " changed the coverage gate");
            for (String dependency : List.of("jackson-databind", "json-schema-validator", "picocli", "javaparser-symbol-solver-core", "slf4j-nop", "junit-jupiter")) {
                require(!managed(project, dependency).isBlank(), artifact + " does not manage " + dependency);
            }
            require(managed(project, "slf4j-api").equals(managed(project, "slf4j-nop")), "SLF4J artifacts are not aligned");
            for (String dependency : List.of("pandora-annotations", "pandora-engine", "pandora-adapter-java", "pandora-cli")) {
                require(managed(project, dependency).equals(text(project, "version")), artifact + " has a stale reactor dependency: " + dependency);
            }
            for (String plugin : List.of("maven-enforcer-plugin", "maven-checkstyle-plugin", "maven-compiler-plugin", "maven-surefire-plugin", "maven-javadoc-plugin", "maven-source-plugin", "jacoco-maven-plugin", "spotbugs-maven-plugin")) {
                String base = "build/plugins/plugin[artifactId='" + plugin + "']";
                require(!text(project, base + "/version").isBlank(), artifact + " does not inherit " + plugin);
            }
        }
        require(found.equals(ARTIFACTS), "Effective POM must contain all five Maven projects: " + found);
        System.out.println("PASS: all five Maven projects inherit managed dependencies, plugins and quality gates.");
    }
}
