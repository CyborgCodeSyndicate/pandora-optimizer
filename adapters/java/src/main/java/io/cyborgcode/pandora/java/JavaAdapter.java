package io.cyborgcode.pandora.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver;
import io.cyborgcode.pandora.model.Compass;
import io.cyborgcode.pandora.model.Diagnostic;
import io.cyborgcode.pandora.model.Lesson;
import io.cyborgcode.pandora.model.OptionSpec;
import io.cyborgcode.pandora.model.Parameter;
import io.cyborgcode.pandora.model.ScanRequest;
import io.cyborgcode.pandora.model.ScanResult;
import io.cyborgcode.pandora.model.SourceLocation;
import io.cyborgcode.pandora.model.Symbol;
import io.cyborgcode.pandora.spi.LanguageAdapter;
import io.cyborgcode.pandora.util.Hashes;
import io.cyborgcode.pandora.util.SourceFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Static extraction. Target classes and custom rules are never loaded or invoked.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class JavaAdapter implements LanguageAdapter {

   private static final String JAVA_METADATA_UNRESOLVED = "JAVA_METADATA_UNRESOLVED";

   private static final String RESOLVED = "resolved";

   private static final Set<String> PACKAGES =
         Set.of(
               "io.cyborgcode.pandora.annotations",
               "io.cyborgcode.pandora.annotation",
               "io.cyborgcode.pandora.aiteacher.annotation");

   private static final Set<String> MARKERS =
         Set.of("AiLesson", "AiCompass", "AiCompassOptions", "Pandora", "PandoraOptions");

   @Override
   public String language() {
      return "java";
   }

   @Override
   public ScanResult scan(ScanRequest request) throws IOException {
      if (request.protocolVersion() != 1) {
         throw new IOException("Unsupported adapter protocol");
      }
      return new Session(request).scan();
   }

   private static final class Session {

      private final ScanRequest request;

      private final Path root;

      private final List<Symbol> symbols = new ArrayList<>();

      private final List<Diagnostic> diagnostics = new ArrayList<>();

      private CompilationUnit unit;

      private String path;

      private String hash;

      private final Set<String> unresolved = new HashSet<>();

      Session(ScanRequest request) throws IOException {
         this.request = request;
         root = Path.of(request.root()).toRealPath();
      }

      ScanResult scan() throws IOException {
         CombinedTypeSolver solver = new CombinedTypeSolver(new ReflectionTypeSolver(false));
         for (String source : request.sourceRoots()) {
            solver.add(new JavaParserTypeSolver(root.resolve(source)));
         }
         for (String ref : request.references()) {
            solver.add(new JarTypeSolver(root.resolve(ref)));
         }
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
            for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
               scanType(type);
            }
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
         String q = qualified(t);
         String parent =
               t.findAncestor(TypeDeclaration.class).map(x -> id(qualified(x))).orElse(null);
         String kind = typeKind(t);
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
                     RESOLVED,
                     supertypes(t)));
         for (BodyDeclaration<?> member : t.getMembers()) {
            scanMember(q, member, guidance != null, kind);
         }
         if (t instanceof RecordDeclaration recordDeclaration) {
            scanRecordProperties(q, recordDeclaration);
         }
      }

      String typeKind(TypeDeclaration<?> type) {
         if (type instanceof EnumDeclaration) {
            return "ENUM";
         }
         if (type instanceof AnnotationDeclaration) {
            return "ANNOTATION";
         }
         if (type instanceof RecordDeclaration) {
            return "RECORD";
         }
         if (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isInterface()) {
            return "INTERFACE";
         }
         return "CLASS";
      }

      void scanMember(String q, BodyDeclaration<?> member, boolean guidedType, String kind) {
         if (member instanceof CallableDeclaration<?> callable) {
            if (includeCallable(callable, guidedType, kind)) {
               scanCallable(q, callable);
            }
         } else if (member instanceof FieldDeclaration field
               && (hasGuidance(field) || guidedType && field.isPublic())) {
            scanField(q, field);
         } else if (member instanceof AnnotationMemberDeclaration a
               && (hasGuidance(a) || guidedType)) {
            scanAnnotationMember(q, a);
         }
      }

      boolean includeCallable(CallableDeclaration<?> callable, boolean guidedType, String kind) {
         boolean annotated = hasGuidance(callable)
               || callable.getParameters().stream().anyMatch(this::hasGuidance);
         boolean visible = callable.isPublic() || callable.isProtected() || kind.equals("INTERFACE");
         return annotated || guidedType && visible;
      }

      void scanField(String q, FieldDeclaration field) {
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
      }

      void scanAnnotationMember(String q, AnnotationMemberDeclaration a) {
         String type = typeName(a.getType());
         String nativeId = q + "#" + a.getNameAsString() + "()";
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

      void scanRecordProperties(String q, RecordDeclaration declaration) {
         for (var p : declaration.getParameters()) {
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
      }

      List<String> supertypes(TypeDeclaration<?> type) {
         List<com.github.javaparser.ast.type.ClassOrInterfaceType> bases = new ArrayList<>();
         if (type instanceof ClassOrInterfaceDeclaration c) {
            bases.addAll(c.getExtendedTypes());
            bases.addAll(c.getImplementedTypes());
         } else if (type instanceof EnumDeclaration e) {
            bases.addAll(e.getImplementedTypes());
         } else if (type instanceof RecordDeclaration r) {
            bases.addAll(r.getImplementedTypes());
         }
         return bases.stream().map(this::declaredTypeName).distinct().toList();
      }

      String declaredTypeName(Type type) {
         try {
            return type.resolve().describe().replaceAll("<.*>", "");
         } catch (RuntimeException e) {
            String name = type.toString().replaceAll("<.*>", "");
            if (name.contains(".")) {
               return name;
            }
            for (var i : unit.getImports()) {
               if (!i.isStatic()
                     && !i.isAsterisk()
                     && i.getName().getIdentifier().equals(name)) {
                  return i.getNameAsString();
               }
            }
            return unit.getPackageDeclaration().map(p -> p.getNameAsString() + ".").orElse("")
                  + name;
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
                     resolved ? RESOLVED : "partial"));
      }

      String resolution(String type) {
         return unresolved.contains(type) ? "partial" : RESOLVED;
      }

      String typeName(Type t) {
         try {
            return t.resolve().describe();
         } catch (RuntimeException e) {
            String value = t.toString();
            if (unresolved.add(value)) {
               diagnostics.add(
                     Diagnostic.warning(
                           "JAVA_TYPE_UNRESOLVED",
                           "Type could not be resolved: " + value,
                           path));
            }
            return value;
         }
      }

      String marker(AnnotationExpr a) {
         String name = a.getNameAsString();
         String simple = name.substring(name.lastIndexOf('.') + 1);
         if (!MARKERS.contains(simple)) {
            return "";
         }
         if (name.contains(".")) {
            return PACKAGES.stream().anyMatch(p -> name.equals(p + "." + simple)) ? simple : "";
         }
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
         if (a instanceof NormalAnnotationExpr normal) {
            normal.getPairs().forEach(p -> fields.put(p.getNameAsString(), p.getValue()));
         } else if (a instanceof SingleMemberAnnotationExpr single) {
            fields.put("value", single.getMemberValue());
         }
         return fields;
      }

      String text(Expression e, String fallback) {
         if (e == null) {
            return fallback;
         }
         if (e instanceof StringLiteralExpr s) {
            return s.asString();
         }
         if (e instanceof TextBlockLiteralExpr s) {
            return s.asString();
         }
         if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            return text(b.getLeft(), "") + text(b.getRight(), "");
         }
         // Resolve source constants, without evaluating arbitrary target code.
         try {
            Node node = constantDeclaration(e);
            if (node instanceof FieldDeclaration field) {
               String name = e instanceof NameExpr n ? n.getNameAsString()
                     : ((FieldAccessExpr) e).getNameAsString();
               node = field.getVariables().stream()
                     .filter(variable -> variable.getNameAsString().equals(name))
                     .findFirst().orElse(null);
            }
            if (node instanceof VariableDeclarator v
                  && v.getInitializer().isPresent()
                  && v.getInitializer().get() != e) {
               return literalConstant(v.getInitializer().get(), fallback, 0);
            }
         } catch (RuntimeException ignored) {
            /* report below */
         }
         diagnostics.add(
               Diagnostic.error(
                     JAVA_METADATA_UNRESOLVED,
                     "Metadata must be a literal or resolvable string constant",
                     path));
         return fallback;
      }

      Node constantDeclaration(Expression expression) {
         if (expression instanceof NameExpr name) {
            return name.resolve().toAst().orElse(null);
         }
         if (expression instanceof FieldAccessExpr field) {
            return field.resolve().toAst().orElse(null);
         }
         return null;
      }

      String literalConstant(Expression e, String fallback, int depth) {
         if (depth > 8) {
            return fallback;
         }
         if (e instanceof StringLiteralExpr s) {
            return s.asString();
         }
         if (e instanceof TextBlockLiteralExpr s) {
            return s.asString();
         }
         if (e instanceof BinaryExpr b && b.getOperator() == BinaryExpr.Operator.PLUS) {
            return literalConstant(b.getLeft(), fallback, depth + 1)
                  + literalConstant(b.getRight(), fallback, depth + 1);
         }
         diagnostics.add(
               Diagnostic.error(
                     JAVA_METADATA_UNRESOLVED,
                     "Unsupported constant metadata expression",
                     path));
         return fallback;
      }

      List<String> strings(Expression e) {
         if (e == null) {
            return List.of();
         }
         if (e instanceof ArrayInitializerExpr array) {
            return array.getValues().stream().map(v -> text(v, "")).toList();
         }
         return List.of(text(e, ""));
      }

      String enumName(Expression e, String fallback) {
         if (e == null) {
            return fallback;
         }
         if (e instanceof FieldAccessExpr f) {
            return f.getNameAsString();
         }
         if (e instanceof NameExpr n) {
            return n.getNameAsString();
         }
         diagnostics.add(
               Diagnostic.error(
                     JAVA_METADATA_UNRESOLVED, "Expected an enum constant", path));
         return fallback;
      }

      Lesson lesson(NodeWithAnnotations<?> n) {
         var annotations = annotations(n, "AiLesson");
         if (annotations.isEmpty()) {
            return null;
         }
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
         if (primary.isEmpty() && optionAnnotations.isEmpty()) {
            return null;
         }
         if (primary.size() > 1) {
            diagnostics.add(
                  Diagnostic.error(
                        "DUPLICATE_GUIDANCE", "Conflicting Compass annotations", path));
         }
         Map<String, Expression> f = primary.isEmpty() ? Map.of() : fields(primary.getFirst());
         List<OptionSpec> options = new ArrayList<>();
         Map<String, Object> extra = new LinkedHashMap<>();
         List<String> examples = new ArrayList<>(strings(f.get("examples")));
         for (var a : optionAnnotations) {
            options.add(option(a, extra, examples));
         }
         return new Compass(
               text(f.get("description"), ""),
               strings(f.get("tags")),
               enumName(f.get("creation"), "AUTO"),
               enumName(f.get("preference"), "AUTO"),
               examples,
               options,
               extra);
      }

      OptionSpec option(AnnotationExpr a, Map<String, Object> extra, List<String> examples) {
         var o = fields(a);
         String enumType = null;
         String implementationsOf = null;
         if (o.get("enumType") instanceof ClassExpr c) {
            enumType = declaredTypeName(c.getType());
         }
         if (o.get("implementationsOf") instanceof ClassExpr c) {
            implementationsOf = declaredTypeName(c.getType());
         }
         addMetadata(o.get("meta"), extra);
         if (o.containsKey("availableOptionsRule")) {
            diagnostics.add(
                  Diagnostic.error(
                        "RUNTIME_RULE_UNSUPPORTED",
                        "Legacy executable options rules require migration to"
                              + " declarative options",
                        path));
         }
         if (o.containsKey("exampleFilesPath")) {
            examples.add(text(o.get("exampleFilesPath"), ""));
         }
         return new OptionSpec(
                     strings(o.get("values")),
                     text(o.get("source"), ""),
                     text(o.get("pointer"), ""),
                     enumType,
                     implementationsOf,
                     text(o.get("render"), "NAME"));
      }

      void addMetadata(Expression metadata, Map<String, Object> extra) {
         List<Expression> entries = List.of();
         if (metadata instanceof ArrayInitializerExpr array) {
            entries = array.getValues();
         } else if (metadata != null) {
            entries = List.of(metadata);
         }
         for (Expression entry : entries) {
            if (entry instanceof AnnotationExpr meta) {
               var pair = fields(meta);
               String key = text(pair.get("key"), "");
               if (!key.isBlank()) {
                  extra.put(key, text(pair.get("value"), ""));
               }
            }
         }
      }
   }
}
