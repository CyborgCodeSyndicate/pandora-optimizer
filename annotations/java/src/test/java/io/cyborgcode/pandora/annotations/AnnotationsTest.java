package io.cyborgcode.pandora.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AnnotationsTest {
    @AiLesson(category="service", description="Keep a stable key")
    @AiCompass(description="Example API")
    @AiCompassOptions(values={"fast"})
    @AiCompassOptions(enumType=Mode.class)
    static class Example {}
    enum Mode { FAST, SLOW }

    @Test void runtimeGuidanceRetainsItsDocumentedDefaults() {
        var lesson = Example.class.getAnnotation(AiLesson.class);
        assertEquals(LessonLevel.GOOD, lesson.level());
        assertEquals("", lesson.whenToUse());
        assertEquals(0, lesson.tags().length);
        assertEquals(0, lesson.related().length);
        var compass = Example.class.getAnnotation(AiCompass.class);
        assertEquals(CreationKind.AUTO, compass.creation());
        assertEquals(Preference.AUTO, compass.preference());
        assertEquals(0, compass.examples().length);
        assertEquals(0, compass.tags().length);
    }
    @Test void repeatedOptionsPreserveLiteralAndEnumRulesWithoutUsageFiles() {
        var rules = Example.class.getAnnotationsByType(AiCompassOptions.class);
        assertEquals(2, rules.length);
        assertArrayEquals(new String[]{"fast"}, rules[0].values());
        assertEquals(Mode.class, rules[1].enumType());
        assertEquals(Void.class, rules[0].implementationsOf());
        assertEquals("NAME", rules[0].render());
        assertEquals(0, rules[0].meta().length);
    }
    @Test void targetsAndRetentionSupportSourceAndRuntimeConsumers() {
        for (var annotation : List.of(AiCompass.class, AiCompassOptions.class, AiLesson.class)) {
            assertEquals(RetentionPolicy.RUNTIME, annotation.getAnnotation(Retention.class).value());
        }
        assertTrue(List.of(AiCompass.class.getAnnotation(Target.class).value()).contains(ElementType.RECORD_COMPONENT));
        assertTrue(List.of(AiCompassOptions.class.getAnnotation(Target.class).value()).contains(ElementType.PARAMETER));
        assertTrue(List.of(CreationKind.values()).contains(CreationKind.STATIC_FACTORY));
        assertEquals(Preference.FORBID, Preference.valueOf("FORBID"));
        assertEquals(LessonLevel.EXCELLENT, LessonLevel.valueOf("EXCELLENT"));
    }
}
