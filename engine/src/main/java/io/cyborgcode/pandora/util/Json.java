package io.cyborgcode.pandora.util;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;

public final class Json {
    private Json() {}

    public static final ObjectMapper MAPPER =
            JsonMapper.builder()
                    .enable(SerializationFeature.INDENT_OUTPUT)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();
}
