package edu.tucn.dppprovver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@Component
public class JsonMapper {
    private final ObjectMapper mapper;

    public JsonMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(Object object) {
        try {
            return mapper.writeValueAsString(object);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON input", e);
        }
    }

    public <T> T fromJson(String json, Class<T> cls) {
        try {
            return mapper.readValue(json, cls);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid chaincode JSON response", e);
        }
    }

    public <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid chaincode JSON response", e);
        }
    }

    public JsonNode tree(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid chaincode response JSON", e);
        }
    }
}
