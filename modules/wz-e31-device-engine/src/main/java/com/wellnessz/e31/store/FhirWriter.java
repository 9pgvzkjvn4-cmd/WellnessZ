package com.wellnessz.e31.store;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.hl7.fhir.r4.model.Attachment;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Device;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Extension;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.InstantType;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Provenance;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.hl7.fhir.r4.model.Type;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;

import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.e31.support.Ids;

/**
 * Writes the canonical FHIR R4 resources of an observation (contract sections 4.1–4.5): Device (PoCD MDS level),
 * Observation (single value at top level, panels as components; quality flags, capture method, clock skew and binding keys
 * as extensions), DiagnosticReport for composite results, and Provenance. Output is validated by a strict HAPI re-parse.
 */
public final class FhirWriter {

    public static final String LOINC = "http://loinc.org";
    public static final String MDC = "urn:iso:std:iso:11073:10101";
    public static final String UCUM = "http://unitsofmeasure.org";
    static final String LOCAL = "urn:wellnessz:code";
    static final Set<DeviceCodes.Category> COMPOSITE = Set.of(DeviceCodes.Category.AUDIOMETRY, DeviceCodes.Category.SPIROMETRY,
            DeviceCodes.Category.ECG, DeviceCodes.Category.POC, DeviceCodes.Category.FIT_TEST, DeviceCodes.Category.ABPM_HOLTER,
            DeviceCodes.Category.IMAGING);
    /** Panel codes per category (LOINC where one exists). */
    static final Map<DeviceCodes.Category, String[]> PANELS = Map.ofEntries(
            Map.entry(DeviceCodes.Category.SPIROMETRY, new String[] {LOINC, "81459-0", "Spirometry panel"}),
            Map.entry(DeviceCodes.Category.AUDIOMETRY, new String[] {LOINC, "28539-6", "Audiogram study"}),
            Map.entry(DeviceCodes.Category.VITALS, new String[] {LOINC, "85353-1", "Vital signs panel"}),
            Map.entry(DeviceCodes.Category.ECG, new String[] {LOINC, "11524-6", "EKG study"}),
            Map.entry(DeviceCodes.Category.VISION, new String[] {LOINC, "28631-1", "Visual acuity panel"}),
            Map.entry(DeviceCodes.Category.BODY_COMPOSITION, new String[] {LOINC, "29463-7", "Body weight and composition"}),
            Map.entry(DeviceCodes.Category.BREATH_ALCOHOL, new String[] {LOINC, "75367-0", "Alcohol in breath"}),
            Map.entry(DeviceCodes.Category.WEARABLE, new String[] {LOCAL, "WEARABLE_DAILY", "Wearable daily aggregates"}),
            Map.entry(DeviceCodes.Category.FIT_TEST, new String[] {LOCAL, "FIT_TEST", "Respirator fit test"}),
            Map.entry(DeviceCodes.Category.EXPOSURE, new String[] {LOCAL, "EXPOSURE", "Exposure measurement"}),
            Map.entry(DeviceCodes.Category.IMAGING, new String[] {LOCAL, "IMAGING_STUDY", "Imaging study"}),
            Map.entry(DeviceCodes.Category.POC, new String[] {LOCAL, "POC_PANEL", "Point-of-care panel"}));

    private final FhirContext fhir;

    public FhirWriter(FhirContext fhir) {
        this.fhir = fhir;
    }

    public record Written(String resourceType, String id, String json) {
    }

    public List<Written> write(ObservationStore.ObservationRow o, DeviceCatalogueLookup.DeviceInstance instance, String tenantCode, String endpointId,
                               String rawArchiveId, String preliminaryOrFinal) {
        List<Written> out = new ArrayList<>();
        Meta meta = new Meta();
        meta.addTag().setSystem("urn:wellnessz:tenant").setCode(tenantCode);
        String deviceRef = null;
        if (o.deviceInstanceId() != null) {
            Device d = device(o, instance);
            d.setMeta(meta.copy());
            deviceRef = "Device/" + d.getIdElement().getIdPart();
            out.add(serialise(d));
        }
        Observation obs = observation(o, deviceRef, preliminaryOrFinal);
        obs.setMeta(meta.copy());
        out.add(serialise(obs));
        if (o.reportId() != null && COMPOSITE.contains(o.category())) {
            DiagnosticReport r = report(o, preliminaryOrFinal);
            r.setMeta(meta.copy());
            out.add(serialise(r));
        }
        Provenance p = provenance(o, deviceRef, endpointId, rawArchiveId);
        p.setMeta(meta.copy());
        out.add(serialise(p));
        return out;
    }

