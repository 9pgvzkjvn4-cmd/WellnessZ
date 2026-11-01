package com.wellnessz.e31.association;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.wellnessz.casekernel.device.DeviceCaptureSessions;
import com.wellnessz.casekernel.device.DeviceCatalogueLookup;
import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceSpis;
import com.wellnessz.casekernel.device.DeviceWorklistSource;
import com.wellnessz.e31.adapter.ParsedRecord;
import com.wellnessz.e31.driver.DriverRuntime;
import com.wellnessz.e31.store.ObservationStore;
import com.wellnessz.e31.support.EngineSettings;
import com.wellnessz.masterdata.Employee;
import com.wellnessz.masterdata.EmployeeRepository;

/**
 * Collects association candidates (worklist, capture session, badge, vendor id) and decides with {@link AssociationEngine}.
 * Every candidate is kept as {@code association_candidate} for audit.
 */
@Service("e31AssociationService")
public class AssociationService {

    static final BigDecimal C100 = new BigDecimal("1.00");
    static final BigDecimal C098 = new BigDecimal("0.98");
    static final BigDecimal C097 = new BigDecimal("0.97");
    static final BigDecimal C092 = new BigDecimal("0.92");
    static final BigDecimal C090 = new BigDecimal("0.90");
    static final BigDecimal C080 = new BigDecimal("0.80");
    static final BigDecimal C070 = new BigDecimal("0.70");
    static final List<String> DEFAULT_PREFIXES = List.of("EMP", "WZ", "ID");

    private final DeviceSpis spis;
    private final EmployeeRepository employees;
    private final ObservationStore observations;
    private final EngineSettings settings;

    public AssociationService(DeviceSpis spis, EmployeeRepository employees, ObservationStore observations, EngineSettings settings) {
        this.spis = spis;
        this.employees = employees;
        this.observations = observations;
        this.settings = settings;
    }

    /**
     * @param existing         the stored observation on reprocessing (keeps a clinician's manual resolution)
     * @param enteredEmployee  employee selected in the form for a fall-back entry (ENTERED_IN_FORM)
     * @param session          the capture session listening on the instance at the measurement time (looked up by the caller)
     */
    public record Request(UUID observationId, DeviceCatalogueLookup.DeviceInstance instance, ParsedRecord.Identity identity,
                          DeviceCodes.Category category, Instant effectiveAt, boolean restricted, ObservationStore.ObservationRow existing,
                          String enteredEmployee, DeviceCaptureSessions.SessionBinding session, boolean persistCandidates) {
    }

    /** The capture session listening on a (published) instance, if the measurement falls into its window. */
    public Optional<DeviceCaptureSessions.SessionBinding> session(DeviceCatalogueLookup.DeviceInstance instance, Instant effectiveAt) {
        if (instance == null || DriverRuntime.virtual(instance.deviceInstanceId())) {
            return Optional.empty();
        }
        return spis.sessions().activeSession(instance.deviceInstanceId(), effectiveAt).filter(s -> inWindow(s, effectiveAt));
    }

    public record Result(AssociationEngine.Decision decision, DeviceCaptureSessions.SessionBinding session, String orderRef, UUID caseId,
                         List<AssociationEngine.Candidate> candidates) {
    }

