package io.cyborgcode.pandora.java;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.*;
import io.cyborgcode.pandora.model.*;
import io.cyborgcode.pandora.model.Parameter;
import io.cyborgcode.pandora.spi.LanguageAdapter;
import io.cyborgcode.pandora.util.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Static extraction. Target classes and custom rules are never loaded or invoked. */
public final class JavaAdapter implements LanguageAdapter {
    private static final Set<String> PACKAGES =
            Set.of(
                    "io.cyborgcode.pandora.annotations",
                    "io.cyborgcode.pandora.annotation",
                    "io.cyborgcode.pandora.aiteacher.annotation");
    private static final Set<String> MARKERS =
            Set.of("AiLesson", "AiCompass", "AiCompassOptions", "Pandora", "PandoraOptions");

    public String language() {
        return "java";
    }

    public ScanResult scan(ScanRequest request) throws Exception {
        if (request.protocolVersion() != 1) throw new IOException("Unsupported adapter protocol");
        return new Session(request).scan();
    }

    private static final class Session {
        private final ScanRequest request;
        private final Path root;
        private final List<Symbol> symbols = new ArrayList<>();
        private final List<Diagnostic> diagnostics = new ArrayList<>();
        private CompilationUnit unit;
        private String path, hash;
        private final Set<String> unresolved = new HashSet<>();

        Session(ScanRequest request) {
            this.request = request;
            root = Path.of(request.root()).toAbsolutePath().normalize();
        }

        ScanResult scan() throws Exception {
            CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
            for (String source : request.sourceRoots())
                solver.add(new JavaParserTypeSolver(root.resolve(source)));
            for (String ref : request.references())
                solver.add(new JarTypeSolver(root.resolve(ref)));
            JavaParser parser =
                    new JavaParser(
                            new ParserConfiguration()
                                    .setLanguageLevel(
                                            ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                                    .setSymbolResolver(new JavaSymbolSolver(solver)));
            for (Path file : SourceFiles.list(root, request.sourceRoots(), ".java")) {
                path = root.relativize(file).toString().replace('\\', '/');
                hash = Hashes.sha256(Files.readAllBytes(file));
                ParseResult<CompilationUnit> parsed = parser.parse(file);
                if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
                    diagnostics.add(
                            Diagnostic.error(
                                    "JAVA_PARSE", "Java source could not be parsed", path));
                    continue;
                }
                unit = parsed.getResult().get();
                for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) scanType(type);
            }
            return new ScanResult(1, symbols, diagnostics);
        }

        String id(String nativeId) {
            return "java:" + request.workspaceId() + ":" + request.moduleId() + ":" + nativeId;
        }

        String qualified(TypeDeclaration<?> t) {
            List<String> names = new ArrayList<>();
            Node current = t;
            while (current instanceof TypeDeclaration<?> d) {
                names.add(0, d.getNameAsString());
                current = d.getParentNode().orElse(null);
            }
            String pkg =
                    unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("");
            return pkg + String.join(".", names);
        }

        SourceLocation location(Node n) {
            return new SourceLocation(
                    path,
                    n.getBegin().map(p -> p.line).orElse(1),
                    n.getEnd().map(p -> p.line).orElse(1),
                    hash);
        }

