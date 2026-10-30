package com.wellnessz.e10.selection;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.e10.model.DaTest;
import com.wellnessz.e10.model.E10SelectionRepository;
import com.wellnessz.e10.model.E10TestRepository;
import com.wellnessz.e10.model.Selection;
import com.wellnessz.e10.model.SubstanceEnums.Reason;
import com.wellnessz.e10.model.SubstanceEnums.TestStatus;
import com.wellnessz.e10.model.SubstanceEnums.TestType;
import com.wellnessz.e10.model.TestingPolicy;
import com.wellnessz.e10.policy.PolicyService;
import com.wellnessz.e10.support.SubstanceSupport;
import com.wellnessz.e10.support.TestingViews.CoverageRow;
import com.wellnessz.e10.support.TestingViews.Requirement;
import com.wellnessz.e10.support.TestingViews.SelectedEmployee;
import com.wellnessz.e10.support.TestingViews.SelectionView;
import com.wellnessz.e10.support.TestingViews.VerifyView;
import com.wellnessz.e10.testing.TestCore;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Random selection engine with audit (E10-BL-001) and coverage against policy targets (E10-BL-002). One selection per
 * (role group, period, test type); the seed is drawn from {@link SecureRandom}, its SHA-256 commitment and the hash of the
 * pool snapshot are stored, and {@code /verify} recomputes the draw from seed and snapshot.
 */
@Service("e10SelectionService")
public class SelectionService {

    private final E10SelectionRepository selections;
    private final E10TestRepository tests;
    private final PolicyService policies;
    private final TestCore core;
    private final SubstanceSupport support;
    private final AuditService audit;
    private final SecureRandom random = new SecureRandom();

    public SelectionService(E10SelectionRepository selections, E10TestRepository tests, PolicyService policies, TestCore core,
                            SubstanceSupport support, AuditService audit) {
        this.selections = selections;
        this.tests = tests;
        this.policies = policies;
        this.core = core;
        this.support = support;
        this.audit = audit;
    }

    @Transactional
    public SelectionView create(CurrentUser user, String roleGroup, String period, String testType) {
        YearMonth month;
        try {
            month = YearMonth.parse(period);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("invalid_period", "period must be YYYY-MM");
        }
        if (month.isBefore(YearMonth.from(support.today()).minusMonths(1))) {
            throw SubstanceSupport.unprocessable("period_closed", "Selections can only be made for the previous, current or a future month");
        }
        if (selections.findByRoleGroupAndPeriodAndTestType(roleGroup, period, testType).isPresent()) {
            throw ApiException.conflict("selection_exists", "A selection for " + roleGroup + " " + period + " " + testType + " already exists");
        }
        TestingPolicy p = policies.policy(roleGroup);
        BigDecimal rate = TestType.ALCOHOL.name().equals(testType) ? p.getRandomAlcoholRatePct() : p.getRandomDrugRatePct();
        List<String> pool = SelectionEngine.canonical(policies.eligible(p, support.today()));
        int n = SelectionEngine.sampleSize(pool.size(), rate);
        byte[] seed = SelectionEngine.newSeed(random);
        List<String> drawn = SelectionEngine.select(pool, seed, n);

        Selection s = Selection.create();
        s.setRef(support.nextRef("RSL"));
        s.setRoleGroup(roleGroup);
        s.setPeriod(period);
        s.setTestType(testType);
        s.setRatePct(rate);
        s.setPoolSize(pool.size());
        s.setPoolSnapshot(support.json(pool));
        s.setPoolHash(SelectionEngine.poolHash(pool));
        s.setSeedHex(SelectionEngine.hex(seed));
        s.setSeedCommitment(SelectionEngine.commitment(seed));
        s.setSelectedCount(drawn.size());
        s.setSelected(support.json(drawn));
        s.setPolicyVersion(p.getVersion() == null ? 0 : p.getVersion());
        s.setPolicyApproval(p.getApprovalStatus());
        s.setCreatedBy(user.username());
        s.setCreatedAt(support.now());
        try {
            selections.saveAndFlush(s);
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            throw ApiException.conflict("selection_exists", "A selection for this period already exists");
        }
        audit.record("DA_SELECTION_CREATED", "DA_SELECTION", s.getId().toString(), AuditService.Outcome.SUCCESS,
                roleGroup + " " + period + " " + testType + " pool=" + pool.size() + " n=" + drawn.size() + " commitment=" + s.getSeedCommitment());
        return view(s);
    }

