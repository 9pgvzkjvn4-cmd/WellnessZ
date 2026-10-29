package com.wellnessz.e05.decision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * EASA Part-MED limitation codes (AMC to MED.A.020 / MED.B.001 style). Labels are a draft wording for AME / CMO
 * confirmation against the local CAA list. Only the operational codes (they restrict how the crew member may be rostered)
 * are disclosed to crew scheduling; the others describe a medical correction and stay with the AME.
 */
public final class LimitationCodes {

    public static final Set<String> OPERATIONAL = Set.of("OML", "OCL", "OSL", "OPL", "ORL", "TML");

    private static final Map<String, String> LABELS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("TML", "Restriction of the period of validity of the medical certificate");
        m.put("VDL", "Shall wear corrective lenses and carry a spare set of spectacles");
        m.put("VML", "Shall wear multifocal spectacles and carry a spare set of spectacles");
        m.put("VNL", "Shall have available corrective spectacles and carry a spare set of spectacles");
        m.put("CCL", "Correction by means of contact lenses only");
        m.put("VCL", "Valid by day only");
        m.put("OML", "Valid only as or with qualified co-pilot");
        m.put("OCL", "Valid only as co-pilot");
        m.put("OSL", "Valid only with safety pilot and in aircraft with dual controls");
        m.put("OPL", "Valid only without passengers");
        m.put("ORL", "Valid only with operational restriction as specified by the licensing authority");
        m.put("SSL", "Special restriction as specified");
        m.put("HAL", "Valid only when hearing aids are worn");
        m.put("SIC", "Specific regular medical examination – contact the medical assessor of the licensing authority");
        m.put("RXO", "Specialist ophthalmological examination required");
        LABELS = java.util.Collections.unmodifiableMap(m);
    }

    private LimitationCodes() {
    }

    /** Code → label in catalogue order (unmodifiable). */
    public static Map<String, String> labels() {
        return LABELS;
    }

    public static boolean isOperational(String code) {
        return OPERATIONAL.contains(code);
    }

    public static boolean isKnown(String code) {
        return LABELS.containsKey(code);
    }
}
