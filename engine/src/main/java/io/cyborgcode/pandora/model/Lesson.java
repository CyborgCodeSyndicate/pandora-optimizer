package io.cyborgcode.pandora.model;

import java.util.List;

/**
 * Author-curated teaching guidance associated with a source symbol.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public record Lesson(
      String category,
      String level,
      String description,
      String whenToUse,
      List<String> tags,
      List<String> related) {

   /** Normalizes optional values and defensively copies collection inputs. */
   public Lesson {
      tags = tags == null ? List.of() : List.copyOf(tags);
      related = related == null ? List.of() : List.copyOf(related);
   }
}
