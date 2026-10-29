package com.wellnessz.e07.programme;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.e07.model.Programme;
import com.wellnessz.e07.model.ProgrammeRepository;
import com.wellnessz.e07.support.SurveillanceSupport;

import tools.jackson.core.type.TypeReference;

/** Reads programme definitions: the ACTIVE version of each code, eligibility criteria and panel tests. */
@Component
public class ProgrammeCatalog {

    private static final TypeReference<List<PanelItem>> PANEL = new TypeReference<>() {
    };

    private final ProgrammeRepository programmes;
    private final SurveillanceSupport support;

    public ProgrammeCatalog(ProgrammeRepository programmes, SurveillanceSupport support) {
        this.programmes = programmes;
        this.support = support;
    }

    /** One panel entry: {@code AUDIOMETRY}, {@code LAB:5671-3}, {@code IMMUNISATION:HEP_B} … with an optional standard code. */
    public record PanelItem(String test, String standard) {
    }

    public List<Programme> active() {
        return programmes.findByStatusOrderByCodeAsc("ACTIVE");
    }

    public Map<String, Programme> activeByCode() {
        Map<String, Programme> out = new LinkedHashMap<>();
        active().forEach(p -> out.put(p.getCode(), p));
        return out;
    }

    public Optional<Programme> active(String code) {
        return programmes.findFirstByCodeAndStatus(code, "ACTIVE");
    }

    /** ACTIVE version, otherwise the latest version (draft or retired) – for titles of historical enrolments. */
    public Optional<Programme> current(String code) {
        return active(code).or(() -> programmes.findByCodeOrderByVersionDesc(code).stream().findFirst());
    }

    public Eligibility.Criteria criteria(Programme p) {
        return support.read(p.getEligibility(), new TypeReference<Eligibility.Criteria>() {
        });
    }

    public List<PanelItem> panel(Programme p) {
        return support.read(p.getPanel(), PANEL);
    }

    public boolean panelContains(Programme p, List<String> tests) {
        return panel(p).stream().anyMatch(i -> tests.contains(i.test()));
    }
}