    public Result associate(Request r) {
        List<AssociationEngine.Candidate> candidates = new ArrayList<>();
        boolean conflict = false;
        String messageEmployee = r.identity().employeeId();
        String orderRef = r.identity().orderRef();
        UUID caseId = null;
        DeviceCaptureSessions.SessionBinding session = null;
        if (r.existing() != null && "MANUAL_RESOLUTION".equals(r.existing().associationMethod()) && r.existing().employeeId() != null) {
            candidates.add(new AssociationEngine.Candidate(r.existing().employeeId(), "MANUAL_RESOLUTION", C100, null));
            orderRef = r.existing().orderRef() != null ? r.existing().orderRef() : orderRef;
            caseId = r.existing().caseId();
        }
        if (r.category() == DeviceCodes.Category.EXPOSURE && messageEmployee == null && r.identity().badgeId() == null) {
            candidates.add(new AssociationEngine.Candidate(null, "AREA_MEASUREMENT", C100, null));
        }
        if (r.enteredEmployee() != null) {
            employees.findById(r.enteredEmployee()).filter(Employee::isActive)
                    .ifPresent(e -> candidates.add(new AssociationEngine.Candidate(e.getEmployeeId(), "ENTERED_IN_FORM", C100, e.getDisplayName())));
        }
        // 2 – capture session at the station
        if (r.session() != null) {
            session = r.session();
            if (messageEmployee != null && !sameEmployee(messageEmployee, session.employeeId())) {
                conflict = true;
                candidates.add(new AssociationEngine.Candidate(session.employeeId(), "SESSION", C080, name(session.employeeId())));
            } else {
                candidates.add(new AssociationEngine.Candidate(session.employeeId(), "SESSION", C100, name(session.employeeId())));
            }
            if (orderRef == null) {
                orderRef = session.orderRef();
            }
            caseId = session.caseId();
        }
        // 1 – worklist / order reference issued by the platform
        if (orderRef != null && r.instance() != null) {
            for (DeviceWorklistSource.WorklistEntry e : worklist(r.instance(), r.effectiveAt(), r.category())) {
                if (!sameRef(orderRef, e.orderRef())) {
                    continue;
                }
                if (messageEmployee == null) {
                    candidates.add(new AssociationEngine.Candidate(e.employeeId(), "WORKLIST", C090, e.displayName()));
                } else if (sameEmployee(messageEmployee, e.employeeId())) {
                    candidates.add(new AssociationEngine.Candidate(e.employeeId(), "WORKLIST", C100, e.displayName()));
                } else {
                    conflict = true;
                    candidates.add(new AssociationEngine.Candidate(e.employeeId(), "WORKLIST", C080, e.displayName()));
                }
                orderRef = e.orderRef();
                if (caseId == null) {
                    caseId = e.caseId();
                }
            }
        }
        // 3 – badge / NFC (exact only)
        if (r.identity().badgeId() != null) {
            employees.findByBadgeIdAndActiveTrue(r.identity().badgeId().strip())
                    .ifPresent(e -> candidates.add(new AssociationEngine.Candidate(e.getEmployeeId(), "BADGE", C098, e.getDisplayName())));
        }
        // 4 – vendor-typed employee id, then vendor demographics
        if (messageEmployee != null) {
            candidates.addAll(vendorId(messageEmployee, prefixes(r.instance())));
        }
        if (r.identity().name() != null) {
            candidates.addAll(fuzzy(r.identity().name(), r.identity().dateOfBirth()));
        }
        AssociationEngine.Decision d = AssociationEngine.decide(candidates, conflict, r.restricted(), settings.autoFileThreshold(),
                settings.suggestThreshold());
        if (r.persistCandidates()) {
            for (AssociationEngine.Candidate c : candidates) {
                if (c.employeeId() != null) {
                    observations.candidate(r.observationId(), c.employeeId(), c.method(), c.confidence(),
                            d.associated() && c.employeeId().equals(d.employeeId()) && c.method().equals(d.method()));
                }
            }
        }
        return new Result(d, session, orderRef, caseId, candidates);
    }

    boolean inWindow(DeviceCaptureSessions.SessionBinding s, Instant effectiveAt) {
        if (s.openedAt() == null) {
            return true;
        }
        return !effectiveAt.isBefore(s.openedAt().minusSeconds(60)) && !effectiveAt.isAfter(s.openedAt().plusSeconds(settings.sessionWindowSeconds()));
    }