    Written serialise(Resource r) {
        IParser parser = fhir.newJsonParser().setPrettyPrint(false);
        String json = parser.encodeResourceToString(r);
        IParser strict = fhir.newJsonParser();
        strict.setParserErrorHandler(new StrictErrorHandler());
        strict.parseResource(json); // validation: the stored JSON must re-parse strictly
        return new Written(r.fhirType(), r.getIdElement().getIdPart(), json);
    }

    Device device(ObservationStore.ObservationRow o, DeviceCatalogueLookup.DeviceInstance instance) {
        Device d = new Device();
        d.setId(safeId(o.deviceInstanceId()));
        d.addIdentifier().setSystem("urn:wellnessz:device-instance").setValue(o.deviceInstanceId());
        DeviceObservation.Provenance p = o.canonical().provenance();
        if (o.equipmentDeviceId() != null) {
            d.addIdentifier().setSystem("urn:wellnessz:equipment").setValue(o.equipmentDeviceId());
        }
        if (p != null && p.serial() != null) {
            d.addIdentifier().setType(new CodeableConcept().setText("Serial")).setValue(p.serial());
            d.setSerialNumber(p.serial());
        }
        if (p != null && p.udi() != null) {
            d.addUdiCarrier().setDeviceIdentifier(p.udi());
        }
        d.setStatus(Device.FHIRDeviceStatus.ACTIVE);
        if (p != null && p.manufacturer() != null) {
            d.setManufacturer(p.manufacturer());
        }
        if (p != null && p.model() != null) {
            d.addDeviceName().setName(p.model()).setType(Device.DeviceNameType.MODELNAME);
        }
        if (p != null && p.modelCode() != null) {
            d.setModelNumber(p.modelCode());
        }
        if (p != null && p.firmware() != null) {
            d.addVersion().setType(new CodeableConcept().setText("firmware")).setValue(p.firmware());
        }
        if (p != null && p.softwareVersion() != null) {
            d.addVersion().setType(new CodeableConcept().setText("software")).setValue(p.softwareVersion());
        }
        if (instance != null && instance.stationCode() != null) {
            d.setLocation(new Reference().setIdentifier(new Identifier().setSystem("urn:wellnessz:station").setValue(instance.stationCode())));
        }
        if (p != null && p.calibrationVerdict() != null) {
            d.addProperty().setType(new CodeableConcept().setText("calibrationStatus")).addValueCode(new CodeableConcept().setText(p.calibrationVerdict()));
        }
        return d;
    }