        void scanType(TypeDeclaration<?> t) {
            String q = qualified(t),
                    parent =
                            t.findAncestor(TypeDeclaration.class)
                                    .map(x -> id(qualified(x)))
                                    .orElse(null);
            String kind =
                    t instanceof EnumDeclaration
                            ? "ENUM"
                            : t instanceof AnnotationDeclaration
                                    ? "ANNOTATION"
                                    : t instanceof RecordDeclaration
                                            ? "RECORD"
                                            : t instanceof ClassOrInterfaceDeclaration c
                                                            && c.isInterface()
                                                    ? "INTERFACE"
                                                    : "CLASS";
            List<String> values =
                    t instanceof EnumDeclaration e
                            ? e.getEntries().stream()
                                    .map(EnumConstantDeclaration::getNameAsString)
                                    .toList()
                            : List.of();
            Compass guidance = compass(t);
            symbols.add(
                    new Symbol(
                            id(q),
                            "java",
                            request.moduleId(),
                            kind,
                            t.getNameAsString(),
                            q,
                            q,
                            parent,
                            null,
                            List.of(),
                            location(t),
                            lesson(t),
                            guidance,
                            values,
                            "resolved"));
            for (BodyDeclaration<?> member : t.getMembers()) {
                if (member instanceof CallableDeclaration<?> callable) {
                    boolean annotated =
                            hasGuidance(callable)
                                    || callable.getParameters().stream()
                                            .anyMatch(this::hasGuidance);
                    if (annotated
                            || guidance != null
                                    && (callable.isPublic()
                                            || callable.isProtected()
                                            || kind.equals("INTERFACE"))) scanCallable(q, callable);
                } else if (member instanceof FieldDeclaration field
                        && (hasGuidance(field) || guidance != null && field.isPublic())) {
                    for (VariableDeclarator variable : field.getVariables()) {
                        String type = typeName(variable.getType());
                        symbols.add(
                                new Symbol(
                                        id(q + "#" + variable.getNameAsString()),
                                        "java",
                                        request.moduleId(),
                                        "FIELD",
                                        variable.getNameAsString(),
                                        q + "." + variable.getNameAsString(),
                                        type + " " + variable.getNameAsString(),
                                        id(q),
                                        type,
                                        List.of(),
                                        location(variable),
                                        lesson(field),
                                        compass(field),
                                        List.of(),
                                        resolution(type)));
                    }
                } else if (member instanceof AnnotationMemberDeclaration a
                        && (hasGuidance(a) || guidance != null)) {
                    String type = typeName(a.getType()),
                            nativeId = q + "#" + a.getNameAsString() + "()";
                    symbols.add(
                            new Symbol(
                                    id(nativeId),
                                    "java",
                                    request.moduleId(),
                                    "METHOD",
                                    a.getNameAsString(),
                                    q + "." + a.getNameAsString(),
                                    a.getNameAsString() + "()",
                                    id(q),
                                    type,
                                    List.of(),
                                    location(a),
                                    lesson(a),
                                    compass(a),
                                    List.of(),
                                    resolution(type)));
                }
            }
            if (t instanceof RecordDeclaration record)
                for (var p : record.getParameters())
                    if (hasGuidance(p)) {
                        String type = typeName(p.getType());
                        symbols.add(
                                new Symbol(
                                        id(q + "#" + p.getNameAsString()),
                                        "java",
                                        request.moduleId(),
                                        "PROPERTY",
                                        p.getNameAsString(),
                                        q + "." + p.getNameAsString(),
                                        type + " " + p.getNameAsString(),
                                        id(q),
                                        type,
                                        List.of(),
                                        location(p),
                                        lesson(p),
                                        compass(p),
                                        List.of(),
                                        resolution(type)));
                    }
        }

        void scanCallable(String owner, CallableDeclaration<?> c) {
            List<Parameter> params = new ArrayList<>();
            for (var p : c.getParameters()) {
                String type = typeName(p.getType()) + (p.isVarArgs() ? "[]" : "");
                params.add(
                        new Parameter(
                                params.size(),
                                p.getNameAsString(),
                                type,
                                false,
                                p.isVarArgs(),
                                compass(p)));
            }
            String name = c instanceof ConstructorDeclaration ? "<init>" : c.getNameAsString();
            String signature =
                    name
                            + "("
                            + String.join(",", params.stream().map(Parameter::type).toList())
                            + ")";
            String returns = c instanceof MethodDeclaration m ? typeName(m.getType()) : owner;
            boolean resolved =
                    params.stream().noneMatch(p -> unresolved.contains(p.type().replace("[]", "")))
                            && !unresolved.contains(returns);
            symbols.add(
                    new Symbol(
                            id(owner + "#" + signature),
                            "java",
                            request.moduleId(),
                            c instanceof ConstructorDeclaration ? "CONSTRUCTOR" : "METHOD",
                            c.getNameAsString(),
                            owner + "." + name,
                            signature,
                            id(owner),
                            returns,
                            params,
                            location(c),
                            lesson(c),
                            compass(c),
                            List.of(),
                            resolved ? "resolved" : "partial"));
        }

        String resolution(String type) {
            return unresolved.contains(type) ? "partial" : "resolved";
        }

        String typeName(Type t) {
            try {
                return t.resolve().describe();
            } catch (RuntimeException e) {
                String value = t.toString();
                if (unresolved.add(value))
                    diagnostics.add(
                            Diagnostic.warning(
                                    "JAVA_TYPE_UNRESOLVED",
                                    "Type could not be resolved: " + value,
                                    path));
                return value;
            }
        }

        String marker(AnnotationExpr a) {
            String name = a.getNameAsString(), simple = name.substring(name.lastIndexOf('.') + 1);
            if (!MARKERS.contains(simple)) return "";
            if (name.contains("."))
                return PACKAGES.stream().anyMatch(p -> name.equals(p + "." + simple)) ? simple : "";
            boolean imported =
                    unit.getImports().stream()
                            .anyMatch(
                                    i ->
                                            !i.isStatic()
                                                    && (i.isAsterisk()
                                                            ? PACKAGES.contains(i.getNameAsString())
                                                            : PACKAGES.stream()
                                                                    .anyMatch(
                                                                            p ->
                                                                                    i.getNameAsString()
                                                                                            .equals(
                                                                                                    p
                                                                                                            + "."
                                                                                                            + simple))));
            boolean same =
                    unit.getPackageDeclaration()
                            .map(p -> PACKAGES.contains(p.getNameAsString()))
                            .orElse(false);
            return imported || same ? simple : "";
        }

        boolean hasGuidance(NodeWithAnnotations<?> node) {
            return node.getAnnotations().stream().anyMatch(a -> !marker(a).isEmpty());
        }

