package io.cyborgcode.pandora.model;

import java.util.List;

/**
 * Data-only rule; enumType is adapter-qualified, source is a configured resource ID.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record OptionSpec(
      List<String> values,
      String source,
      String pointer,
      String enumType,
      String implementationsOf,
      String render) {

   /** Creates a literal, resource or enum rule with name rendering. */
   public OptionSpec(List<String> values, String source, String pointer, String enumType) {
      this(values, source, pointer, enumType, null, "NAME");
   }

   /** Normalizes optional values and defensively copies collection inputs. */
   public OptionSpec {
      values = values == null ? List.of() : List.copyOf(values);
      render = render == null ? "NAME" : render;
   }
}
