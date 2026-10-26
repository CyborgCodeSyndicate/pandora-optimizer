package io.cyborgcode.pandora.model;

import java.util.List;

/**
 * Language-neutral API shape; native type syntax is intentionally retained.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record Symbol(
      String id,
      String language,
      String module,
      String kind,
      String name,
      String qualifiedName,
      String signature,
      String parentId,
      String returnType,
      List<Parameter> parameters,
      SourceLocation source,
      Lesson lesson,
      Compass compass,
      List<String> enumValues,
      String resolution,
      List<String> supertypes) {

   /** Creates a symbol without supertype discovery facts. */
   public Symbol(
         String id,
         String language,
         String module,
         String kind,
         String name,
         String qualifiedName,
         String signature,
         String parentId,
         String returnType,
         List<Parameter> parameters,
         SourceLocation source,
         Lesson lesson,
         Compass compass,
         List<String> enumValues,
         String resolution) {
      this(
            id,
            language,
            module,
            kind,
            name,
            qualifiedName,
            signature,
            parentId,
            returnType,
            parameters,
            source,
            lesson,
            compass,
            enumValues,
            resolution,
            List.of());
   }

   /** Normalizes optional values and defensively copies collection inputs. */
   public Symbol {
      supertypes = supertypes == null ? List.of() : List.copyOf(supertypes);
      parameters = parameters == null ? List.of() : List.copyOf(parameters);
      enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
   }
}
