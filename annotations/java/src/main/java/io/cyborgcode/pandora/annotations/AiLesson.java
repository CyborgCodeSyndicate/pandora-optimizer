package io.cyborgcode.pandora.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Author-curated teaching guidance. Scanners read this without executing application code.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface AiLesson {

   /** Author-defined category used to filter lesson retrieval. */
   String category();

   /** Author assessment of this pattern's quality. */
   LessonLevel level() default LessonLevel.GOOD;

   /** Explains the annotated API or teaching pattern. */
   String description();

   /** Circumstances in which this lesson applies. */
   String whenToUse() default "";

   /** Search terms associated with this guidance. */
   String[] tags() default {};

   /** Author-provided references to related symbols or patterns. */
   String[] related() default {};
}
