using Pandora.Optimizer.DotNet;
using Xunit;
namespace Pandora.Optimizer.Tests;
public sealed class CSharpAdapterTests : IDisposable
{
    private readonly string root = Path.Combine(Path.GetTempPath(), "pandora-tests-" + Guid.NewGuid());
    public CSharpAdapterTests() { Directory.CreateDirectory(root); }
    public void Dispose() { Directory.Delete(root, true); }
    private ScanResult Scan(string text)
    {
        File.WriteAllText(Path.Combine(root, "Example.cs"), text);
        return new CSharpAdapter().Scan(new(1, "test", "main", root, ["."], []));
    }
    private const string Header = "using Pandora.Optimizer.Annotations; namespace Demo; ";
    [Fact]
    public void StaticAndInstanceConstructorsHaveDistinctIds()
    {
        var result = Scan(Header + "class Example { [AiCompass] static Example() {} [AiCompass] public Example() {} }");
        Assert.Equal(2, result.Symbols.Where(s => s.Kind == "CONSTRUCTOR").Select(s => s.Id).Distinct().Count());
    }
    [Fact]
    public void ExtractsLessonsWithoutExecutingStaticInitializer()
    {
        var result = Scan(Header + """
            [AiCompass(Description="client")]
            public class Example {
                static Example() { throw new System.Exception("must never execute"); }
                [AiLesson(Category="service",Level=LessonLevel.EXCELLENT,Description="retry carefully",Tags=new[]{"retry"})]
                [AiCompass(Description="run")]
                public void Run(string key,[AiCompassOptions(Values=new[]{"fast","slow"})] string mode) {}
            }
            """);
        Assert.DoesNotContain(result.Diagnostics, d => d.Severity == "error");
        var method = Assert.Single(result.Symbols, s => s.Name == "Run");
        Assert.Equal("EXCELLENT", method.Lesson!.Level); Assert.Equal(2, method.Parameters.Length);
        Assert.Equal(1, method.Parameters[1].Index); Assert.Equal(["fast", "slow"], method.Parameters[1].Compass!.Options[0].Values);
    }
    [Fact]
    public void DiscoversConstructorsAndProperties()
    {
        var result = Scan(Header + """
            public class Example {
                [AiCompass(Description="construct")] public Example() {}
                [AiCompass(Description="name")] public string Name {get;set;} = "";
            }
            """);
        Assert.Contains(result.Symbols, s => s.Kind == "CONSTRUCTOR");
        Assert.Contains(result.Symbols, s => s.Kind == "PROPERTY");
    }
    [Fact]
    public void KeepsOverloadsDistinct()
    {
        var result = Scan(Header + """
            public class Example {
                [AiCompass] public void Run(int value) {}
                [AiCompass] public void Run(string value) {}
            }
            """);
        Assert.Equal(2, result.Symbols.Where(s => s.Kind == "METHOD").Select(s => s.Id).Distinct().Count());
    }
    [Fact]
    public void SupportsAttributeAliases()
    {
        var result = Scan("using Compass=Pandora.Optimizer.Annotations.AiCompassAttribute; namespace Demo; [Compass(Description=\"alias\")] class Example {}");
        Assert.Equal("alias", Assert.Single(result.Symbols).Compass!.Description);
    }
    [Fact]
    public void IgnoresUnrelatedAttributeWithSameShortName()
    {
        var result = Scan("using System; namespace Demo; class AiCompassAttribute:Attribute {} [AiCompass] class Example {}");
        Assert.All(result.Symbols, s => Assert.Null(s.Compass));
    }
    [Fact]
    public void ResolvesConstantsAndEnums()
    {
        var result = Scan(Header + """
            public enum Mode { Fast, Slow }
            public class Example {
                const string Description="A constant";
                [AiCompass(Description=Description)]
                public void Run([AiCompassOptions(EnumType=typeof(Mode))] Mode mode) {}
            }
            """);
        var method = Assert.Single(result.Symbols, s => s.Name == "Run");
        Assert.Equal("A constant", method.Compass!.Description);
        Assert.Equal("Demo.Mode", method.Parameters[0].Compass!.Options[0].EnumType);
        Assert.Equal(["Fast", "Slow"], Assert.Single(result.Symbols, s => s.Kind == "ENUM").EnumValues);
    }
    [Fact]
    public void ReportsSyntaxFailures()
    {
        Assert.Contains(Scan("class Example { !!!").Diagnostics, d => d.Code == "CSHARP_PARSE" && d.Severity == "error");
    }
    [Fact]
    public void PreservesRefOptionalAndParamsShape()
    {
        var result = Scan(Header + "class Example { [AiCompass] public void Run(ref int key, string label=\"x\", params string[] values) {} }");
        var method = Assert.Single(result.Symbols, s => s.Name == "Run");
        Assert.StartsWith("ref ", method.Parameters[0].Type);
        Assert.True(method.Parameters[1].Optional); Assert.True(method.Parameters[2].Variadic);
    }
    [Fact]
    public void UsesRealSourceLocationAndNestedIdentity()
    {
        var result = Scan(Header + "class Example { [AiLesson(Category=\"test\",Description=\"nested\")] public class Nested {} }");
        var nested = Assert.Single(result.Symbols, s => s.Name == "Nested");
        Assert.Equal("Demo.Example.Nested", nested.QualifiedName); Assert.Equal("Example.cs", nested.Source.Path);
    }
}
