import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ACK_FIELDS, buildAck, needsManagerAck, toRtwPlans, validateAckComment } from '../api/fitness';
import { CLINICAL_CONTENT_MESSAGE } from '../api/referrals';
import { submitAction } from '../api/submit';
import { ApiError } from '../auth/AuthClient';
import { findOne } from '../test/tree';
import { RtwPlanCard, type RtwPlanCardProps } from './RtwPlans';

const raw = {
  id: 'p1',
  caseId: 'c1',
  caseRef: 'OH-2026-0007',
  employeeId: 'E1004',
  employeeName: 'Rami Ramp',
  status: 'ACTIVE',
  startDate: '2026-11-02',
  goal: 'Back to full ramp duties by week 6',
  phases: [
    { fromWeek: 1, toWeek: 2, hoursPerDay: 4, duties: 'Baggage sortation only', restrictions: [{ code: 'LIFT_MAX_KG', label: 'No lifting above {value} kg', value: 10 }] },
    { fromWeek: 3, toWeek: 6, hoursPerDay: 6, duties: 'Ramp duties without heavy loading', restrictions: [] },
  ],
  reviews: [
    { id: 'r1', dueDate: '2026-11-10', completedAt: '2026-11-10T09:00:00Z', outcome: 'ON_TRACK', note: 'SECRET-REVIEW', completedBy: 'nurse1' },
    { id: 'r2', dueDate: '2026-11-24' },
  ],
  employeeAck: { at: '2026-10-29T08:00:00Z', agree: true, comment: 'SECRET-EMPLOYEE-COMMENT' },
  documentId: 'doc-9',
  nextReviewDate: '2026-11-24',
  createdBy: 'nurse1',
  diagnosis: 'SECRET-DX',
};
const plan = toRtwPlans([raw])[0]!;
const props = (over: Partial<RtwPlanCardProps> = {}): RtwPlanCardProps => ({
  plan,
  canAck: true,
  comment: '',
  errors: {},
  busy: false,
  onComment: vi.fn(),
  onAck: vi.fn(),
  ...over,
});

describe('RTW plans (E05-BL-007)', () => {
  it('renders phases, reviews and the employee acknowledgement status – no clinical fields', () => {
    const html = renderToStaticMarkup(<RtwPlanCard {...props()} />);
    for (const s of ['Rami Ramp', 'Weeks 1–2', 'Weeks 3–6', 'Baggage sortation only', 'No lifting above 10 kg', 'Back to full ramp duties', 'On track', 'open', 'Acknowledged on', ' – agrees', 'Awaiting manager acknowledgement']) {
      expect(html).toContain(s);
    }
    expect(html).toContain('href="/bff/api/documents/doc-9/content"');
    expect(html).not.toMatch(/SECRET|nurse1/);
  });

  it('lets a manager acknowledge an active plan with an optional comment', () => {
    const onAck = vi.fn();
    const onComment = vi.fn();
    const tree = RtwPlanCard(props({ onAck, onComment }));
    findOne(tree, (e) => e.type === 'form').props.onSubmit({ preventDefault: vi.fn() });
    expect(onAck).toHaveBeenCalledOnce();
    findOne(tree, (e) => e.props.id === 'rtw-p1-comment').props.onChange({ target: { value: 'Supported' } });
    expect(onComment).toHaveBeenCalledWith('Supported');
    expect(renderToStaticMarkup(<RtwPlanCard {...props()} />)).toContain('Acknowledge plan');
  });

  it('is read-only for HR', () => {
    const html = renderToStaticMarkup(<RtwPlanCard {...props({ canAck: false })} />);
    expect(html).not.toContain('<form');
    expect(html).not.toContain('Acknowledge plan');
  });

  it('hides the form once acknowledged and shows who acknowledged', () => {
    const [acked] = toRtwPlans([{ ...raw, managerAck: { at: '2026-10-29T10:00:00Z', by: 'manager1', comment: 'Fine' } }]);
    expect(needsManagerAck(acked!)).toBe(false);
    expect(needsManagerAck(toRtwPlans([{ ...raw, status: 'DRAFT' }])[0]!)).toBe(false);
    const html = renderToStaticMarkup(<RtwPlanCard {...props({ plan: acked! })} />);
    expect(html).toContain('Acknowledged by manager1');
    expect(html).not.toContain('<form');
  });

  it('posts the acknowledgement and highlights the comment on 422 clinical content', async () => {
    expect(validateAckComment('x'.repeat(501))).toHaveProperty('comment');
    expect(buildAck('  ')).toEqual({});
    const ok = vi.fn().mockResolvedValue({});
    await submitAction({ api: ok }, '/rtw-plans/p1/manager-acknowledge', buildAck(' Will support '), ACK_FIELDS, { failed: 'x' });
    expect(ok).toHaveBeenCalledWith('/rtw-plans/p1/manager-acknowledge', { method: 'POST', body: { comment: 'Will support' } });

    const api = vi.fn().mockRejectedValue(new ApiError(422, { code: 'clinical_content_not_allowed', field: 'comment' }));
    const r = await submitAction({ api }, '/rtw-plans/p1/manager-acknowledge', buildAck('c'), ACK_FIELDS, { failed: 'The acknowledgement was not saved' });
    expect(r).toMatchObject({ ok: false, fieldErrors: { comment: CLINICAL_CONTENT_MESSAGE } });
    const html = renderToStaticMarkup(<RtwPlanCard {...props({ errors: r.ok ? {} : r.fieldErrors, message: r.ok ? undefined : r.message })} />);
    expect(html).toMatch(/<textarea id="rtw-p1-comment"[^>]*aria-invalid="true"/);
  });
});
