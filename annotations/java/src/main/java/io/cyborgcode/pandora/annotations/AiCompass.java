package io.cyborgcode.pandora.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * API usage guidance. Examples refer to resource IDs in pandora/config.json.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
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

   /** Explains the annotated API or teaching pattern. */
   String description() default "";

   /** Search terms associated with this guidance. */
   String[] tags() default {};

   /** Describes how callers obtain the annotated API instance. */
   CreationKind creation() default CreationKind.AUTO;

   /** Expresses whether callers should prefer or avoid this API. */
   Preference preference() default Preference.AUTO;

   /** Configured resource IDs containing optional usage examples. */
   String[] examples() default {};
}
