package com.wellnessz.e33.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e33.domain.CaptureStateMachine.State;
import com.wellnessz.e33.domain.CaptureStateMachine.Trigger;

class CaptureServiceUnitTest {

    @Test
    void stationDisplayNamesCarryNoTechnicalParameters() {
        assertThat(DeviceListService.displayName(DeviceCodes.Category.SPIROMETRY, "LHR-OH-ROOM-2")).isEqualTo("Spirometer – Room 2");
        assertThat(DeviceListService.displayName(DeviceCodes.Category.AUDIOMETRY, "LHR-OH-BOOTH-1")).isEqualTo("Audiometer – Booth 1");
        assertThat(DeviceListService.displayName(DeviceCodes.Category.VITALS, null)).isEqualTo("Vital signs monitor");
    }

    static com.wellnessz.casekernel.device.DeviceCatalogueLookup.DeviceInstance inst(String id, String model, String serial, String equipment,
                                                                                    String station) {
        return new com.wellnessz.casekernel.device.DeviceCatalogueLookup.DeviceInstance(id, "MD-027", DeviceCodes.Family.VITALS, "Vendor", model,
                serial, null, "1.0", equipment, DeviceCodes.Option.P4_HL7_MLLP, "PUBLISHED", "LHR", station, "EP-1", "pkg", "1.0.0",
                java.util.Set.of(DeviceCodes.Category.VITALS), Map.of(), List.of("SESSION"), true, List.of(), "UTC", "GROUP", 1);
    }

    @Test
    void stationDisplayNamesAreDisambiguatedByModelThenAssetTag() {
        Map<String, String> names = DeviceListService.displayNames(List.of(
                inst("DEV-1", "Connex Spot", "CSM-100042", "VIT-LHR-01", "LHR-OH-ROOM-1"),
                inst("DEV-2", "UA-651BLE", "UA651-77310", null, "LHR-OH-ROOM-1"),
                inst("DEV-3", "Connex Spot", "CSM-100777", null, "LHR-OH-ROOM-1"),
                inst("DEV-4", "Connex Spot", null, null, "LHR-OH-ROOM-2")), DeviceCodes.Category.VITALS);
        assertThat(names).containsEntry("DEV-1", "Vital signs monitor – Room 1 (Connex Spot · VIT-LHR-01)")
                .containsEntry("DEV-2", "Vital signs monitor – Room 1 (UA-651BLE)")
                .containsEntry("DEV-3", "Vital signs monitor – Room 1 (Connex Spot · SN …0777)")
                .containsEntry("DEV-4", "Vital signs monitor – Room 2"); // alone at its station: unchanged
        assertThat(new java.util.HashSet<>(names.values())).hasSize(4);
        // identical model and no asset tag / serial: the instance id keeps the names apart
        Map<String, String> same = DeviceListService.displayNames(List.of(inst("DEV-A", "Scale", null, null, "LHR-OH-ROOM-1"),
                inst("DEV-B", "Scale", null, null, "LHR-OH-ROOM-1")), DeviceCodes.Category.VITALS);
        assertThat(same.get("DEV-A")).isEqualTo("Vital signs monitor – Room 1 (Scale · DEV-A)");
        assertThat(same.get("DEV-B")).isEqualTo("Vital signs monitor – Room 1 (Scale · DEV-B)");
    }

    @Test
    void transitionsAreAuditedPerState() {
        assertThat(CaptureTransitions.auditAction(Trigger.ACCEPT, State.ACCEPTED)).isEqualTo("DEVICE_CAPTURE_ACCEPT");
        assertThat(CaptureTransitions.auditAction(Trigger.RETEST, State.LISTENING)).isEqualTo("DEVICE_CAPTURE_REJECT");
        assertThat(CaptureTransitions.auditAction(Trigger.TIMEOUT, State.EXPIRED)).isEqualTo("DEVICE_CAPTURE_EXPIRE");
        assertThat(CaptureTransitions.auditAction(Trigger.CHOOSE_ENTRY, State.FALLBACK_ENTRY)).isEqualTo("DEVICE_CAPTURE_FALLBACK");
        assertThat(CaptureTransitions.auditAction(Trigger.RESULT, State.PREVIEW)).isEqualTo("DEVICE_CAPTURE_PREVIEW");
        assertThat(CaptureTransitions.auditAction(Trigger.CANCEL, State.CANCELLED)).isEqualTo("DEVICE_CAPTURE_CANCEL");
    }

    @Test
    void provenanceMarkingPerPath() {
        Instant at = Instant.parse("2026-10-31T09:14:05Z");
        assertThat(CaptureViews.marking("DEVICE", "EasyOne Air", at)).isEqualTo("device-sourced · EasyOne Air · 31 Oct 2026 09:14 UTC");
        assertThat(CaptureViews.marking("OCR_VERIFIED", "EasyOne Air", at)).isEqualTo("photo verified");
        assertThat(CaptureViews.marking("MANUAL_ATTESTED", null, at)).isEqualTo("entered manually – attested");
    }

    @Test
    void snapshotKeepsValuesAndText() {
        DeviceObservation o = new DeviceObservation(UUID.randomUUID(), null, DeviceCodes.Category.VITALS, "E1001", null, null, Instant.now(),
                "DEV-000003", null, DeviceCodes.CaptureMethod.DEVICE,
                List.of(new DeviceObservation.Component("systolic", null, "8480-6", "Systolic", "QUANTITY", new BigDecimal("128"), null, "mm[Hg]",
                                null, null, null),
                        new DeviceObservation.Component("position", null, "8361-8", "Position", "CODE", null, "SITTING", null, null, null, null)),
                List.of(), null, List.of(), false);
        Map<String, Object> snap = SummaryReleaser.snapshot(o);
        assertThat(snap).containsEntry("position", "SITTING");
        assertThat(SummaryReleaser.numeric(snap)).containsOnlyKeys("systolic");
        assertThat(SummaryReleaser.numeric(Map.of("a", "12.5", "b", "x", "c", 3))).containsOnlyKeys("a", "c");
    }

    @Test
    void eventHubDeliversWithoutTransaction() {
        CaptureEventHub hub = new CaptureEventHub();
        UUID id = UUID.randomUUID();
        hub.subscribe(id, 1000, List.of());
        assertThat(hub.subscribers(id)).isEqualTo(1);
        hub.publish(id, new CaptureEventHub.Event("state", Map.of("state", "ACCEPTED")), true);
        assertThat(hub.subscribers(id)).isZero();
    }
}
