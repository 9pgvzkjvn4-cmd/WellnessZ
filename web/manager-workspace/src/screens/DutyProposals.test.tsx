import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import {
  buildDutyDecision,
  DECISION_FIELDS,
  emptyDecisionDraft,
  restrictionText,
  toDutyProposals,
  validateDutyDecision,
} from '../api/fitness';
import { CLINICAL_CONTENT_MESSAGE } from '../api/referrals';
import { submitAction } from '../api/submit';
import { ApiError } from '../auth/AuthClient';
import { decisionIds } from '../components/ids';
import { findAll, findOne } from '../test/tree';
import { ProposalCard, ProposalHistory, type ProposalCardProps } from './DutyProposals';

const raw = {
  id: 'dp1',
  caseId: 'c1',
  caseRef: 'OH-2026-0007',
  employeeId: 'E1004',
  employeeName: 'Rami Ramp',
  jobCode: 'GND_BAGGAGE_SORT',
  jobTitle: 'Baggage sortation (indoor)',
  duties: 'Scanning and tagging bags at the sorter; no loading of ULDs',
  restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', value: 10 }, { code: 'NO_AIRSIDE', label: 'No airside driving' }],
  hoursPerDay: 6,
  startDate: '2026-11-02',
  endDate: '2026-11-30',
  status: 'PROPOSED',
  proposedBy: 'nurse1',
  proposedAt: '2026-10-28T09:00:00Z',
  rationale: 'SECRET-RATIONALE',
  diagnosis: { code: 'M54.5', display: 'SECRET-DX' },
};
const proposal = toDutyProposals([raw])[0]!;

const card = (over: Partial<ProposalCardProps> = {}) => {
  const p: ProposalCardProps = { proposal, draft: emptyDecisionDraft(), errors: {}, busy: false, onDraft: vi.fn(), onDecide: vi.fn(), ...over };
  return p;
};

describe('modified duty proposals (E05-BL-006)', () => {
  it('renders role, duties, hours, dates and the restrictions to respect – no clinical fields', () => {
    const html = renderToStaticMarkup(<ProposalCard {...card()} />);
    for (const s of ['Rami Ramp', 'OH-2026-0007', 'Baggage sortation (indoor)', 'GND_BAGGAGE_SORT', 'Scanning and tagging bags', '>6<', 'No lifting above 10 kg', 'No airside driving']) {
      expect(html).toContain(s);
    }
    expect(html).toMatch(/Nov[^<]*2026/);
    expect(html).not.toMatch(/SECRET|M54/);
    expect(JSON.stringify(proposal)).not.toMatch(/SECRET|M54|nurse1/);
    expect(html).toContain('>Accept</button>');
    expect(html).toContain('>Decline</button>');
    expect(html).toContain('id="dp-dp1-reasonCode"');
    expect(html).toContain('<option value="NO_SUITABLE_WORK">No suitable work available</option>');
  });

  it('fills the catalogue value placeholder', () => {
    expect(restrictionText({ label: 'No lifting above {value} kg', value: '10' })).toBe('No lifting above 10 kg');
    expect(restrictionText({ label: 'Max hours', value: '6' })).toBe('Max hours (6)');
  });

  it('requires a reason code to decline but not to accept', () => {
    expect(validateDutyDecision(false, emptyDecisionDraft())).toEqual({ reasonCode: 'Please choose a reason for declining.' });
    expect(validateDutyDecision(true, emptyDecisionDraft())).toEqual({});
    expect(validateDutyDecision(false, { reasonCode: 'OPERATIONAL', note: '' })).toEqual({});
    expect(validateDutyDecision(true, { reasonCode: '', note: 'x'.repeat(501) })).toHaveProperty('note');
  });

  it('builds the decision body (reasonCode only when declining, optional note)', () => {
    expect(buildDutyDecision(true, { reasonCode: 'COST', note: ' ' })).toEqual({ accept: true });
    expect(buildDutyDecision(false, { reasonCode: 'SAFETY', note: ' Role is airside only ' })).toEqual({ accept: false, reasonCode: 'SAFETY', note: 'Role is airside only' });
  });

  it('wires Accept / Decline and the reason select', () => {
    const onDecide = vi.fn();
    const onDraft = vi.fn();
    const tree = ProposalCard(card({ onDecide, onDraft }));
    const buttons = findAll(tree, (e) => e.type === 'button');
    buttons.find((b) => b.props.children === 'Decline')!.props.onClick();
    expect(onDecide).toHaveBeenLastCalledWith(false);
    buttons.find((b) => b.props.children === 'Accept')!.props.onClick();
    expect(onDecide).toHaveBeenLastCalledWith(true);
    findOne(tree, (e) => e.props.id === decisionIds('dp1').reasonCode).props.onChange({ target: { value: 'OTHER' } });
    expect(onDraft).toHaveBeenLastCalledWith({ reasonCode: 'OTHER', note: '' });
  });

  it('highlights the missing reason after a decline attempt', () => {
    const errors = validateDutyDecision(false, emptyDecisionDraft());
    const html = renderToStaticMarkup(<ProposalCard {...card({ errors })} />);
    expect(html).toMatch(/<select id="dp-dp1-reasonCode"[^>]*aria-invalid="true"/);
    expect(html).toContain('Please choose a reason for declining.');
  });

  it('posts the decision and highlights the note on 422 clinical content', async () => {
    const ok = vi.fn().mockResolvedValue({ id: 'dp1', status: 'DECLINED' });
    const body = buildDutyDecision(false, { reasonCode: 'NO_SUITABLE_WORK', note: 'n' });
    expect(await submitAction({ api: ok }, '/duty-proposals/dp1/decision', body, DECISION_FIELDS, { failed: 'x' })).toMatchObject({ ok: true });
    expect(ok).toHaveBeenCalledWith('/duty-proposals/dp1/decision', { method: 'POST', body: { accept: false, reasonCode: 'NO_SUITABLE_WORK', note: 'n' } });

    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'note', detail: 'ECHO' }));
    const r = await submitAction({ api }, '/duty-proposals/dp1/decision', body, DECISION_FIELDS, { failed: 'The decision was not saved' });
    expect(r).toMatchObject({ ok: false, fieldErrors: { note: CLINICAL_CONTENT_MESSAGE } });
    expect(JSON.stringify(r)).not.toContain('ECHO');
    const html = renderToStaticMarkup(<ProposalCard {...card({ errors: r.ok ? {} : r.fieldErrors, message: r.ok ? undefined : r.message })} />);
    expect(html).toMatch(/<textarea id="dp-dp1-note"[^>]*aria-invalid="true"/);
    expect(html).toContain('role="alert"');
    expect(html).toContain('medical information');
  });

  it('lists decided proposals with outcome and reason', () => {
    const history = toDutyProposals([
      { ...raw, id: 'dp2', status: 'DECLINED', reasonCode: 'NO_SUITABLE_WORK', decidedAt: '2026-10-29T10:00:00Z', note: 'SECRET-NOTE' },
      { ...raw, id: 'dp3', status: 'ESCALATED_TO_HR', reasonCode: 'OPERATIONAL' },
    ]);
    const html = renderToStaticMarkup(<ProposalHistory items={history} />);
    expect(html).toContain('No suitable work available');
    expect(html).toContain('badge-escalated-to-hr');
    expect(html).not.toContain('SECRET');
    expect(renderToStaticMarkup(<ProposalHistory items={[]} />)).toContain('No decided proposals yet');
  });
});
