using System.Security.Cryptography;
using Microsoft.CodeAnalysis;
using Microsoft.CodeAnalysis.CSharp;
using Microsoft.CodeAnalysis.CSharp.Syntax;
using Pandora.Optimizer.Annotations;
namespace Pandora.Optimizer.DotNet;

/// <summary>Reads syntax and metadata references without loading target code or running MSBuild.</summary>
public sealed class CSharpAdapter
{
    private const string AnnotationNamespace = "Pandora.Optimizer.Annotations.";
    private static readonly HashSet<string> Excluded = new(StringComparer.OrdinalIgnoreCase) { ".git", ".idea", ".vs", "bin", "obj", "target", "node_modules", "generated", "cache" };
    private readonly List<Diagnostic> diagnostics = [];
    private ScanRequest request = null!;
    private static string TypeName(ITypeSymbol t) => t.ToDisplayString(SymbolDisplayFormat.FullyQualifiedFormat).Replace("global::", "");
    private string Id(string native) => "csharp:" + request.WorkspaceId + ":" + request.ModuleId + ":" + native;
    public ScanResult Scan(ScanRequest input)
    {
        request = input; diagnostics.Clear();
        if (input.ProtocolVersion != 1) throw new InvalidDataException("Unsupported protocol");
        string root = Path.GetFullPath(input.Root);
        var files = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var source in input.SourceRoots)
        {
            string start = Path.GetFullPath(Path.Combine(root, source));
            if (!Contained(root, start) || !Directory.Exists(start)) throw new InvalidDataException("Invalid source root");
            var ancestor = new DirectoryInfo(start);
            while (ancestor is not null && Contained(root, ancestor.FullName))
            {
                if ((ancestor.Attributes & FileAttributes.ReparsePoint) != 0)
                    throw new InvalidDataException("Source directory symlinks are not supported");
                if (ancestor.FullName == root) break;
                ancestor = ancestor.Parent;
            }
            foreach (var file in Walk(start)) if (file.EndsWith(".cs", StringComparison.OrdinalIgnoreCase)) files.Add(file);
        }
        var trees = files.Select(file => CSharpSyntaxTree.ParseText(File.ReadAllText(file), new CSharpParseOptions(LanguageVersion.CSharp12), file)).ToArray();
        string[] trusted = ((string?)AppContext.GetData("TRUSTED_PLATFORM_ASSEMBLIES") ?? "").Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries);
        var references = trusted.Concat([typeof(AiCompassAttribute).Assembly.Location]).Concat(input.References.Select(p => Path.GetFullPath(Path.Combine(root, p))))
            .Distinct(StringComparer.OrdinalIgnoreCase).Select(p => MetadataReference.CreateFromFile(p));
        var compilation = CSharpCompilation.Create("PandoraInspection", trees, references, new CSharpCompilationOptions(OutputKind.DynamicallyLinkedLibrary));
        var output = new Dictionary<string, Symbol>(StringComparer.Ordinal);
        foreach (var tree in trees)
        {
            string path = Path.GetRelativePath(root, tree.FilePath).Replace('\\', '/');
            string hash = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(tree.FilePath))).ToLowerInvariant();
            foreach (var error in tree.GetDiagnostics().Where(d => d.Severity == DiagnosticSeverity.Error))
                diagnostics.Add(new("CSHARP_PARSE", "error", error.Id + ": " + error.GetMessage(), path));
            var model = compilation.GetSemanticModel(tree);
            foreach (var declaration in tree.GetRoot().DescendantNodes().OfType<MemberDeclarationSyntax>())
            {
                ISymbol? symbol = declaration switch
                {
                    BaseTypeDeclarationSyntax t => model.GetDeclaredSymbol(t),
                    BaseMethodDeclarationSyntax m => model.GetDeclaredSymbol(m),
                    PropertyDeclarationSyntax p => model.GetDeclaredSymbol(p),
                    _ => null
                };
                if (symbol is not null) Add(symbol, declaration, path, hash, output);
                if (declaration is FieldDeclarationSyntax field)
                    foreach (var variable in field.Declaration.Variables)
                        if (model.GetDeclaredSymbol(variable) is { } f) Add(f, variable, path, hash, output);
            }
            // Compilation errors are diagnostics, not permission to execute builds or generators.
            foreach (var d in model.GetDiagnostics().Where(d => d.Severity == DiagnosticSeverity.Error).Take(30))
                diagnostics.Add(new("CSHARP_SEMANTIC", "warning", d.Id + ": " + d.GetMessage(), path));
        }
        return new(1, output.Values.OrderBy(s => s.Id, StringComparer.Ordinal).ToArray(), diagnostics.Distinct().ToArray());
    }
    private void Add(ISymbol s, SyntaxNode node, string path, string hash, Dictionary<string, Symbol> output)
    {
        var attrs = s.GetAttributes();
        bool guided = attrs.Any(IsGuidance);
        bool parameterGuided = s is IMethodSymbol methodWithParams && methodWithParams.Parameters.Any(p => p.GetAttributes().Any(IsGuidance));
        bool ownerGuided = s.ContainingType?.GetAttributes().Any(IsGuidance) ?? false;
        if (s is not INamedTypeSymbol && !guided && !parameterGuided && !(ownerGuided && s.DeclaredAccessibility is Accessibility.Public or Accessibility.Protected)) return;
        string kind, name = s.Name, qualified, native; string? returnType = null, parent = null;
        Parameter[] parameters = []; string[] enumValues = []; string resolution = "resolved";
        if (s is INamedTypeSymbol type)
        {
            qualified = TypeName(type); native = qualified;
            kind = type.TypeKind switch { TypeKind.Enum => "ENUM", TypeKind.Interface => "INTERFACE", TypeKind.Struct => type.IsRecord ? "RECORD" : "STRUCT", _ => type.IsRecord ? "RECORD" : "CLASS" };
            if (type.ContainingType is { } enclosing) parent = Id(TypeName(enclosing));
            if (type.TypeKind == TypeKind.Enum) enumValues = type.GetMembers().OfType<IFieldSymbol>().Where(f => f.HasConstantValue).Select(f => f.Name).ToArray();
        }
        else if (s is IMethodSymbol method)
        {
            string owner = TypeName(method.ContainingType); parent = Id(owner);
            kind = method.MethodKind is MethodKind.Constructor or MethodKind.StaticConstructor ? "CONSTRUCTOR" : "METHOD";
            string methodName = method.MethodKind == MethodKind.StaticConstructor ? "<clinit>" : kind == "CONSTRUCTOR" ? "<init>" : method.Name + (method.Arity > 0 ? "<" + string.Join(",", method.TypeParameters.Select(t => t.Name)) + ">" : "");
            parameters = method.Parameters.Select(p => new Parameter(p.Ordinal, p.Name, (p.RefKind != RefKind.None ? p.RefKind.ToString().ToLowerInvariant() + " " : "") + TypeName(p.Type), p.IsOptional, p.IsParams, ReadCompass(p.GetAttributes(), path))).ToArray();
            native = owner + "#" + methodName + "(" + string.Join(",", parameters.Select(p => p.Type)) + ")"; qualified = owner + "." + methodName; returnType = TypeName(method.ReturnType);
            if (method.ReturnType.TypeKind == TypeKind.Error || method.Parameters.Any(p => p.Type.TypeKind == TypeKind.Error)) resolution = "partial";
        }
        else if (s is IFieldSymbol field)
        {
            kind = "FIELD"; qualified = TypeName(field.ContainingType) + "." + field.Name; native = TypeName(field.ContainingType) + "#" + field.Name; parent = Id(TypeName(field.ContainingType)); returnType = TypeName(field.Type);
            if (field.Type.TypeKind == TypeKind.Error) resolution = "partial";
        }
        else if (s is IPropertySymbol property)
        {
            kind = "PROPERTY"; qualified = TypeName(property.ContainingType) + "." + property.Name; native = TypeName(property.ContainingType) + "#" + property.Name; parent = Id(TypeName(property.ContainingType)); returnType = TypeName(property.Type);
            if (property.Type.TypeKind == TypeKind.Error) resolution = "partial";
        }
        else return;
        var span = node.GetLocation().GetLineSpan();
        var location = new SourceLocation(path, span.StartLinePosition.Line + 1, span.EndLinePosition.Line + 1, hash);
        string signature = s is IMethodSymbol ? native[(native.IndexOf('#') + 1)..] : qualified;
        var result = new Symbol(Id(native), "csharp", request.ModuleId, kind, name, qualified, signature, parent, returnType, parameters, location, ReadLesson(attrs, path), ReadCompass(attrs, path), enumValues, resolution);
        if (output.TryGetValue(result.Id, out var previous))
        {
            // Partial declarations share identity; combine only non-conflicting guidance.
            if (previous.Compass is not null && result.Compass is not null || previous.Lesson is not null && result.Lesson is not null)
                diagnostics.Add(new("DUPLICATE_GUIDANCE", "error", "Multiple guided partial declarations: " + qualified, path));
            output[result.Id] = result with { Compass = result.Compass ?? previous.Compass, Lesson = result.Lesson ?? previous.Lesson };
        }
        else output[result.Id] = result;
    }
    private static bool IsGuidance(AttributeData a) => a.AttributeClass?.ToDisplayString() is
        AnnotationNamespace + "AiLessonAttribute" or AnnotationNamespace + "AiCompassAttribute" or AnnotationNamespace + "AiCompassOptionsAttribute";
    private static AttributeData? Find(IEnumerable<AttributeData> attrs, string name) => attrs.FirstOrDefault(a => a.AttributeClass?.ToDisplayString() == AnnotationNamespace + name + "Attribute");
    private static TypedConstant? Get(AttributeData a, string name) => a.NamedArguments.FirstOrDefault(p => p.Key == name) is var pair && pair.Key is not null ? pair.Value : null;
    private string Text(AttributeData a, string name, string fallback, string path)
    {
        var value = Get(a, name); if (value is null) return fallback;
        if (value.Value.Kind == TypedConstantKind.Error) { diagnostics.Add(new("CSHARP_METADATA_UNRESOLVED", "error", "Invalid metadata value: " + name, path)); return fallback; }
        return value.Value.Value?.ToString() ?? fallback;
    }
    private string EnumName(AttributeData a, string name, string fallback, string path)
    {
        var value = Get(a, name); if (value is null) return fallback;
        if (value.Value.Type is INamedTypeSymbol type)
        {
            var field = type.GetMembers().OfType<IFieldSymbol>().FirstOrDefault(f => f.HasConstantValue && Equals(f.ConstantValue, value.Value.Value));
            if (field is not null) return field.Name;
        }
        diagnostics.Add(new("CSHARP_METADATA_UNRESOLVED", "error", "Invalid enum metadata: " + name, path)); return fallback;
    }
    private string[] Strings(AttributeData a, string name, string path)
    {
        var value = Get(a, name); if (value is null) return [];
        if (value.Value.Kind != TypedConstantKind.Array || value.Value.IsNull) { diagnostics.Add(new("CSHARP_METADATA_UNRESOLVED", "error", "Expected string array: " + name, path)); return []; }
        if (value.Value.Values.Any(v => v.Kind == TypedConstantKind.Error || v.Value is not string)) diagnostics.Add(new("CSHARP_METADATA_UNRESOLVED", "error", "Invalid array metadata: " + name, path));
        return value.Value.Values.Select(v => v.Value?.ToString() ?? "").ToArray();
    }
    private Lesson? ReadLesson(IEnumerable<AttributeData> attrs, string path)
    {
        var a = Find(attrs, "AiLesson"); return a is null ? null : new(Text(a, "Category", "", path), EnumName(a, "Level", "GOOD", path), Text(a, "Description", "", path), Text(a, "WhenToUse", "", path), Strings(a, "Tags", path), Strings(a, "Related", path));
    }
    private Compass? ReadCompass(IEnumerable<AttributeData> attrs, string path)
    {
        var a = Find(attrs, "AiCompass");
        var options = attrs.Where(x => x.AttributeClass?.ToDisplayString() == AnnotationNamespace + "AiCompassOptionsAttribute").Select(x =>
            new OptionSpec(Strings(x, "Values", path), Text(x, "Source", "", path), Text(x, "Pointer", "", path), (Get(x, "EnumType")?.Value as ITypeSymbol) is { } type ? TypeName(type) : null)).ToArray();
        return a is null && options.Length == 0 ? null : new(a is null ? "" : Text(a, "Description", "", path), a is null ? [] : Strings(a, "Tags", path), a is null ? "AUTO" : EnumName(a, "Creation", "AUTO", path), a is null ? "AUTO" : EnumName(a, "Preference", "AUTO", path), a is null ? [] : Strings(a, "Examples", path), options);
    }
    private static bool Contained(string root, string path)
    {
        string relative = Path.GetRelativePath(root, path);
        return relative != ".." && !relative.StartsWith(".." + Path.DirectorySeparatorChar, StringComparison.Ordinal) && !Path.IsPathRooted(relative);
    }
    private static IEnumerable<string> Walk(string dir)
    {
        if ((File.GetAttributes(dir) & FileAttributes.ReparsePoint) != 0) throw new InvalidDataException("Source directory symlinks are not supported");
        foreach (var p in Directory.EnumerateFileSystemEntries(dir).Order(StringComparer.Ordinal))
        {
            if (Excluded.Contains(Path.GetFileName(p))) continue;
            var attributes = File.GetAttributes(p);
            if ((attributes & FileAttributes.ReparsePoint) != 0) continue;
            if ((attributes & FileAttributes.Directory) != 0) { foreach (var child in Walk(p)) yield return child; }
            else yield return p;
        }
    }
}
