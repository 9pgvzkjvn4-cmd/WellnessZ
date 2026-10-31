package com.wellnessz.e32.option;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e32.support.Docs;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The ten connectivity option packs P1–P10 (E32-F03, E32-BL-004, contract §13.1 / §13.3): name, how it works, typical
 * devices, parameter schema (JSON Schema 2020-12), common schema, prerequisites, step guide, diagram, security level,
 * reliability, effort and help texts in English and Arabic. Loaded from {@code e32/option-packs.json}.
 */
@Component("e32OptionPacks")
public class OptionPacks {

    public static final String RESOURCE = "e32/option-packs.json";
    private final Map<String, Map<String, Object>> packs = new LinkedHashMap<>();
    private final Map<String, Object> commonSchema;
    private final String version;

    public OptionPacks() {
        this(JsonMapper.builder().build());
    }

    OptionPacks(JsonMapper json) {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            root = json.readValue(in, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("option packs not readable", e);
        }
        commonSchema = Docs.map(root.get("commonSchema"));
        version = Docs.str(root.get("version"));
        for (Object o : Docs.list(root.get("options"))) {
            Map<String, Object> p = Docs.map(o);
            packs.put(Docs.str(p.get("option")), p);
        }
    }

    public String version() {
        return version;
    }

    public List<String> codes() {
        return List.copyOf(packs.keySet());
    }

    public Optional<Map<String, Object>> find(String option) {
        Map<String, Object> p = packs.get(option);
        return p == null ? Optional.empty() : Optional.of(Docs.copyMap(p));
    }

    public Map<String, Object> require(String option) {
        return find(option).orElseThrow(() -> ApiException.notFound("Connectivity option"));
    }

    /** The pack as published by {@code GET /device-catalogue/options/{option}} (with the common schema). */
    public Map<String, Object> view(String option) {
        Map<String, Object> p = require(option);
        p.put("commonSchema", Docs.copy(commonSchema));
        p.put("packVersion", version);
        return p;
    }

    public List<Map<String, Object>> all() {
        return packs.keySet().stream().map(this::view).toList();
    }

    public Map<String, Object> parameterSchema(String option) {
        return Docs.map(require(option).get("parameterSchema"));
    }

    public Map<String, Object> commonSchema() {
        return Docs.copyMap(commonSchema);
    }

    public List<String> prerequisites(String option) {
        return Docs.strings(require(option).get("prerequisites"));
    }

    /** Endpoint kind provisioned at publish (null for manual entry). */
    public String endpointKind(String option) {
        return Docs.str(require(option).get("endpointKind"));
    }

    /** Comparison row shown in wizard step 2. */
    public Map<String, Object> comparison(String option) {
        Map<String, Object> p = require(option);
        return Docs.of("option", option, "name", p.get("name"), "prerequisites", p.get("prerequisites"), "reliability", p.get("reliability"),
                "securityLevel", p.get("securityLevel"), "effort", p.get("effort"));
    }
}