    List<DeviceWorklistSource.WorklistEntry> worklist(DeviceCatalogueLookup.DeviceInstance instance, Instant at, DeviceCodes.Category category) {
        List<DeviceWorklistSource.WorklistEntry> out = new ArrayList<>();
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);
        for (DeviceWorklistSource s : spis.worklists()) {
            try {
                out.addAll(s.worklist(instance.siteCode(), day, category));
            } catch (RuntimeException e) {
                // a failing worklist source must not stop association; other methods still apply
            }
        }
        return out;
    }

    static boolean sameRef(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return strip(a).equalsIgnoreCase(strip(b));
    }

    static String strip(String ref) {
        String s = ref.strip();
        return s.matches("(?i)(ORD|APT|DA|SCR):.*") ? s.substring(s.indexOf(':') + 1) : s;
    }

    boolean sameEmployee(String messageId, String employeeId) {
        return messageId.equalsIgnoreCase(employeeId)
                || AssociationEngine.normaliseId(messageId, DEFAULT_PREFIXES).equals(AssociationEngine.normaliseId(employeeId, DEFAULT_PREFIXES));
    }

    static List<String> prefixes(DeviceCatalogueLookup.DeviceInstance instance) {
        Object p = instance == null ? null : instance.parameters().get("idPrefixes");
        if (p instanceof List<?> l) {
            return l.stream().map(String::valueOf).toList();
        }
        if (p instanceof String s && !s.isBlank()) {
            return List.of(s.split(","));
        }
        return DEFAULT_PREFIXES;
    }

    List<AssociationEngine.Candidate> vendorId(String raw, List<String> prefixes) {
        List<AssociationEngine.Candidate> out = new ArrayList<>();
        String exact = raw.strip();
        Optional<Employee> e = employees.findById(exact).filter(Employee::isActive);
        if (e.isPresent()) {
            out.add(new AssociationEngine.Candidate(e.get().getEmployeeId(), "VENDOR_ID_MATCH", C097, e.get().getDisplayName()));
            return out;
        }
        String normalised = AssociationEngine.normaliseId(exact, prefixes);
        Set<String> variants = new LinkedHashSet<>(List.of(normalised, exact.toUpperCase(java.util.Locale.ROOT)));
        if (normalised.matches("\\d+")) {
            variants.add("E" + normalised);
        }
        for (String v : variants) {
            Optional<Employee> n = employees.findById(v).filter(Employee::isActive);
            if (n.isPresent()) {
                out.add(new AssociationEngine.Candidate(n.get().getEmployeeId(), "VENDOR_ID_MATCH", C092, n.get().getDisplayName()));
                break;
            }
        }
        return out;
    }

    /**
     * Vendor demographics: the contract scores Jaro-Winkler ≥ 0.92 with an exact date of birth at 0.85. The HR master
     * carries no date of birth, so a name match is scored 0.70 – a suggestion in the unmatched queue, never auto-filed.
     */
    List<AssociationEngine.Candidate> fuzzy(String name, LocalDate dob) {
        List<AssociationEngine.Candidate> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String token : AssociationEngine.letters(name).split(" ")) {
            if (token.length() < 3) {
                continue;
            }
            for (Employee e : employees.search(token, null, PageRequest.of(0, 25))) {
                if (!seen.add(e.getEmployeeId())) {
                    continue;
                }
                double jw = Math.max(AssociationEngine.jaroWinkler(name, e.getDisplayName()), AssociationEngine.jaroWinkler(reverse(name), e.getDisplayName()));
                if (jw >= 0.92) {
                    // master data carries no date of birth: without a verifiable DOB a name match is never better than a suggestion
                    out.add(new AssociationEngine.Candidate(e.getEmployeeId(), "VENDOR_ID_MATCH", C070, e.getDisplayName()));
                }
            }
        }
        return out;
    }

    static String reverse(String name) {
        String[] parts = name.strip().split("[ ,^]+");
        if (parts.length < 2) {
            return name;
        }
        return String.join(" ", List.of(parts).reversed());
    }

    String name(String employeeId) {
        return employees.findById(employeeId).map(Employee::getDisplayName).orElse(null);
    }
}