    Observation observation(ObservationStore.ObservationRow o, String deviceRef, String status) {
        Observation obs = new Observation();
        obs.setId(o.id().toString());
        obs.setStatus("final".equals(status) ? Observation.ObservationStatus.FINAL : Observation.ObservationStatus.PRELIMINARY);
        String cat = switch (o.category()) {
            case VITALS, BODY_COMPOSITION -> "vital-signs";
            case POC, BREATH_ALCOHOL, DRUG_SCREEN -> "laboratory";
            case IMAGING -> "imaging";
            case WEARABLE -> "activity";
            default -> "exam";
        };
        obs.addCategory().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/observation-category").setCode(cat);
        List<DeviceObservation.Component> comps = o.canonical().components();
        if (comps.size() == 1) {
            DeviceObservation.Component c = comps.get(0);
            obs.setCode(code(c));
            obs.setValue(value(c));
            if (c.bodySite() != null) {
                obs.setBodySite(new CodeableConcept().setText(c.bodySite()));
            }
            if (c.interpretation() != null) {
                obs.addInterpretation().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation")
                        .setCode(c.interpretation());
            }
            obs.addExtension("urn:wellnessz:fhir:binding-key", new StringType(c.key()));
        } else {
            String[] panel = PANELS.getOrDefault(o.category(), new String[] {LOCAL, o.category().name(), o.category().name()});
            obs.setCode(new CodeableConcept().addCoding(new Coding(panel[0], panel[1], panel[2])));
            for (DeviceObservation.Component c : comps) {
                Observation.ObservationComponentComponent oc = obs.addComponent();
                oc.setCode(code(c));
                oc.setValue(value(c));
                if (c.interpretation() != null) {
                    oc.addInterpretation().addCoding().setSystem("http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation")
                            .setCode(c.interpretation());
                }
                if (c.bodySite() != null) {
                    oc.addExtension("urn:wellnessz:fhir:body-site", new CodeType(c.bodySite()));
                }
                oc.addExtension("urn:wellnessz:fhir:binding-key", new StringType(c.key()));
            }
        }
        if (o.employeeId() != null) {
            obs.setSubject(new Reference().setIdentifier(new Identifier().setSystem("urn:wellnessz:employee").setValue(o.employeeId())));
        }
        if (o.caseId() != null) {
            obs.setEncounter(new Reference().setIdentifier(new Identifier().setSystem("urn:wellnessz:case").setValue(o.caseId().toString())));
        }
        if (o.orderRef() != null) {
            obs.addBasedOn(new Reference().setIdentifier(new Identifier().setSystem("urn:wellnessz:order").setValue(o.orderRef())));
        }
        obs.setEffective(new DateTimeType(Date.from(o.effectiveAt())));
        obs.setIssued(Date.from(o.createdAt() == null ? o.effectiveAt() : o.createdAt()));
        DeviceObservation.Provenance p = o.canonical().provenance();
        if (p != null && p.operator() != null) {
            obs.addPerformer(new Reference().setIdentifier(new Identifier().setSystem("urn:wellnessz:user").setValue(p.operator())));
        }
        if (deviceRef != null) {
            obs.setDevice(new Reference(deviceRef));
        }
        for (DeviceObservation.QualityFlag f : o.canonical().flags()) {
            Extension e = new Extension("urn:wellnessz:fhir:quality-flag");
            e.addExtension("gate", new CodeType(f.gate()));
            e.addExtension("outcome", new CodeType(f.outcome().name()));
            e.addExtension("code", new CodeType(f.code()));
            obs.addExtension(e);
        }
        obs.addExtension("urn:wellnessz:fhir:capture-method", new CodeType(o.captureMethod().name()));
        obs.addExtension("urn:wellnessz:fhir:clock-skew-ms", new IntegerType((int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, o.clockSkewMs()))));
        return obs;
    }

    static CodeableConcept code(DeviceObservation.Component c) {
        CodeableConcept cc = new CodeableConcept();
        if (c.loincCode() != null) {
            cc.addCoding(new Coding(c.loincCode().startsWith("LOCAL:") ? LOCAL : LOINC, c.loincCode(), c.display()));
        }
        if (c.mdcCode() != null) {
            cc.addCoding(new Coding(MDC, c.mdcCode(), null));
        }
        cc.setText(c.display());
        return cc;
    }

    static Type value(DeviceObservation.Component c) {
        if ("QUANTITY".equals(c.valueType()) && c.value() != null) {
            Quantity q = new Quantity();
            q.setValue(c.value());
            if (c.ucum() != null) {
                q.setUnit(c.ucum()).setSystem(UCUM).setCode(c.ucum());
            }
            return q;
        }
        if ("BOOLEAN".equals(c.valueType())) {
            return new BooleanType("true".equals(c.text()));
        }
        if ("CODE".equals(c.valueType())) {
            return new CodeableConcept().setText(c.text());
        }
        return new StringType(c.text());
    }

    DiagnosticReport report(ObservationStore.ObservationRow o, String status) {
        DiagnosticReport r = new DiagnosticReport();
        r.setId(o.reportId().toString());
        r.setStatus("final".equals(status) ? DiagnosticReport.DiagnosticReportStatus.FINAL : DiagnosticReport.DiagnosticReportStatus.PRELIMINARY);
        String[] panel = PANELS.getOrDefault(o.category(), new String[] {LOCAL, o.category().name(), o.category().name()});
        r.setCode(new CodeableConcept().addCoding(new Coding(panel[0], panel[1], panel[2])));
        if (o.employeeId() != null) {
            r.setSubject(new Reference().setIdentifier(new Identifier().setSystem("urn:wellnessz:employee").setValue(o.employeeId())));
        }
        r.setEffective(new DateTimeType(Date.from(o.effectiveAt())));
        r.addResult(new Reference("Observation/" + o.id()));
        for (DeviceObservation.Attachment a : o.canonical().attachments()) {
            Attachment att = r.addPresentedForm();
            att.setContentType(a.mediaType());
            att.setUrl("urn:wellnessz:raw:" + a.archiveId());
            att.setSize((int) Math.min(Integer.MAX_VALUE, a.sizeBytes()));
            att.addExtension("urn:wellnessz:fhir:sha256", new StringType(a.sha256()));
        }
        o.canonical().components().stream().filter(c -> "interpretation".equals(c.key()) && c.text() != null).findFirst().ifPresent(c -> {
            r.setConclusion(c.text());
            r.addExtension("urn:wellnessz:fhir:interpretation-status", new CodeType("UNVERIFIED"));
        });
        return r;
    }

    Provenance provenance(ObservationStore.ObservationRow o, String deviceRef, String endpointId, String rawArchiveId) {
        Provenance p = new Provenance();
        p.setId(Ids.uuidV5("provenance|" + o.id()).toString());
        p.addTarget(new Reference("Observation/" + o.id()));
        p.setRecordedElement(new InstantType(Date.from(o.createdAt() == null ? o.effectiveAt() : o.createdAt())));
        p.setActivity(new CodeableConcept().setText(switch (o.captureMethod()) {
            case OCR_VERIFIED -> "OCR_VERIFIED_CAPTURE";
            case MANUAL_ATTESTED -> "MANUAL_ATTESTED_ENTRY";
            default -> "DEVICE_CAPTURE";
        }));
        DeviceObservation.Provenance pv = o.canonical().provenance();
        if (deviceRef != null) {
            p.addAgent().setType(new CodeableConcept().setText("device")).setWho(new Reference(deviceRef));
        }
        if (pv != null && pv.operator() != null) {
            p.addAgent().setType(new CodeableConcept().setText("operator")).setWho(new Reference().setIdentifier(new Identifier().setValue(pv.operator())));
        }
        if (o.verifiedBy() != null) {
            p.addAgent().setType(new CodeableConcept().setText("verifier")).setWho(new Reference().setIdentifier(new Identifier().setValue(o.verifiedBy())));
        }
        if (o.driverPackageId() != null) {
            p.addAgent().setType(new CodeableConcept().setText("driver")).setWho(new Reference().setDisplay(o.driverPackageId() + "@" + o.driverVersion()));
        }
        p.addAgent().setType(new CodeableConcept().setText("endpoint")).setWho(new Reference().setDisplay(endpointId == null ? "unknown" : endpointId));
        if (rawArchiveId != null) {
            p.addEntity().setRole(Provenance.ProvenanceEntityRole.SOURCE).setWhat(new Reference()
                    .setIdentifier(new Identifier().setSystem("urn:wellnessz:raw").setValue(rawArchiveId))
                    .setDisplay("sha256:" + (pv == null || pv.rawPayloadSha256() == null ? "" : pv.rawPayloadSha256())));
        }
        if (pv != null && pv.calibrationVerdict() != null) {
            p.addExtension("urn:wellnessz:fhir:calibration", new StringType((pv.calibrationId() == null ? "" : pv.calibrationId()) + "|"
                    + (pv.calibrationDate() == null ? "" : pv.calibrationDate()) + "|" + pv.calibrationVerdict()));
        }
        if (o.associationMethod() != null) {
            p.addExtension("urn:wellnessz:fhir:association", new StringType(o.associationMethod() + "|" + (o.confidence() == null ? "" : o.confidence())));
        }
        return p;
    }

    static String safeId(String id) {
        String s = id.replaceAll("[^A-Za-z0-9\\-.]", "-");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** FHIR attachment hash: base64 SHA-1 is required by R4; the engine adds the SHA-256 in an extension. */
    static String base64(String hex) {
        return Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
    }
}
