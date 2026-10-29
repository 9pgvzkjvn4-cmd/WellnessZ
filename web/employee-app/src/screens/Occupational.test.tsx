import { renderToStaticMarkup } from 'react-dom/server';
import type { ReactElement } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { toAdviceReport, toAppointment, type MyAppointment } from '../api/occupational';
import { ohStrings } from '../i18n/occupational';
import { SESSION, stubClient } from '../test/client';
import { findAll, findOne, textOf } from '../test/tree';
import { AdviceReportCard, AdviceReports, type AdviceReportCardProps } from './AdviceReports';
import { MyAbsence, CertificateForm, CertificateList, type CertificateFormProps } from './MyAbsence';
import { AppointmentCard, BookingForm, MyAppointments, SlotPicker, type AppointmentCardProps, type BookingFormProps } from './MyAppointments';
import { ImmunisationTable, MySurveillance, PregnancyForm, ProgrammeList } from './MySurveillance';
import { BookExamForm, ConsentForm, PreEmployment, QuestionnaireForm } from './PreEmployment';

const t = ohStrings('en');
const NOW = new Date('2026-10-29T09:00:00Z');
const appt = toAppointment({ id: 'a1', appointmentType: 'VACCINATION', appointmentTypeName: 'Vaccination', siteCode: 'HQ-MC', startsAt: '2026-10-29T09:30:00Z', endsAt: '2026-10-29T09:40:00Z', status: 'BOOKED' })!;

function cardProps(over: Partial<AppointmentCardProps> = {}): AppointmentCardProps {
  return {
    t, lang: 'en', a: appt, now: NOW, busy: false, cancelling: false, cancelReason: '', canReschedule: true,
    onCheckIn: vi.fn(), onJoin: vi.fn(), onReschedule: vi.fn(), onCancel: vi.fn(), onCancelReason: vi.fn(), onConfirmCancel: vi.fn(), onAbortCancel: vi.fn(),
    ...over,
  };
}
const button = (tree: ReactElement, label: string) => findOne(tree, (e) => e.type === 'button' && textOf(e.props.children).trim() === label);

describe('appointments view', () => {
  it('offers check-in in the 60-minute window, change and cancel', () => {
    const p = cardProps();
    const tree = AppointmentCard(p);
    button(tree, t.apptCheckIn).props.onClick();
    expect(p.onCheckIn).toHaveBeenCalledWith(appt);
    button(tree, t.apptReschedule).props.onClick();
    button(tree, t.apptCancel).props.onClick();
    expect(p.onReschedule).toHaveBeenCalled();
    expect(p.onCancel).toHaveBeenCalled();
    expect(findAll(AppointmentCard(cardProps({ now: new Date('2026-10-28T09:00:00Z') })), (e) => e.type === 'button' && textOf(e.props.children) === t.apptCheckIn)).toHaveLength(0);
  });

  it('cancel asks for confirmation with an optional reason; candidates cannot reschedule', () => {
    const p = cardProps({ cancelling: true, canReschedule: false });
    const html = renderToStaticMarkup(<AppointmentCard {...p} />);
    expect(html).toContain(t.apptCancelTitle);
    expect(html).toContain('role="alertdialog"');
    expect(html).not.toContain(t.apptReschedule);
  });

  it('telehealth: join disabled until 15 minutes before, then a safe link', () => {
    const tele: MyAppointment = { ...appt, telehealth: true };
    const before = AppointmentCard(cardProps({ a: tele }));
    expect(button(before, t.apptJoin).props.disabled).toBe(true);
    const html = renderToStaticMarkup(<AppointmentCard {...cardProps({ a: tele, joinUrl: 'https://video.example/r/1', now: new Date('2026-10-29T09:20:00Z') })} />);
    expect(html).toContain('href="https://video.example/r/1"');
    expect(html).toContain('rel="noopener noreferrer"');
    expect(html).toContain(t.apptVideo);
  });

  it('shows the ticket after check-in', () => {
    expect(renderToStaticMarkup(<AppointmentCard {...cardProps({ a: { ...appt, status: 'CHECKED_IN', ticket: 'A012' } })} />)).toContain('Your ticket: A012');
  });

  it('booking blocks a type with an open booking and offers slots grouped by day', () => {
    const base: BookingFormProps = {
      t, lang: 'en', types: [{ code: 'VACCINATION', name: 'Vaccination', telehealth: false }], sites: [{ code: 'HQ-MC', name: 'HQ Medical Centre' }], type: 'VACCINATION', site: 'HQ-MC',
      blockedTypes: new Set(['VACCINATION']), busy: false, onType: vi.fn(), onSite: vi.fn(), onFind: vi.fn(), onChoose: vi.fn(), onBook: vi.fn(),
    };
    const blocked = renderToStaticMarkup(<BookingForm {...base} slots={[{ startsAt: '2026-10-31T10:00:00Z' }]} />);
    expect(blocked).toContain(t.apptAlreadyBooked);
    expect(blocked).not.toContain('name="slot"');
    const p = { ...base, blockedTypes: new Set<string>(), slots: [{ startsAt: '2026-10-31T10:00:00Z' }, { startsAt: '2026-11-02T10:00:00Z' }] };
    const tree = BookingForm(p);
    button(tree, t.apptFindSlots).props.onClick();
    expect(p.onFind).toHaveBeenCalled();
    findAll(tree, (e) => e.type === 'input' && e.props.name === 'slot')[1]!.props.onChange();
    expect(p.onChoose).toHaveBeenCalledWith('2026-11-02T10:00:00Z');
    expect(button(tree, t.apptBook).props.disabled).toBe(true); // nothing chosen yet
    expect(renderToStaticMarkup(<SlotPicker t={t} lang="en" slots={[]} busy={false} submitLabel="x" onChoose={vi.fn()} onSubmit={vi.fn()} />)).toContain(t.apptNoSlots);
    expect(renderToStaticMarkup(<BookingForm {...base} types={[]} />)).toContain(t.apptNoTypes);
  });
});

