package com.wellnessz.e31;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import ca.uhn.fhir.context.FhirContext;

import com.wellnessz.e31.adapter.Adapters;
import com.wellnessz.e31.driver.BundledDrivers;
import com.wellnessz.e31.simulator.GoldenDatasets;
import com.wellnessz.e31.terminology.MigrationTerminology;
import com.wellnessz.e31.terminology.Terminology;
import com.wellnessz.e31.terminology.TerminologySnapshot;

import tools.jackson.databind.json.JsonMapper;

/**
 * Shared fixtures of the E31 unit tests: the terminology snapshot is read from the V22 migration itself (so the tests prove
 * that every bundled driver is covered by the shipped reference data), the bundled packages and golden datasets.
 */
public final class TestSupport {

    private static JsonMapper json;
    private static FhirContext fhir;
    private static TerminologySnapshot terminology;
    private static Adapters adapters;

    private TestSupport() {
    }

    public static synchronized JsonMapper json() {
        if (json == null) {
            json = JsonMapper.builder().build();
        }
        return json;
    }

    public static synchronized FhirContext fhir() {
        if (fhir == null) {
            fhir = FhirContext.forR4();
        }
        return fhir;
    }

    public static synchronized Adapters adapters() {
        if (adapters == null) {
            adapters = new Adapters(fhir(), json());
        }
        return adapters;
    }

    public static BundledDrivers bundled() {
        return new BundledDrivers(json());
    }

    public static GoldenDatasets golden() {
        return new GoldenDatasets(json());
    }

    /** Terminology snapshot parsed from {@code V22__e31_device_engine.sql}. */
    public static synchronized Terminology terminology() {
        if (terminology != null) {
            return terminology;
        }
        String sql;
        try {
            sql = Files.readString(Path.of("../../apps/wz-core-app/src/main/resources/db/migration/V22__e31_device_engine.sql"), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("V22 migration not found", e);
        }
        terminology = MigrationTerminology.fromSql(sql);
        return terminology;
    }
}
