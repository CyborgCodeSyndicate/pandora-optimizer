using Pandora.Optimizer.Annotations;
namespace Demo;
public enum Mode { STANDARD, EXPRESS }
[AiCompass(Description="Submit payments with an idempotency key.",Creation=CreationKind.CONSTRUCTOR)]
public class Payments {
    [AiCompass(Description="Create the payment client.")]
    public Payments() {}
    [AiLesson(Category="service",Level=LessonLevel.EXCELLENT,Description="Preserve the same key across retries.",WhenToUse="When retrying payment submissions.",Tags=new[]{"retry","payments"})]
    [AiCompass(Description="Submit a payment.",Preference=Preference.PREFER,Examples=new[]{"payment-examples"})]
    public string Submit(
        [AiCompass(Description="Stable retry key.")] string key,
        [AiCompassOptions(Source="payment-options",Pointer="/environments")] string environment,
        [AiCompassOptions(EnumType=typeof(Mode))] Mode mode) {
        return key + ":" + environment + ":" + mode.ToString();
    }
}
