using System.Text.Json;
namespace Pandora.Optimizer.DotNet;
public sealed record ScanRequest(int ProtocolVersion, string WorkspaceId, string ModuleId, string Root, string[] SourceRoots, string[] References);
public sealed record Diagnostic(string Code, string Severity, string Message, string? Path);
public sealed record SourceLocation(string Path, int StartLine, int EndLine, string Sha256);
public sealed record Lesson(string Category, string Level, string Description, string WhenToUse, string[] Tags, string[] Related);
public sealed record OptionSpec(string[] Values, string Source, string Pointer, string? EnumType);
public sealed record Compass(string Description, string[] Tags, string Creation, string Preference, string[] Examples, OptionSpec[] Options);
public sealed record Parameter(int Index, string Name, string Type, bool Optional, bool Variadic, Compass? Compass);
public sealed record Symbol(string Id, string Language, string Module, string Kind, string Name, string QualifiedName, string Signature, string? ParentId, string? ReturnType, Parameter[] Parameters, SourceLocation Source, Lesson? Lesson, Compass? Compass, string[] EnumValues, string Resolution);
public sealed record ScanResult(int ProtocolVersion, Symbol[] Symbols, Diagnostic[] Diagnostics);
public static class Protocol
{
    public static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase, PropertyNameCaseInsensitive = true };
}
