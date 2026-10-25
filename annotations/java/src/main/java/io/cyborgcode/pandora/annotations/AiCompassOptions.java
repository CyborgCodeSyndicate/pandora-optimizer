package io.cyborgcode.pandora.annotations;

import java.lang.annotation.*;

/** Declarative values, an enum, or a named JSON resource and RFC 6901 pointer. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(AiCompassOptions.List.class)
@Target({
    ElementType.TYPE,
    ElementType.METHOD,
    ElementType.CONSTRUCTOR,
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT
})
public @interface AiCompassOptions {
    String[] values() default {};

    String source() default "";

    String pointer() default "";

    Class<?> enumType() default Void.class;

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({
        ElementType.TYPE,
        ElementType.METHOD,
        ElementType.CONSTRUCTOR,
        ElementType.FIELD,
        ElementType.PARAMETER,
        ElementType.RECORD_COMPONENT
    })
    @interface List {
        AiCompassOptions[] value();
    }
}