    @Transactional(readOnly = true)
    public List<SelectionView> list(String period) {
        List<Selection> list = period == null ? selections.findAllByOrderByCreatedAtDesc() : selections.findByPeriodOrderByRoleGroupAscTestTypeAsc(period);
        audit.record("DA_SELECTIONS_READ", "DA_SELECTION", null, AuditService.Outcome.SUCCESS, "rows=" + list.size());
        return list.stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public VerifyView verify(UUID id) {
        Selection s = load(id);
        List<String> snapshot = support.readIds(s.getPoolSnapshot());
        byte[] seed = SelectionEngine.unhex(s.getSeedHex());
        boolean hashOk = SelectionEngine.poolHash(snapshot).equals(s.getPoolHash()) && snapshot.size() == s.getPoolSize();
        boolean commitmentOk = SelectionEngine.commitment(seed).equals(s.getSeedCommitment());
        List<String> recomputed = SelectionEngine.select(SelectionEngine.canonical(snapshot), seed,
                SelectionEngine.sampleSize(snapshot.size(), s.getRatePct()));
        boolean selectionOk = recomputed.equals(support.readIds(s.getSelected())) && recomputed.size() == s.getSelectedCount();
        boolean reproducible = hashOk && commitmentOk && selectionOk;
        audit.record("DA_SELECTION_VERIFIED", "DA_SELECTION", id.toString(), AuditService.Outcome.SUCCESS, "reproducible=" + reproducible);
        return new VerifyView(s.getId(), s.getRef(), reproducible, hashOk, commitmentOk, selectionOk, s.getPoolSize(), s.getSelectedCount(),
                s.getSeedHex(), s.getSeedCommitment(), s.getPoolHash());
    }

    /** Creates the RANDOM tests (REQUIRED → NOTIFIED) and sends the selection notices with the policy lead time. */
    @Transactional
    public List<Requirement> notify(CurrentUser user, UUID id) {
        Selection s = load(id);
        if (s.getNotifiedAt() != null) {
            throw ApiException.conflict("already_notified", "The selection has already been notified");
        }
        TestingPolicy p = policies.policy(s.getRoleGroup());
        List<String> panels = TestType.ALCOHOL.name().equals(s.getTestType()) ? PolicyService.alcoholPanels(p) : PolicyService.drugPanels(p);
        if (panels.isEmpty()) {
            throw SubstanceSupport.unprocessable("no_panel", "The policy has no " + s.getTestType() + " panel");
        }
        List<Requirement> out = new ArrayList<>();
        for (String employeeId : support.readIds(s.getSelected())) {
            DaTest t = core.create(new TestCore.NewTest(employeeId, s.getRoleGroup(), Reason.RANDOM.name(), panels,
                    "selection:" + s.getId() + ":" + employeeId, user.username()));
            t.setSelectionId(s.getId());
            if (TestStatus.REQUIRED.name().equals(t.getStatus())) {
                core.notifyEmployee(t, p.getLeadTimeMinutes());
            }
            out.add(core.requirement(t, true));
        }
        s.setNotifiedAt(support.now());
        s.setNotifiedBy(user.username());
        audit.record("DA_SELECTION_NOTIFIED", "DA_SELECTION", id.toString(), AuditService.Outcome.SUCCESS, "tests=" + out.size());
        return out;
    }

    @Transactional(readOnly = true)
    public List<CoverageRow> coverage(int year) {
        LocalDate today = support.today();
        int months = year < today.getYear() ? 12 : year > today.getYear() ? 0 : today.getMonthValue();
        List<DaTest> yearTests = tests.findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                LocalDate.of(year, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), LocalDate.of(year + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant());
        List<CoverageRow> out = new ArrayList<>();
        for (TestingPolicy p : policies.byRoleGroup().values().stream().sorted(java.util.Comparator.comparing(TestingPolicy::getRoleGroup)).toList()) {
            int pool = policies.eligible(p, today).size();
            int target = CoverageMath.annualTarget(pool, p.getRandomDrugRatePct(), p.getRandomAlcoholRatePct());
            int completed = (int) yearTests.stream().filter(t -> Reason.RANDOM.name().equals(t.getReason()))
                    .filter(t -> p.getRoleGroup().equals(t.getRoleGroup()))
                    .filter(t -> TestStatus.COMPLETED.contains(TestStatus.valueOf(t.getStatus()))).count();
            out.add(new CoverageRow(p.getRoleGroup(), pool, target, completed, CoverageMath.coveragePct(completed, target),
                    CoverageMath.behindPlan(completed, target, months)));
        }
        audit.record("DA_COVERAGE_READ", "DA_POOL", String.valueOf(year), AuditService.Outcome.SUCCESS, null);
        return out;
    }

    private Selection load(UUID id) {
        return selections.findById(id).orElseThrow(() -> ApiException.notFound("Selection"));
    }

    private SelectionView view(Selection s) {
        List<SelectedEmployee> selected = support.readIds(s.getSelected()).stream().map(e -> new SelectedEmployee(e, support.name(e))).toList();
        return new SelectionView(s.getId(), s.getRef(), s.getRoleGroup(), s.getPeriod(), s.getTestType(), s.getRatePct(), s.getPoolSize(),
                s.getPoolHash(), s.getSeedCommitment(), s.getSelectedCount(), selected, s.getCreatedAt(), s.getCreatedBy(), s.getNotifiedAt(),
                s.getPolicyApproval(), s.getPolicyVersion());
    }
}
