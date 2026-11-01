package com.wellnessz.e31.driver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sample driver packages shipped with the engine ({@code classpath:e31/drivers/*.json}, one per adapter family of contract
 * section 6.2 plus the BLE profiles HTP, PLX, WSS and BCS and an exposure feed). They are unsigned SDK samples: the engine
 * uses them only for simulators, golden-dataset conformance and the demo; production traffic always uses the signed
 * package published in the E32 registry.
 */
@Component("e31BundledDrivers")
public class BundledDrivers {

    private final Map<String, DriverPackage> packages = new LinkedHashMap<>();
    private final Map<String, String> sources = new LinkedHashMap<>();

    public BundledDrivers(JsonMapper json) {
        DriverParser parser = new DriverParser(json);
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:e31/drivers/*.json");
            List<Resource> sorted = new ArrayList<>(List.of(resources));
            sorted.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
            for (Resource r : sorted) {
                try (InputStream in = r.getInputStream()) {
                    String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    DriverPackage p = parser.parse(text);
                    packages.put(p.ref(), p);
                    sources.put(p.ref(), text);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Sample driver packages could not be read", e);
        }
    }

    public List<DriverPackage> all() {
        return List.copyOf(packages.values());
    }

    public Optional<DriverPackage> get(String packageId, String version) {
        if (version != null) {
            return Optional.ofNullable(packages.get(packageId + "@" + version));
        }
        return packages.values().stream().filter(p -> p.packageId().equals(packageId)).reduce((a, b) -> b);
    }

    /** The package JSON as shipped (for the SDK and the conformance runner). */
    public Optional<String> source(String packageId, String version) {
        return get(packageId, version).map(p -> sources.get(p.ref()));
    }

    public Optional<DriverPackage> forAdapter(String adapter) {
        return packages.values().stream().filter(p -> p.adapter().equals(adapter)).findFirst();
    }
}
