package io.cyborgcode.pandora.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative values, an enum, or a named JSON resource and RFC 6901 pointer.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
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

   /** Literal option values accepted by the annotated API. */
   String[] values() default {};

   /** Configured resource ID containing option values. */
   String source() default "";

   /** RFC 6901 JSON pointer selecting values within the resource. */
   String pointer() default "";

   /** Enum type whose constants define the accepted values. */
   Class<?> enumType() default Void.class;

   /** Discover enum implementations in the consumer and configured dependency catalogs. */
   Class<?> implementationsOf() default Void.class;

   /** NAME preserves Enum.name(); QUALIFIED emits the declaring type and constant. */
   String render() default "NAME";

   /** Additional author-supplied metadata entries. */
   Meta[] meta() default {};

   /** Additional metadata attached to an API option declaration. */
   @Documented
   @Retention(RetentionPolicy.RUNTIME)
   @Target({})
   @interface Meta {

      /** Metadata entry name. */
      String key();

      /** Metadata value or repeated option declarations. */
      String value();
   }

   /** Container for repeated option declarations. */
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

      /** Metadata value or repeated option declarations. */
      AiCompassOptions[] value();
   }
}
