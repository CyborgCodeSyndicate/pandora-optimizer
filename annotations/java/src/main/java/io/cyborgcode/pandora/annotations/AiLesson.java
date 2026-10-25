package io.cyborgcode.pandora.annotations;

import java.lang.annotation.*;

/** Author-curated teaching guidance. Scanners read this without executing application code. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface AiLesson {
    String category();

    LessonLevel level() default LessonLevel.GOOD;

    String description();

    String whenToUse() default "";

    String[] tags() default {};

    String[] related() default {};
}
