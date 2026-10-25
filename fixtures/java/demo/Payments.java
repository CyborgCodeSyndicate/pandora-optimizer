package demo;
import io.cyborgcode.pandora.annotations.*;
@AiCompass(description="Submit payments with an idempotency key.", creation=CreationKind.CONSTRUCTOR)
public class Payments {
    @AiCompass(description="Create the payment client.")
    public Payments() {}
    @AiLesson(category="service",level=LessonLevel.EXCELLENT,description="Preserve the same key across retries.",whenToUse="When retrying payment submissions.",tags={"retry","payments"})
    @AiCompass(description="Submit a payment.",preference=Preference.PREFER,examples={"payment-examples"})
    public String submit(
        @AiCompass(description="Stable retry key.") String key,
        @AiCompassOptions(source="payment-options",pointer="/environments") String environment,
        @AiCompassOptions(enumType=Mode.class) Mode mode) {
        return key + ":" + environment + ":" + mode.name();
    }
}
enum Mode { STANDARD, EXPRESS }
