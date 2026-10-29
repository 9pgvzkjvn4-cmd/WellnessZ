package com.wellnessz.e04.medication;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.casekernel.model.OhCase;
import com.wellnessz.casekernel.support.CaseTimeline;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.DataClassification;
import com.wellnessz.e03.cds.CdsAlert;
import com.wellnessz.e03.cds.CdsService;
import com.wellnessz.e03.model.AllergyRepository;
import com.wellnessz.e03.model.Medication;
import com.wellnessz.e03.model.MedicationRepository;
import com.wellnessz.e04.model.FormularyItem;
import com.wellnessz.e04.model.FormularyItemRepository;
import com.wellnessz.e04.model.MedicationAdministration;
import com.wellnessz.e04.model.MedicationAdministrationRepository;
import com.wellnessz.e04.model.StockBatch;
import com.wellnessz.e04.model.StockBatchRepository;
import com.wellnessz.e04.support.TreatmentAccess;
import com.wellnessz.e04.support.TreatmentSupport;
import com.wellnessz.masterdata.ClinicSite;
import com.wellnessz.masterdata.ClinicSiteRepository;
import com.wellnessz.security.abac.AccessPolicy;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Clinic medication administration (E04-F05): formulary, stock batches by site, administration with allergy check against
 * the E03 record (override with reason), batch / expiry checks and stock decrement, mirrored into the E03 medication list
 * (source ADMINISTERED); monthly usage by item and site.
 */
@Service
public class MedicationAdminService {

    private final FormularyItemRepository formulary;
    private final StockBatchRepository stock;
    private final MedicationAdministrationRepository administrations;
    private final AllergyRepository allergies;
    private final MedicationRepository medications;
    private final CdsService cds;
    private final ClinicSiteRepository sites;
    private final TreatmentAccess access;
    private final CaseTimeline timeline;
    private final EPrescribingGateway eprescribing;
    private final TreatmentSupport support;
    private final AuditService audit;

    public MedicationAdminService(FormularyItemRepository formulary, StockBatchRepository stock, MedicationAdministrationRepository administrations,
                                  AllergyRepository allergies, MedicationRepository medications, CdsService cds, ClinicSiteRepository sites,
                                  TreatmentAccess access, CaseTimeline timeline, EPrescribingGateway eprescribing, TreatmentSupport support,
                                  AuditService audit) {
        this.formulary = formulary;
        this.stock = stock;
        this.administrations = administrations;
        this.allergies = allergies;
        this.medications = medications;
        this.cds = cds;
        this.sites = sites;
        this.access = access;
        this.timeline = timeline;
        this.eprescribing = eprescribing;
        this.support = support;
        this.audit = audit;
    }

    static final String SITE = "[A-Z0-9][A-Z0-9_\\-]{1,39}";

    public record FormularyCommand(@NotBlank @Size(max = 200) String name, @NotBlank @Pattern(regexp = "[A-Z_]{2,20}") String form,
                                   @Size(max = 60) String strength, @NotBlank @Size(max = 20) String unit,
                                   @NotEmpty @Size(max = 5) List<@Pattern(regexp = "[A-Z_]{2,30}") String> route,
                                   @Pattern(regexp = "\\d{6,18}") String snomedCode, Boolean stockTracked, @Min(0) @Max(100000) Integer reorderLevel,
                                   Boolean active) {
    }

    public record FormularyView(String code, String name, String form, String strength, String unit, List<String> route, String snomedCode,
                                boolean stockTracked, int reorderLevel, boolean active) {
    }

    public record StockCommand(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String itemCode,
                               @NotBlank @Pattern(regexp = "[A-Za-z0-9/\\-]{1,40}") String batchNumber, @NotNull LocalDate expiry,
                               @NotNull @Min(1) @Max(100000) Integer quantity, @NotBlank @Pattern(regexp = SITE) String siteCode) {
    }

    public record StockView(UUID id, String itemCode, String name, String batchNumber, LocalDate expiry, int quantity, String siteCode,
                            boolean belowReorder, boolean expired) {
    }

