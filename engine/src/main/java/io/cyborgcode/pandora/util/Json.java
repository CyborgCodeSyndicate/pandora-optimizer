package io.cyborgcode.pandora.util;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Shared JSON mapper for catalog and adapter protocol records.
 *
 * @author Cyborg Code Syndicate 💍👨💻
 */
public final class Json {

   private Json() {}

   public static final ObjectMapper MAPPER =
         JsonMapper.builder()
               .enable(SerializationFeature.INDENT_OUTPUT)
               .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
               .build();
}
