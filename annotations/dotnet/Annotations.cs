namespace Pandora.Optimizer.Annotations;
public enum LessonLevel { EXCELLENT, GOOD, BAD }
public enum Preference { AUTO, PREFER, AVOID, FORBID }
public enum CreationKind { AUTO, CONSTRUCTOR, BUILDER, STATIC_FACTORY, ENUM_CONSTANT, SINGLETON, PROVIDED }

[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct | AttributeTargets.Interface | AttributeTargets.Enum | AttributeTargets.Method | AttributeTargets.Constructor)]
public sealed class AiLessonAttribute : Attribute
{
    public string Category { get; set; } = "";
    public LessonLevel Level { get; set; } = LessonLevel.GOOD;
    public string Description { get; set; } = "";
    public string WhenToUse { get; set; } = "";
    public string[] Tags { get; set; } = [];
    public string[] Related { get; set; } = [];
}
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct | AttributeTargets.Interface | AttributeTargets.Enum | AttributeTargets.Method | AttributeTargets.Constructor | AttributeTargets.Field | AttributeTargets.Property | AttributeTargets.Parameter)]
public sealed class AiCompassAttribute : Attribute
{
    public string Description { get; set; } = "";
    public string[] Tags { get; set; } = [];
    public CreationKind Creation { get; set; } = CreationKind.AUTO;
    public Preference Preference { get; set; } = Preference.AUTO;
    public string[] Examples { get; set; } = [];
}
[AttributeUsage(AttributeTargets.Class | AttributeTargets.Struct | AttributeTargets.Interface | AttributeTargets.Enum | AttributeTargets.Method | AttributeTargets.Constructor | AttributeTargets.Field | AttributeTargets.Property | AttributeTargets.Parameter, AllowMultiple = true)]
public sealed class AiCompassOptionsAttribute : Attribute
{
    public string[] Values { get; set; } = [];
    public string Source { get; set; } = "";
    public string Pointer { get; set; } = "";
    public Type? EnumType { get; set; }
}
