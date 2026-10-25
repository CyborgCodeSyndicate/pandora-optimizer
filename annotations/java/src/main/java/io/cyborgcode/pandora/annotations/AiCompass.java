package io.cyborgcode.pandora.annotations;

import java.lang.annotation.*;

/** API usage guidance. Examples refer to resource IDs in pandora/config.json. */
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
public @interface AiCompass {
    String description() default "";

    String[] tags() default {};

    CreationKind creation() default CreationKind.AUTO;

    Preference preference() default Preference.AUTO;

    String[] examples() default {};
}
