package com.wellnessz.e32.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.wellnessz.casekernel.device.DeviceCodes.Family;
import com.wellnessz.e32.support.Docs;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class InventoryMappingTest {

    static List<Map<String, Object>> inventory() throws Exception {
        try (InputStream in = InventoryMappingTest.class.getClassLoader().getResourceAsStream(CatalogueSeedLoader.RESOURCE)) {
            return JsonMapper.builder().build().readValue(in, new TypeReference<List<Map<String, Object>>>() {
            });
        }
    }

    @Test
    void inventoryHas133ModelsWithTheContractOptionTotals() throws Exception {
        List<Map<String, Object>> rows = inventory();
        assertThat(rows).hasSize(133);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            for (String o : InventoryMapping.options(Docs.strings(r.get("options")))) {
                counts.merge(o, 1, Integer::sum);
            }
        }
        assertThat(counts).containsEntry("P1", 73).containsEntry("P2", 66).containsEntry("P4", 40).containsEntry("P6", 32).containsEntry("P3", 22)
                .containsEntry("P10", 13).containsEntry("P9", 12).containsEntry("P5", 12).containsEntry("P7", 7).containsEntry("P8", 4);
    }

    @Test
    void everyRowMapsToOneOfTheElevenFamiliesAndBuildsADetailRecord() throws Exception {
        Map<Family, Integer> perFamily = new LinkedHashMap<>();
        for (Map<String, Object> r : inventory()) {
            Family f = InventoryMapping.family(Docs.str(r.get("family")));
            perFamily.merge(f, 1, Integer::sum);
            Map<String, Object> d = InventoryMapping.detail(r);
            assertThat(d).containsKeys("variants", "guidelineDrivers", "units", "formsFed", "optionNotes", "regulatory", "security");
            assertThat(Docs.list(d.get("optionNotes"))).hasSize(10);
            assertThat(InventoryMapping.categories(f)).isNotEmpty();
        }
        assertThat(perFamily).hasSize(11).containsEntry(Family.CARDIOLOGY, 19).containsEntry(Family.VITALS, 18).containsEntry(Family.SPIROMETRY, 14)
                .containsEntry(Family.WEARABLE, 5);
        assertThatThrownBy(() -> InventoryMapping.family("Dental")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void regulatoryStatusPerMarketFollowsTheFdaColumn() {
        Map<String, Object> us = InventoryMapping.regulatory("DXN;DQA;FLL").get(0);
        assertThat(us).containsEntry("market", "US").containsEntry("status", "CLEARED");
        assertThat(Docs.strings(us.get("productCodes"))).containsExactly("DXN", "DQA", "FLL");
        assertThat(InventoryMapping.regulatory("n/a (not a medical device)").get(0)).containsEntry("status", "NOT_REQUIRED");
        assertThat(InventoryMapping.regulatory("CCK (breath CO analyser – verify)").get(0)).containsEntry("status", "UNKNOWN");
        assertThat(InventoryMapping.fdaCodes("CCK (breath CO analyser – verify)")).containsExactly("CCK");
        assertThat(InventoryMapping.regulatory("BZG")).extracting(m -> m.get("market")).containsExactly("US", "EU", "UK", "AE");
    }

    @Test
    void variantsOptionNotesAndConfidence() {
        assertThat(InventoryMapping.variants("UA-651BLE / UA-1200BLE / UC-352BLE")).containsExactly("UA-651BLE", "UA-1200BLE", "UC-352BLE");
        List<Map<String, Object>> notes = InventoryMapping.optionNotes("P3 BLE / P1 USB to EasyOne Connect; P4 HL7; P2 XML/GDT/PDF export",
                List.of("P1", "P2", "P3", "P4"));
        assertThat(notes.get(2)).containsEntry("option", "P3").containsEntry("supported", true).containsEntry("notes", "BLE");
        assertThat(notes.get(3)).containsEntry("notes", "HL7");
        assertThat(notes.get(9)).containsEntry("option", "P10").containsEntry("supported", false).doesNotContainKey("notes");
        assertThat(InventoryMapping.highConfidence("High")).isTrue();
        assertThat(InventoryMapping.highConfidence("Medium – verify HL7")).isFalse();
        assertThat(InventoryMapping.options(List.of("P10", "P1", "P4"))).containsExactly("P1", "P4", "P10");
    }
}