    public record AdministerCommand(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String itemCode, @NotBlank @Size(max = 100) String dose,
                                    @NotBlank @Pattern(regexp = "[A-Z_]{2,30}") String route,
                                    @NotBlank @Pattern(regexp = "[A-Za-z0-9/\\-]{1,40}") String batchNumber,
                                    @NotBlank @Pattern(regexp = SITE) String siteCode, @Size(max = 500) String overrideAllergyReason) {
    }

    public record AdministrationView(UUID id, UUID caseId, String itemCode, String name, String dose, String route, String batchNumber,
                                     LocalDate expiry, String siteCode, String administeredBy, Instant administeredAt, String allergyOverride) {
    }

    public record UsageRow(String month, String itemCode, String name, String siteCode, int administrations) {
    }

    // ------------------------------------------------------------------ formulary

    @Transactional(readOnly = true)
    public List<FormularyView> formulary() {
        return formulary.findAllByOrderByCode().stream().map(MedicationAdminService::view).toList();
    }

    @Transactional(readOnly = true)
    public FormularyView item(String code) {
        return formulary.findById(code).map(MedicationAdminService::view).orElseThrow(() -> ApiException.notFound("Formulary item"));
    }

    static FormularyView view(FormularyItem f) {
        return new FormularyView(f.getCode(), f.getName(), f.getForm(), f.getStrength(), f.getUnit(), TreatmentSupport.csv(f.getRoutes()),
                f.getSnomedCode(), f.isStockTracked(), f.getReorderLevel(), f.isActive());
    }

    @Transactional
    public FormularyView putItem(CurrentUser user, String code, FormularyCommand cmd) {
        FormularyItem f = formulary.findById(code).orElseGet(() -> {
            FormularyItem n = new FormularyItem();
            n.setCode(code);
            n.setStockTracked(true);
            n.setActive(true);
            return n;
        });
        f.setName(cmd.name().strip());
        f.setForm(cmd.form());
        f.setStrength(cmd.strength());
        f.setUnit(cmd.unit().strip());
        f.setRoutes(TreatmentSupport.joinCsv(cmd.route()));
        f.setSnomedCode(cmd.snomedCode());
        if (cmd.stockTracked() != null) {
            f.setStockTracked(cmd.stockTracked());
        }
        if (cmd.reorderLevel() != null) {
            f.setReorderLevel(cmd.reorderLevel());
        }
        if (cmd.active() != null) {
            f.setActive(cmd.active());
        }
        f.setUpdatedBy(user.username());
        f.setUpdatedAt(support.now());
        formulary.save(f);
        audit.record("FORMULARY_PUT", "FORMULARY_ITEM", code, AuditService.Outcome.SUCCESS, null);
        return view(f);
    }

    // ------------------------------------------------------------------ stock

    @Transactional
    public StockView receive(CurrentUser user, StockCommand cmd) {
        FormularyItem f = formulary.findById(cmd.itemCode()).orElseThrow(() -> ApiException.badRequest("unknown_item", "Unknown formulary item"));
        requireSite(cmd.siteCode());
        if (cmd.expiry().isBefore(support.today())) {
            throw TreatmentSupport.unprocessable("batch_expired", "The batch is already expired");
        }
        StockBatch b = stock.findByItemCodeAndBatchNumberAndSiteCode(cmd.itemCode(), cmd.batchNumber(), cmd.siteCode()).orElse(null);
        if (b != null) {
            if (!b.getExpiry().equals(cmd.expiry())) {
                throw ApiException.conflict("batch_expiry_mismatch", "The batch is already held with another expiry date");
            }
            b.setQuantity(b.getQuantity() + cmd.quantity());
        } else {
            b = new StockBatch();
            b.setId(UUID.randomUUID());
            b.setItemCode(cmd.itemCode());
            b.setBatchNumber(cmd.batchNumber());
            b.setExpiry(cmd.expiry());
            b.setQuantity(cmd.quantity());
            b.setSiteCode(cmd.siteCode());
        }
        b.setReceivedBy(user.username());
        b.setReceivedAt(support.now());
        stock.save(b);
        audit.record("STOCK_RECEIVE", "STOCK_BATCH", b.getId().toString(), AuditService.Outcome.SUCCESS, cmd.itemCode() + " +" + cmd.quantity());
        return stockViews(List.of(b), Map.of(f.getCode(), f)).get(0);
    }

