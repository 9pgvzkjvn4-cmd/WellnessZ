package io.intimed.intelligence;

import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Optional;

/**
 * Terminology service adapter (IM-E12-BL-010): the AutoMapper's view of an external terminology server for host fields
 * coded in a system IntiMed does not know. Implementations must be side-effect free, time-bounded and fail closed
 * (empty results) – terminology is an additional mapping signal, never a precondition for routing.
 */
public interface TerminologyService {
    /** Code systems IntiMed resolves itself (no server round trip). */
    List<String> KNOWN_SYSTEMS = List.of("http://loinc.org", "urn:iso:std:iso:11073:10101", "urn:intimed:local", "http://unitsofmeasure.org");

    record Concept(String system, String code, String display) { }

    record Mapping(String system, String code, String display, String equivalence) { }

    boolean enabled();

    /** Status for the studio: enabled, base URL host, reason when disabled, cache size. */
    ObjectNode status();

    /** {@code CodeSystem/$lookup}: display of a code. */
    Optional<Concept> lookup(String system, String code);

    /** {@code CodeSystem/$validate-code}: empty when the server cannot answer. */
    Optional<Boolean> validateCode(String system, String code);

    /** {@code ConceptMap/$translate} into {@code targetSystem} (e.g. http://loinc.org). */
    List<Mapping> translate(String system, String code, String targetSystem);

    static boolean known(String system) { return system == null || system.isBlank() || KNOWN_SYSTEMS.contains(system) || system.contains("loinc") || system.contains("11073"); }
}
