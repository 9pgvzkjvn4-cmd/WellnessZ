package com.wellnessz.e31.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.e31.adapter.xml.XPathParser;
import com.wellnessz.e31.association.AssociationEngine;
import com.wellnessz.e31.association.AssociationServiceAccess;

/** Contract points with the other device modules found at the increment-6 merge (QC event payload, configured association methods). */
class CrossModuleContractTest {

    @Test
    void qcnSummaryCarriesTheFieldsE32Records() {
        String qcn = "<QCN.R01><HDR><HDR.control_id V=\"7\"/><HDR.creation_dttm V=\"2026-10-30T07:00:00+00:00\"/></HDR>"
                + "<CTC><CTC.name V=\"Afinion HbA1c Control C1\"/><CTC.lot_number V=\"AF-LOT-2026-10\"/><CTC.expiration_date V=\"2026-12-31\"/></CTC>"
                + "<OBS><OBS.observation_id V=\"HbA1c\"/><OBS.value V=\"5.2\" U=\"%\"/><OBS.normal_flag V=\"N\"/>"
                + "<OBS.observation_dttm V=\"2026-10-30T07:05:00+00:00\"/></OBS></QCN.R01>";
        Map<String, String> qc = XPathParser.qcSummary(qcn.getBytes(StandardCharsets.UTF_8));
        assertThat(qc).containsEntry("lot", "AF-LOT-2026-10").containsEntry("lotExpiry", "2026-12-31").containsEntry("outcome", "PASS")
                .containsEntry("performedAt", "2026-10-30T07:05:00+00:00");
        Map<String, String> failed = XPathParser.qcSummary(qcn.replace("V=\"N\"", "V=\"H\"").getBytes(StandardCharsets.UTF_8));
        assertThat(failed).containsEntry("outcome", "FAIL");
        assertThat(XPathParser.qcSummary("not xml".getBytes(StandardCharsets.UTF_8))).isEmpty();
    }

    @Test
    void onlyTheConfiguredAssociationMethodsMayFileAResult() {
        DeviceCatalogueLookup.DeviceInstance sessionOnly = new DeviceCatalogueLookup.DeviceInstance("DEV-1", "MD-027", DeviceCodes.Family.VITALS,
                "m", "m", "s", null, "1", null, DeviceCodes.Option.P4_HL7_MLLP, "PUBLISHED", "LHR", "ST", "EP", "p", "1.0.0",
                Set.of(DeviceCodes.Category.VITALS), Map.of(), List.of("SESSION"), true, List.of(), "UTC", "GROUP", 1);
        List<AssociationEngine.Candidate> candidates = List.of(
                new AssociationEngine.Candidate("E1001", "VENDOR_ID_MATCH", new BigDecimal("0.97"), "Erin"),
                new AssociationEngine.Candidate("E1002", "MANUAL_RESOLUTION", BigDecimal.ONE, "Paul"));
        assertThat(AssociationServiceAccess.allowed(candidates, sessionOnly)).extracting(AssociationEngine.Candidate::method)
                .containsExactly("MANUAL_RESOLUTION");
        DeviceCatalogueLookup.DeviceInstance unrestricted = new DeviceCatalogueLookup.DeviceInstance("DEV-2", null, null, null, null, null, null,
                null, null, null, "PUBLISHED", null, null, null, null, null, null, null, List.of(), true, null, "UTC", "GROUP", 1);
        assertThat(AssociationServiceAccess.allowed(candidates, unrestricted)).hasSize(2);
    }
}