    @Transactional(readOnly = true)
    public List<StockView> stock(String siteCode) {
        List<StockBatch> list = siteCode == null ? stock.findAllByOrderByItemCodeAscExpiryAsc() : stock.findBySiteCodeOrderByItemCodeAscExpiryAsc(siteCode);
        Map<String, FormularyItem> items = new HashMap<>();
        formulary.findAll().forEach(f -> items.put(f.getCode(), f));
        return stockViews(list, items);
    }

    private List<StockView> stockViews(List<StockBatch> list, Map<String, FormularyItem> items) {
        LocalDate today = support.today();
        Map<String, Integer> usable = new HashMap<>();
        List<StockBatch> all = list.size() == 1 ? stock.findBySiteCodeOrderByItemCodeAscExpiryAsc(list.get(0).getSiteCode()) : list;
        for (StockBatch b : all) {
            if (!b.getExpiry().isBefore(today)) {
                usable.merge(b.getItemCode() + "|" + b.getSiteCode(), b.getQuantity(), Integer::sum);
            }
        }
        List<StockView> out = new ArrayList<>();
        for (StockBatch b : list) {
            FormularyItem f = items.get(b.getItemCode());
            int level = f == null ? 0 : f.getReorderLevel();
            out.add(new StockView(b.getId(), b.getItemCode(), f == null ? b.getItemCode() : f.getName(), b.getBatchNumber(), b.getExpiry(),
                    b.getQuantity(), b.getSiteCode(), usable.getOrDefault(b.getItemCode() + "|" + b.getSiteCode(), 0) < level,
                    b.getExpiry().isBefore(today)));
        }
        return out;
    }

    private void requireSite(String siteCode) {
        sites.findById(siteCode).filter(ClinicSite::isActive).orElseThrow(() -> ApiException.badRequest("unknown_site", "Unknown clinic site"));
    }

    // ------------------------------------------------------------------ administration