describe('absence view', () => {
  const props = (over: Partial<CertificateFormProps> = {}): CertificateFormProps => ({
    t, state: { fromDate: '', toDate: '', episodeId: '' }, errors: {}, episodes: [], busy: false, onFile: vi.fn(), onChange: vi.fn(), onSubmit: vi.fn(), ...over,
  });

  it('offers camera capture and file choice with the allowed types', () => {
    const html = renderToStaticMarkup(<CertificateForm {...props()} />);
    expect(html).toContain('capture="environment"');
    expect(html).toContain('accept="application/pdf,image/jpeg,image/png');
    expect(html).toContain(t.absTakePhoto);
    expect(html).toContain(t.absChooseFile);
  });

  it('shows localised field errors linked to the inputs', () => {
    const html = renderToStaticMarkup(<CertificateForm {...props({ errors: { file: 'fileSize', toDate: 'dateOrder' } })} />);
    expect(html).toContain(t.fieldErrors.fileSize);
    expect(html).toContain(t.fieldErrors.dateOrder);
    expect(html).toMatch(/id="cert-to"[^>]*aria-invalid="true"/);
    const fr = renderToStaticMarkup(<CertificateForm {...props({ t: ohStrings('fr'), errors: { fromDate: 'required' } })} />);
    expect(fr).toContain('Veuillez remplir ce champ.');
  });

  it('dates go through onChange; certificate list shows validity only', () => {
    const p = props();
    findOne(CertificateForm(p), (e) => e.type === 'input' && e.props.id === 'cert-from').props.onChange({ target: { value: '2026-10-27' } });
    expect(p.onChange).toHaveBeenCalledWith({ fromDate: '2026-10-27' });
    const html = renderToStaticMarkup(<CertificateList t={t} lang="en" certificates={[{ id: 'c1', fromDate: '2026-10-27', toDate: '2026-10-31', status: 'INVALID', receivedAt: '2026-10-28T08:00:00Z' }]} />);
    expect(html).toContain('Not accepted');
    expect(html).toContain(t.certStatusHint.INVALID);
  });
});

describe('advice report review', () => {
  const r = toAdviceReport({
    id: 'r1', requestRef: 'ADV-2026-000001', requestedByRole: 'HR', status: 'AWAITING_EMPLOYEE',
    report: { fitness: 'FIT_WITH_ADJUSTMENTS', adjustments: ['Phased return'], restrictions: [], prognosis: 'RTW_WITHIN_4_WEEKS', answers: { EXPECTED_RTW: 'About 4 weeks' } },
  })!;
  const props = (over: Partial<AdviceReportCardProps> = {}): AdviceReportCardProps => ({
    t, lang: 'en', r, questions: { EXPECTED_RTW: 'When is the employee expected to return?' }, form: { comment: '', declineReason: '' }, errors: {}, confirming: false, busy: false,
    onChange: vi.fn(), onContinue: vi.fn(), onConfirm: vi.fn(), onCancel: vi.fn(), ...over,
  });

  it('shows the report and explains exactly what HR will and will not see', () => {
    const html = renderToStaticMarkup(<AdviceReportCard {...props()} />);
    for (const s of ['Fit with adjustments', 'Phased return', 'Return to work expected within 4 weeks', 'When is the employee expected to return?', 'About 4 weeks', 'What your HR will see', t.advNeverSees, 'Waiting for your decision', 'ADV-2026-000001'])
      expect(html).toContain(s);
  });

  it('decline needs a reason (hint seen by OH only), then a confirmation step', () => {
    const p = props();
    findAll(AdviceReportCard(p), (e) => e.type === 'input' && e.props.type === 'radio')[1]!.props.onChange();
    expect(p.onChange).toHaveBeenCalledWith({ decision: 'DECLINE' });
    const declining = renderToStaticMarkup(<AdviceReportCard {...props({ form: { decision: 'DECLINE', comment: '', declineReason: '' }, errors: { declineReason: 'required' } })} />);
    expect(declining).toContain(t.advDeclineReason);
    expect(declining).toContain(t.fieldErrors.required);
    expect(declining).not.toContain(t.advComment);
    const confirm = renderToStaticMarkup(<AdviceReportCard {...props({ form: { decision: 'CONSENT', comment: 'Thanks', declineReason: '' }, confirming: true })} />);
    expect(confirm).toContain(t.advConfirmConsentTitle);
    expect(confirm).toContain('will be sent to your HR');
  });

  it('released and withheld reports have no decision controls', () => {
    const released = renderToStaticMarkup(<AdviceReportCard {...props({ r: { ...r, status: 'WITHHELD' } })} />);
    expect(released).toContain(t.advWithheld);
    expect(released).not.toContain('type="radio"');
  });
});

