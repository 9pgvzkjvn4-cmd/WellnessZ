package com.wellnessz.e32.catalogue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Catalogue-owned integration routes ({@code e32/catalogue-routes.json}): options a model is integrated through a vendor system in
 * addition to the options of the client's inventory row, which stays exactly as provided (contract §5). Example: MD-045 GE MAC –
 * inventory P1 / P4 (cart → MUSE), catalogue route P2 (MUSE XML export, driver {@code ecg.ge.muse-xml}). The inventory options keep
 * driving counts and search; the wizard (step 2) and the driver registry accept inventory options plus routes.
 */
public final class CatalogueRoutes {

    public record Route(String modelCode, String option, String via, String driverPackageId, String reason) {
    }

    private static final List<Route> ROUTES = load();

    private CatalogueRoutes() {
    }

    static List<Route> load() {
        try (InputStream in = CatalogueRoutes.class.getClassLoader().getResourceAsStream("e32/catalogue-routes.json")) {
            if (in == null) {
                return List.of();
            }
            JsonNode root = JsonMapper.builder().build().readTree(in);
            List<Route> out = new ArrayList<>();
            for (JsonNode r : root.path("routes")) {
                out.add(new Route(r.path("modelCode").asString(), r.path("option").asString(), r.path("via").asString(null),
                        r.path("driverPackageId").asString(null), r.path("reason").asString(null)));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new IllegalStateException("e32/catalogue-routes.json unreadable", e);
        }
    }

    public static List<Route> routes(String modelCode) {
        return ROUTES.stream().filter(r -> r.modelCode().equals(modelCode)).toList();
    }

    /** Inventory options plus catalogue routes of a model. */
    public static List<String> offered(String modelCode, List<String> inventoryOptions) {
        Set<String> out = new LinkedHashSet<>(inventoryOptions);
        routes(modelCode).forEach(r -> out.add(r.option()));
        return List.copyOf(out);
    }

    public static boolean offers(ModelStore.ModelRow m, String option) {
        return m.supports(option) || routes(m.modelCode()).stream().anyMatch(r -> r.option().equals(option));
    }

    public static List<Map<String, Object>> view(String modelCode) {
        return routes(modelCode).stream().map(r -> Map.<String, Object>of("option", r.option(), "via", r.via() == null ? "" : r.via(),
                "driverPackageId", r.driverPackageId() == null ? "" : r.driverPackageId(), "reason", r.reason() == null ? "" : r.reason())).toList();
    }
}