    @Transactional
    public AdministrationView administer(CurrentUser user, UUID caseId, AdministerCommand cmd) {
        OhCase c = access.clinical(user, caseId, AccessPolicy.Action.UPDATE, "MEDICATION_ADMINISTER");
        FormularyItem f = formulary.findById(cmd.itemCode()).filter(FormularyItem::isActive)
                .orElseThrow(() -> ApiException.badRequest("unknown_item", "Unknown or inactive formulary item"));
        if (!TreatmentSupport.csv(f.getRoutes()).contains(cmd.route())) {
            throw ApiException.badRequest("invalid_route", "Route not permitted for this item");
        }
        requireSite(cmd.siteCode());
        StockBatch b = stock.findByItemCodeAndBatchNumberAndSiteCode(f.getCode(), cmd.batchNumber(), cmd.siteCode()).orElse(null);
        String problem = MedicationChecks.batchProblem(b, support.today(), f.isStockTracked());
        if (problem != null) {
            throw TreatmentSupport.unprocessable(problem, switch (problem) {
                case "batch_unknown" -> "Batch not held at this site";
                case "batch_expired" -> "The batch is expired – do not administer";
                default -> "No stock left in this batch";
            });
        }
        List<MedicationChecks.Substance> recorded = allergies.findByEmployeeIdOrderByRecordedAtDesc(c.getEmployeeId()).stream()
                .filter(a -> "ACTIVE".equals(a.getStatus()))
                .map(a -> new MedicationChecks.Substance(a.getSubstanceDisplay(), a.getSubstanceCode())).toList();
        List<String> alerts = new ArrayList<>(MedicationChecks.allergyAlerts(f.getName(), f.getSnomedCode(), recorded));
        cds.medicationAlerts(f.getName(), recorded.stream().map(s -> new CdsService.Substance(s.display(), s.code())).toList(), List.of()).stream()
                .filter(a -> CdsAlert.ALLERGY_CLASS.equals(a.code())).map(CdsAlert::message).filter(m -> !alerts.contains(m)).forEach(alerts::add);
        String override = TreatmentSupport.clinical("overrideAllergyReason", cmd.overrideAllergyReason(), 500);
        if (!alerts.isEmpty() && override == null) {
            audit.record("MEDICATION_ADMINISTER", "CASE", caseId.toString(), AuditService.Outcome.DENIED, "allergy_alert");
            throw new MedicationChecks.AllergyAlertException(alerts);
        }
        if (f.isStockTracked()) {
            b.setQuantity(b.getQuantity() - 1);
        }
        Instant now = support.now();
        Medication m = new Medication();
        m.setId(UUID.randomUUID());
        m.setEmployeeId(c.getEmployeeId());
        m.setName(f.getName() + (f.getStrength() == null ? "" : " " + f.getStrength()));
        if (f.getSnomedCode() != null) {
            m.setCodeSystem("SNOMED_CT");
            m.setCode(f.getSnomedCode());
            m.setCodeDisplay(f.getName());
        }
        m.setDose(cmd.dose().strip());
        m.setRoute(cmd.route());
        m.setFrequency("once");
        m.setSource("ADMINISTERED");
        m.setStatus("COMPLETED");
        m.setStartDate(support.today());
        m.setEndDate(support.today());
        m.setClassification(DataClassification.CLINICAL.name());
        m.setOverrides(alerts.isEmpty() ? null : support.write(List.of(Map.of("alertCode", CdsAlert.ALLERGY_CLASS, "reason", override))));
        m.setRecordedBy(user.username());
        m.setRecordedAt(now);
        m.setUpdatedAt(now);
        medications.save(m);
        MedicationAdministration a = new MedicationAdministration();
        a.setId(UUID.randomUUID());
        a.setCaseId(c.getId());
        a.setEmployeeId(c.getEmployeeId());
        a.setItemCode(f.getCode());
        a.setItemName(f.getName());
        a.setDose(cmd.dose().strip());
        a.setRoute(cmd.route());
        a.setBatchNumber(b.getBatchNumber());
        a.setExpiry(b.getExpiry());
        a.setSiteCode(cmd.siteCode());
        a.setAdministeredBy(user.username());
        a.setAdministeredAt(now);
        a.setAllergyOverride(alerts.isEmpty() ? null : override);
        a.setMedicationId(m.getId());
        administrations.save(a);
        timeline.record(c.getId(), "MEDICATION_ADMINISTERED", "Clinic medication administered" + (alerts.isEmpty() ? "" : " (allergy alert overridden)"));
        audit.record("MEDICATION_ADMINISTER", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS,
                f.getCode() + " batch=" + b.getBatchNumber() + (alerts.isEmpty() ? "" : " allergyOverride"));
        return view(a);
    }

    @Transactional(readOnly = true)
    public List<AdministrationView> administrations(CurrentUser user, UUID caseId) {
        access.clinical(user, caseId, AccessPolicy.Action.READ, "MEDICATION_ADMINISTRATIONS_READ");
        List<AdministrationView> out = administrations.findByCaseIdOrderByAdministeredAtDesc(caseId).stream().map(MedicationAdminService::view).toList();
        audit.record("MEDICATION_ADMINISTRATIONS_READ", "CASE", caseId.toString(), AuditService.Outcome.SUCCESS, "rows=" + out.size());
        return out;
    }

    static AdministrationView view(MedicationAdministration a) {
        return new AdministrationView(a.getId(), a.getCaseId(), a.getItemCode(), a.getItemName(), a.getDose(), a.getRoute(), a.getBatchNumber(),
                a.getExpiry(), a.getSiteCode(), a.getAdministeredBy(), a.getAdministeredAt(), a.getAllergyOverride());
    }

    @Transactional(readOnly = true)
    public List<UsageRow> usage(YearMonth month) {
        Instant from = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = month.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, String> names = new HashMap<>();
        for (MedicationAdministration a : administrations.findByAdministeredAtBetween(from, to)) {
            counts.merge(a.getItemCode() + "|" + a.getSiteCode(), 1, Integer::sum);
            names.put(a.getItemCode(), a.getItemName());
        }
        List<UsageRow> out = new ArrayList<>();
        counts.forEach((k, n) -> {
            String[] p = k.split("\\|", 2);
            out.add(new UsageRow(month.toString(), p[0], names.get(p[0]), p[1], n));
        });
        return out;
    }

    public EPrescribingGateway.Status eprescribing() {
        return eprescribing.status();
    }
}
