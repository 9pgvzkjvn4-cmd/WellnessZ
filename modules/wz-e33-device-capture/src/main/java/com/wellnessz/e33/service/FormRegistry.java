package com.wellnessz.e33.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.e33.domain.VerificationRule;
import com.wellnessz.e33.model.E33FormRuleRepository;
import com.wellnessz.e33.model.FormRule;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Form registry (contract 14.2, E33-F01 / F02): the field catalogue of every OH form that can capture from a device
 * ({@code e33/form-registry.json}, versioned with the code) merged with its rule ({@code devicecap.form_rule}). Used by the
 * capture dialogue and by the console's binding editor (wizard step 5).
 */
@Component
public class FormRegistry {

    public record Field(String path, String label, String type, String unit, boolean required) {
    }

    public record FieldGroup(String group, List<Field> fields) {
    }

    /**
     * @param verification who may verify, whether interpretation needs sign-off and whether a summary is released
     */
    public record Form(String formCode, String formVersion, String module, DeviceCodes.Category category, List<FieldGroup> fieldGroups,
                       String target, Verification verification) {
    }

    public record Verification(List<String> verifierRoles, List<String> signOffRoles, boolean verifyAlways, boolean releaseToEmployee,
                               boolean restricted) {
    }

    private final Map<String, List<FieldGroup>> fieldGroups;
    private final String registryVersion;
    private final E33FormRuleRepository rules;

    public FormRegistry(E33FormRuleRepository rules, JsonMapper json) {
        this.rules = rules;
        Map<String, List<FieldGroup>> groups = new LinkedHashMap<>();
        JsonNode root;
        try (InputStream in = new ClassPathResource("e33/form-registry.json").getInputStream()) {
            root = json.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("E33 form registry missing", e);
        }
        this.registryVersion = root.path("registryVersion").asString();
        for (JsonNode f : root.path("forms")) {
            List<FieldGroup> list = new ArrayList<>();
            for (JsonNode g : f.path("fieldGroups")) {
                List<Field> fields = new ArrayList<>();
                for (JsonNode x : g.path("fields")) {
                    fields.add(new Field(x.path("path").asString(), x.path("label").asString(), x.path("type").asString(),
                            x.hasNonNull("unit") ? x.path("unit").asString() : null, x.path("required").asBoolean(false)));
                }
                list.add(new FieldGroup(g.path("group").asString(), List.copyOf(fields)));
            }
            groups.put(f.path("formCode").asString(), List.copyOf(list));
        }
        this.fieldGroups = Map.copyOf(groups);
    }

    public String registryVersion() {
        return registryVersion;
    }

    @Transactional(readOnly = true)
    public List<Form> forms() {
        return rules.findAll().stream().sorted(java.util.Comparator.comparing(FormRule::getFormCode)).map(this::form).toList();
    }

    @Transactional(readOnly = true)
    public Optional<Form> find(String formCode) {
        return formCode == null ? Optional.empty() : rules.findById(formCode).map(this::form);
    }

    @Transactional(readOnly = true)
    public Form require(String formCode) {
        return find(formCode).orElseThrow(() -> ApiException.notFound("Form"));
    }

    @Transactional(readOnly = true)
    public FormRule rule(String formCode) {
        return rules.findById(formCode).orElseThrow(() -> ApiException.notFound("Form"));
    }

    /** {@code true} when the field group exists in the form (null = whole form). */
    public boolean hasGroup(String formCode, String group) {
        return group == null || fieldGroups.getOrDefault(formCode, List.of()).stream().anyMatch(g -> g.group().equals(group));
    }

    private Form form(FormRule r) {
        return new Form(r.getFormCode(), r.getFormVersion(), r.getModule(), DeviceCodes.Category.valueOf(r.getCategory()),
                fieldGroups.getOrDefault(r.getFormCode(), List.of()), r.getTarget(),
                new Verification(VerificationRule.roles(r.getVerifierRoles()).stream().sorted().toList(),
                        VerificationRule.roles(r.getSignOffRoles()).stream().sorted().toList(), r.isVerifyAlways(), r.isReleaseToEmployee(),
                        r.isRestricted()));
    }
}
