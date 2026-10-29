package com.wellnessz.e03.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** JSON (de)serialisation of the structured parts of the record (answers, SOAP, order items, device measurements). */
@Component
public class JsonSupport {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {
    };

    private final JsonMapper json;

    public JsonSupport(JsonMapper json) {
        this.json = json;
    }

    public String write(Object value) {
        return value == null ? null : json.writeValueAsString(value);
    }

    public Map<String, Object> map(String value) {
        return value == null || value.isBlank() ? new LinkedHashMap<>() : json.readValue(value, MAP);
    }

    public List<Map<String, Object>> list(String value) {
        return value == null || value.isBlank() ? List.of() : json.readValue(value, LIST);
    }

    public JsonNode tree(String value) {
        return value == null || value.isBlank() ? null : json.readTree(value);
    }

    public <T> T convert(Object value, Class<T> type) {
        return json.convertValue(value, type);
    }

    public <T> T read(String value, Class<T> type) {
        return json.readValue(value, type);
    }

    public <T> List<T> readList(String value, Class<T> element) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, element));
    }
}