describe('surveillance view', () => {
  it('programmes with due dates and booked appointment, immunisations, crew pregnancy form', () => {
    const html = renderToStaticMarkup(<ProgrammeList t={t} lang="en" programmes={[{ title: 'Respiratory', status: 'OVERDUE', nextDueOn: '2026-10-01' }, { title: 'Hearing', status: 'SCHEDULED', nextDueOn: '2026-11-15', appointment: { startsAt: '2026-11-10T09:00:00Z', siteCode: 'LHR-AIRSIDE' } }]} />);
    expect(html).toContain('Overdue');
    expect(html).toContain(t.survBookHint);
    expect(html).toContain('LHR-AIRSIDE');
    const imm = renderToStaticMarkup(<ImmunisationTable t={t} lang="en" items={[{ vaccineName: 'Hepatitis B', status: 'TITRE_NEEDED', dosesGiven: 3, dosesRequired: 3 }]} />);
    expect(imm).toContain('Blood test needed');
    expect(imm).toContain('3 of 3 doses');
    const preg = renderToStaticMarkup(<PregnancyForm t={t} open declaredOn="2026-10-30" error="dateFuture" busy={false} done={false} max="2026-10-29" onOpen={vi.fn()} onChange={vi.fn()} onSubmit={vi.fn()} />);
    expect(preg).toContain(t.pregWho);
    expect(preg).toContain(t.fieldErrors.dateFuture);
  });
});

describe('pre-employment view', () => {
  it('renders YES/NO, DATE and TEXT questions with errors', () => {
    const onAnswer = vi.fn();
    const p = { t, questions: [{ code: 'Q1', text: 'Back problems?', type: 'YES_NO' as const, required: true }, { code: 'Q2', text: 'Last tetanus', type: 'DATE' as const, required: false }, { code: 'Q3', text: 'Other', type: 'TEXT' as const, required: false }], answers: {}, errors: { Q1: 'required' as const }, busy: false, onAnswer, onSubmit: vi.fn() };
    const html = renderToStaticMarkup(<QuestionnaireForm {...p} />);
    expect(html).toContain('type="date"');
    expect(html).toContain('<textarea');
    expect(html).toContain(t.fieldErrors.required);
    findAll(QuestionnaireForm(p), (e) => e.type === 'input' && e.props.name === 'q-Q1')[0]!.props.onChange();
    expect(onAnswer).toHaveBeenCalledWith('Q1', 'YES');
  });

  it('consent lists both purposes; booking waits for questionnaire + consent', () => {
    const consent = ConsentForm({ t, checked: false, busy: false, onCheck: vi.fn(), onSubmit: vi.fn() });
    expect(button(consent, t.preConsentSubmit).props.disabled).toBe(true);
    expect(renderToStaticMarkup(consent)).toContain('never your answers or results');
    expect(renderToStaticMarkup(<BookExamForm t={t} enabled={false} sites={[]} site="" earliest="" busy={false} onSite={vi.fn()} onEarliest={vi.fn()} onSubmit={vi.fn()} />)).toContain(t.preBookNeeds);
  });
});

describe('containers render (SSR, Arabic RTL)', () => {
  const p = { client: stubClient(), session: SESSION, onSessionExpired: () => {}, lang: 'ar' };
  it.each([
    ['MyAppointments', <MyAppointments {...p} />],
    ['MyAbsence', <MyAbsence {...p} />],
    ['MySurveillance', <MySurveillance {...p} />],
    ['PreEmployment', <PreEmployment {...p} session={{ ...SESSION, roles: ['CANDIDATE'] }} />],
    ['AdviceReports', <AdviceReports client={p.client} onSessionExpired={p.onSessionExpired} lang="ar" />],
  ] as [string, ReactElement][])('%s', (_n, el) => {
    const html = renderToStaticMarkup(el);
    expect(html).toContain('dir="rtl"');
    expect(html).toContain('lang="ar"');
  });
});