        List<AnnotationExpr> annotations(NodeWithAnnotations<?> n, String... names) {
            Set<String> wanted = Set.of(names);
            return n.getAnnotations().stream().filter(a -> wanted.contains(marker(a))).toList();
        }

        Map<String, Expression> fields(AnnotationExpr a) {
            Map<String, Expression> fields = new LinkedHashMap<>();
            if (a instanceof NormalAnnotationExpr normal)
                normal.getPairs().forEach(p -> fields.put(p.getNameAsString(), p.getValue()));
            else if (a instanceof SingleMemberAnnotationExpr single)
                fields.put("value", single.getMemberValue());
            return fields;
        }

        String text(Expression e, String fallback) {
            if (e == null) return fallback;
            if (e instanceof StringLiteralExpr s) return s.asString();
            if (e instanceof TextBlockLiteralExpr s) return s.asString();
            if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS)
                return text(b.getLeft(), "") + text(b.getRight(), "");
            // Resolve source constants, without evaluating arbitrary target code.
            try {
                Node node =
                        e instanceof NameExpr n
                                ? n.resolve().toAst().orElse(null)
                                : e instanceof FieldAccessExpr f
                                        ? f.resolve().toAst().orElse(null)
                                        : null;
                if (node instanceof VariableDeclarator v
                        && v.getInitializer().isPresent()
                        && v.getInitializer().get() != e)
                    return literalConstant(v.getInitializer().get(), fallback, 0);
            } catch (RuntimeException ignored) {
                /* report below */
            }
            diagnostics.add(
                    Diagnostic.error(
                            "JAVA_METADATA_UNRESOLVED",
                            "Metadata must be a literal or resolvable string constant",
                            path));
            return fallback;
        }

        String literalConstant(Expression e, String fallback, int depth) {
            if (depth > 8) return fallback;
            if (e instanceof StringLiteralExpr s) return s.asString();
            if (e instanceof TextBlockLiteralExpr s) return s.asString();
            if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS)
                return literalConstant(b.getLeft(), fallback, depth + 1)
                        + literalConstant(b.getRight(), fallback, depth + 1);
            diagnostics.add(
                    Diagnostic.error(
                            "JAVA_METADATA_UNRESOLVED",
                            "Unsupported constant metadata expression",
                            path));
            return fallback;
        }

        List<String> strings(Expression e) {
            if (e == null) return List.of();
            if (e instanceof ArrayInitializerExpr array)
                return array.getValues().stream().map(v -> text(v, "")).toList();
            return List.of(text(e, ""));
        }

        String enumName(Expression e, String fallback) {
            if (e == null) return fallback;
            if (e instanceof FieldAccessExpr f) return f.getNameAsString();
            if (e instanceof NameExpr n) return n.getNameAsString();
            diagnostics.add(
                    Diagnostic.error(
                            "JAVA_METADATA_UNRESOLVED", "Expected an enum constant", path));
            return fallback;
        }

        Lesson lesson(NodeWithAnnotations<?> n) {
            var annotations = annotations(n, "AiLesson");
            if (annotations.isEmpty()) return null;
            var f = fields(annotations.getFirst());
            return new Lesson(
                    text(f.get("category"), ""),
                    enumName(f.get("level"), "GOOD"),
                    text(f.get("description"), ""),
                    text(f.get("whenToUse"), ""),
                    strings(f.get("tags")),
                    strings(f.get("related")));
        }

        Compass compass(NodeWithAnnotations<?> n) {
            var primary = annotations(n, "AiCompass", "Pandora");
            var optionAnnotations = annotations(n, "AiCompassOptions", "PandoraOptions");
            if (primary.isEmpty() && optionAnnotations.isEmpty()) return null;
            if (primary.size() > 1)
                diagnostics.add(
                        Diagnostic.error(
                                "DUPLICATE_GUIDANCE", "Conflicting Compass annotations", path));
            Map<String, Expression> f = primary.isEmpty() ? Map.of() : fields(primary.getFirst());
            List<OptionSpec> options = new ArrayList<>();
            List<String> examples = new ArrayList<>(strings(f.get("examples")));
            for (var a : optionAnnotations) {
                var o = fields(a);
                String enumType = null;
                if (o.get("enumType") instanceof ClassExpr c) enumType = typeName(c.getType());
                if (o.containsKey("availableOptionsRule"))
                    diagnostics.add(
                            Diagnostic.error(
                                    "RUNTIME_RULE_UNSUPPORTED",
                                    "Legacy executable options rules require migration to declarative options",
                                    path));
                if (o.containsKey("exampleFilesPath"))
                    examples.add(text(o.get("exampleFilesPath"), ""));
                options.add(
                        new OptionSpec(
                                strings(o.get("values")),
                                text(o.get("source"), ""),
                                text(o.get("pointer"), ""),
                                enumType));
            }
            return new Compass(
                    text(f.get("description"), ""),
                    strings(f.get("tags")),
                    enumName(f.get("creation"), "AUTO"),
                    enumName(f.get("preference"), "AUTO"),
                    examples,
                    options);
        }
    }
}
