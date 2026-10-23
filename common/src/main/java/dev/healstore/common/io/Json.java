package dev.healstore.common.io;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();
    private Json() {}
    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot encode JSON", e);
        }
    }
    public static <T> T read(String text, Class<T> type) {
        try {
            return MAPPER.readValue(text, type);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Invalid JSON", e);
        }
    }
}
