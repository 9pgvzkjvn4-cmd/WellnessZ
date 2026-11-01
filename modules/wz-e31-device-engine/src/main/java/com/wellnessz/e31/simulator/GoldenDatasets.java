package com.wellnessz.e31.simulator;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import com.wellnessz.common.error.ApiException;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Golden datasets (E31-HLR-10, contract section 19): synthetic recordings per family and package under
 * {@code classpath:e31/golden/<family>/<package>/…} – {@code normal}, {@code edge}, {@code malformed}, {@code time-skew},
 * {@code duplicate} – indexed by {@code e31/golden/index.json}. Synthetic only: no real person data.
 */
@Component("e31GoldenDatasets")
public class GoldenDatasets {

    /**
     * @param resource    path below {@code e31/}
     * @param kind        NORMAL | EDGE | MALFORMED | TIME_SKEW | DUPLICATE | PERFORMANCE
     * @param headers     transport headers the simulator sends (file name and format, X-Edge-Clock offset …)
     * @param expect      expected outcome of the simulator run (status, gates) – used by CI regression tests
     */
    public record Dataset(String datasetId, String family, String adapter, String packageId, String kind, String resource, String contentType,
                          String description, Map<String, String> headers, Map<String, Object> expect) {
    }

    private final List<Dataset> datasets;

    public GoldenDatasets(JsonMapper json) {
        try (InputStream in = new ClassPathResource("e31/golden/index.json").getInputStream()) {
            this.datasets = List.copyOf(json.readValue(in, new TypeReference<List<Dataset>>() {
            }));
        } catch (IOException e) {
            throw new IllegalStateException("Golden dataset index missing", e);
        }
    }

    public List<Dataset> list(String family) {
        List<Dataset> out = new ArrayList<>();
        for (Dataset d : datasets) {
            if (family == null || family.equalsIgnoreCase(d.family())) {
                out.add(d);
            }
        }
        return out;
    }

    public Optional<Dataset> find(String datasetId) {
        return datasets.stream().filter(d -> d.datasetId().equals(datasetId)).findFirst();
    }

    public Dataset require(String datasetId) {
        return find(datasetId).orElseThrow(() -> ApiException.notFound("Golden dataset"));
    }

    /** Content of a dataset or test-vector input (path relative to {@code e31/}). */
    public byte[] content(String resource) {
        if (resource == null || resource.contains("..") || !resource.matches("[A-Za-z0-9_./\\-]{1,200}")) {
            throw ApiException.notFound("Golden dataset");
        }
        try (InputStream in = new ClassPathResource("e31/" + resource).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw ApiException.notFound("Golden dataset");
        }
    }
}
