// @vitest-environment jsdom
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it } from 'vitest';
import { draftEncounter, injuryTemplate, standards } from '../../testing/fixtures';
import { mockApi, reply, sequence, sessionFor } from '../../testing/mockApi';
import { EncounterEditor } from './EncounterEditor';
import { EncounterList } from './EncounterList';
import { UnsignedNotes } from './UnsignedNotes';

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

const MECHANISMS = [{ code: 'FALL', label: 'Fall' }, { code: 'LIFTING', label: 'Lifting' }];
const common = {
  'GET /encounter-templates': [injuryTemplate],
  'GET /body-map/mechanisms': MECHANISMS,
  'GET /masterdata/fitness-standards': standards,
  'GET /terminology/favourites': [],
  'GET /terminology/search': [{ system: 'ICD_10', code: 'S63.5', display: 'Sprain of wrist' }],
};
const signed = draftEncounter({ status: 'SIGNED', signature: { signedBy: 'tester', signedAt: '2026-10-29T09:00:00Z', sha256: 'ab12cd34ef56' }, diagnoses: [{ system: 'ICD_10', code: 'S63.5', display: 'Sprain of wrist', primary: true }] });

const renderEditor = (api: ReturnType<typeof mockApi>, roles = ['OH_CLINICIAN']) =>
  render(<EncounterEditor client={api.client} session={sessionFor(...roles)} onSessionExpired={() => {}} encounterId="enc1" />);

describe('EncounterEditor', () => {
  it('renders template sections, SOAP, body map, diagnoses and the CDS panel', async () => {
    const api = mockApi({ ...common, 'GET /encounters/enc1': draftEncounter() });
    renderEditor(api);
    expect(await screen.findByRole('group', { name: 'History' })).toBeTruthy();
    expect(screen.getByLabelText(/How did it happen/)).toBeTruthy();
    expect(screen.getByLabelText('Pain score (0-10)')).toBeTruthy();
    expect(screen.getByRole('group', { name: 'SOAP note' })).toBeTruthy();
    expect(screen.getByRole('group', { name: 'Body map' })).toBeTruthy();
    expect(within(screen.getByRole('list', { name: 'Clinical decision support' })).getByText(/Audiometry due/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Sign note' })).toBeTruthy();
  });

  it('blocks signing without mandatory fields / diagnosis, then saves and signs and shows the signature hash', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...common,
      'GET /encounters/enc1': sequence(draftEncounter(), signed),
      'PUT /encounters/enc1': draftEncounter(),
      'POST /encounters/enc1/sign': signed,
    });
    renderEditor(api);
    await user.click(await screen.findByRole('button', { name: 'Sign note' }));
    const summary = screen.getByRole('alert', { name: 'The note cannot be signed yet' });
    expect(within(summary).getAllByRole('link').map((a) => a.textContent)).toEqual(['History: “How did it happen” is required.', 'Add at least one coded diagnosis before signing.']);
    expect(api.find('POST', '/encounters/enc1/sign')).toHaveLength(0);

    await user.type(screen.getByLabelText(/How did it happen/), 'Lifted a bag');
    await user.type(screen.getByLabelText('Subjective'), 'Wrist pain');
    // body map: toggle a region on the back view and set the mechanism
    await user.click(screen.getByRole('button', { name: /^Back/ }));
    await user.click(within(screen.getByRole('group', { name: 'Back regions' })).getByRole('button', { name: 'Lower back' }));
    await user.selectOptions(screen.getByLabelText('Mechanism for Lower back (Back)'), 'LIFTING');
    // diagnosis from the picker → becomes primary
    const dx = screen.getByRole('group', { name: 'Add diagnosis' });
    await user.type(within(dx).getByLabelText('Search codes'), 'wrist{Enter}');
    await user.click(await within(dx).findByRole('button', { name: /S63.5 Sprain of wrist/ }));
    expect((screen.getByRole('radio', { name: 'Primary' }) as HTMLInputElement).checked).toBe(true);

    await user.click(screen.getByRole('button', { name: 'Sign note' }));
    await waitFor(() => expect(api.find('POST', '/encounters/enc1/sign')).toHaveLength(1));
    expect(api.find('PUT', '/encounters/enc1')[0]?.body).toEqual({
      answers: { mechanismText: 'Lifted a bag' },
      soap: { subjective: 'Wrist pain' },
      bodyMap: [{ side: 'BACK', region: 'LOWER_BACK', mechanism: 'LIFTING' }],
      diagnoses: [{ system: 'ICD_10', code: 'S63.5', display: 'Sprain of wrist', primary: true }],
      fitnessStandardCode: null,
    });
    expect(await screen.findByText(/Encounter signed. Signature hash ab12cd34ef56/)).toBeTruthy();
    const sig = await screen.findByLabelText('Signature');
    expect(sig.textContent).toMatch(/SHA-256: ab12cd34ef56/);
    // signed: locked, addenda available
    expect(screen.queryByRole('button', { name: 'Sign note' })).toBeNull();
    expect((screen.getByLabelText('Subjective') as HTMLTextAreaElement).disabled).toBe(true);
    expect(screen.getByRole('heading', { name: 'Addenda' })).toBeTruthy();
  });

  it('adds a signed addendum to a signed note', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...common, 'GET /encounters/enc1': signed, 'POST /encounters/enc1/addenda': signed });
    renderEditor(api);
    await user.type(await screen.findByLabelText('New addendum'), 'X-ray reviewed: no fracture');
    await user.click(screen.getByRole('button', { name: 'Sign and add addendum' }));
    await waitFor(() => expect(api.find('POST', '/encounters/enc1/addenda')[0]?.body).toEqual({ text: 'X-ray reviewed: no fracture' }));
  });

  it('marks an encounter as entered in error with a reason (dialog)', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...common, 'GET /encounters/enc1': signed, 'POST /encounters/enc1/entered-in-error': signed });
    renderEditor(api);
    await user.click(await screen.findByRole('button', { name: 'Mark as entered in error' }));
    const dialog = screen.getByRole('dialog', { name: 'Mark encounter as entered in error' });
    await user.type(within(dialog).getByLabelText('Reason'), 'Wrong employee');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));
    await waitFor(() => expect(api.find('POST', '/encounters/enc1/entered-in-error')[0]?.body).toEqual({ reason: 'Wrong employee' }));
  });

  it('photo upload requires the consent checkbox and sends multipart with consent=true', async () => {
    const user = userEvent.setup();
    const api = mockApi({ ...common, 'GET /encounters/enc1': draftEncounter(), 'POST /encounters/enc1/photos': { documentId: 'd9' } });
    renderEditor(api);
    const file = new File(['img'], 'wound.jpg', { type: 'image/jpeg' });
    await user.upload(await screen.findByLabelText(/^Photo \(JPEG or PNG/), file);
    await user.click(screen.getByRole('button', { name: 'Upload photo' }));
    expect(screen.getByRole('alert', { name: 'There is a problem' }).textContent).toMatch(/consented/);
    expect(api.find('POST', '/encounters/enc1/photos')).toHaveLength(0);
    await user.click(screen.getByRole('checkbox', { name: /employee consented to this photo/ }));
    await user.type(screen.getByLabelText('Body region'), 'Left forearm');
    await user.click(screen.getByRole('button', { name: 'Upload photo' }));
    await waitFor(() => expect(api.find('POST', '/encounters/enc1/photos')).toHaveLength(1));
    const fd = api.find('POST', '/encounters/enc1/photos')[0]?.body as FormData;
    expect(fd instanceof FormData).toBe(true);
    expect(fd.get('consent')).toBe('true');
    expect(fd.get('bodyRegion')).toBe('Left forearm');
    expect((fd.get('file') as File).name).toBe('wound.jpg');
  });

  it('AI assistant: consent first, then "AI assistant not configured" on 503 ai_not_configured', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...common,
      'GET /encounters/enc1': draftEncounter(),
      'POST /encounters/enc1/ai-consent': {},
      'POST /encounters/enc1/ai-draft': reply(503, { status: 503, code: 'ai_not_configured', detail: 'No provider' }),
    });
    renderEditor(api);
    const panel = await screen.findByRole('region', { name: 'AI documentation assistant' });
    expect((within(panel).getByRole('button', { name: 'Record consent' }) as HTMLButtonElement).disabled).toBe(true);
    await user.click(within(panel).getByRole('checkbox', { name: /verbal consent/ }));
    await user.click(within(panel).getByRole('button', { name: 'Record consent' }));
    await waitFor(() => expect(api.find('POST', '/encounters/enc1/ai-consent')[0]?.body).toMatchObject({ verbal: true }));
    await user.type(await within(panel).findByLabelText(/transcript/), 'Patient says wrist hurts');
    await user.click(within(panel).getByRole('button', { name: 'Request draft' }));
    expect(await within(panel).findByText('AI assistant not configured.')).toBeTruthy();
    expect(within(panel).queryByRole('button', { name: 'Request draft' })).toBeNull();
  });

  it('AI draft is inserted only on request and flags the note AI-assisted on save', async () => {
    const user = userEvent.setup();
    const api = mockApi({
      ...common,
      'GET /encounters/enc1': draftEncounter(),
      'POST /encounters/enc1/ai-consent': {},
      'POST /encounters/enc1/ai-draft': { draft: { soap: { subjective: 'Wrist pain after lifting', plan: 'Rest' } }, provider: 'local', generatedAt: '2026-10-29T09:00:00Z' },
      'PUT /encounters/enc1': draftEncounter({ aiAssisted: true }),
    });
    renderEditor(api);
    const panel = await screen.findByRole('region', { name: 'AI documentation assistant' });
    await user.click(within(panel).getByRole('checkbox', { name: /verbal consent/ }));
    await user.click(within(panel).getByRole('button', { name: 'Record consent' }));
    await user.type(await within(panel).findByLabelText(/transcript/), 'transcript');
    await user.click(within(panel).getByRole('button', { name: 'Request draft' }));
    expect(await within(panel).findByText('AI draft – not saved')).toBeTruthy();
    expect((screen.getByLabelText('Subjective') as HTMLTextAreaElement).value).toBe('');
    await user.click(within(panel).getByRole('button', { name: 'Insert draft into SOAP note' }));
    expect((screen.getByLabelText('Subjective') as HTMLTextAreaElement).value).toBe('Wrist pain after lifting');
    expect(screen.getAllByText('AI-assisted').length).toBeGreaterThan(0);
    await user.click(screen.getByRole('button', { name: 'Save draft' }));
    await waitFor(() => expect(api.find('PUT', '/encounters/enc1')[0]?.body).toMatchObject({ aiAssisted: true, soap: { subjective: 'Wrist pain after lifting', plan: 'Rest' } }));
  });

  it('read-only for a non-author', async () => {
    const api = mockApi({ ...common, 'GET /encounters/enc1': draftEncounter({ author: 'someone-else' }) });
    renderEditor(api);
    expect(await screen.findByText('Only the author can edit this draft.')).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Sign note' })).toBeNull();
  });
});

describe('EncounterList / NewEncounter / UnsignedNotes', () => {
  it('creates an encounter of the chosen type for the case and opens the editor', async () => {
    const user = userEvent.setup();
    const mh = { ...injuryTemplate, type: 'MENTAL_HEALTH' as const, title: 'Mental-health consult', version: 1 };
    const api = mockApi({
      'GET /employees/E1004/encounters': [{ id: 'e0', employeeId: 'E1004', caseId: 'other', type: 'ILLNESS', status: 'SIGNED' }, { id: 'e1', employeeId: 'E1004', caseId: 'c1', type: 'INJURY', status: 'DRAFT', title: 'Wrist', aiAssisted: true }],
      'GET /encounter-templates': [injuryTemplate, mh],
      'POST /employees/E1004/encounters': draftEncounter({ id: 'new1' }),
    });
    render(<EncounterList client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} employeeId="E1004" caseId="c1" canWrite refreshSummary={() => {}} />);
    expect(await screen.findByRole('link', { name: 'Wrist' })).toBeTruthy();
    expect(screen.queryByRole('link', { name: 'Illness' })).toBeNull(); // other case filtered out
    expect(screen.getByText('AI-assisted')).toBeTruthy();
    const select = screen.getByLabelText(/Encounter type/);
    await waitFor(() => expect(within(select).getAllByRole('option')).toHaveLength(2)); // MENTAL_HEALTH hidden for non-psych
    await user.selectOptions(select, 'INJURY');
    await user.click(screen.getByRole('button', { name: 'Start encounter' }));
    await waitFor(() => expect(window.location.hash).toBe('#/encounters/new1'));
    expect(api.find('POST', '/employees/E1004/encounters')[0]?.body).toEqual({ type: 'INJURY', caseId: 'c1', templateVersion: 2 });
  });

  it('PSYCH_CLINICIAN may start a mental-health encounter', async () => {
    const mh = { ...injuryTemplate, type: 'MENTAL_HEALTH' as const, title: 'Mental-health consult', version: 1 };
    const api = mockApi({ 'GET /employees/E1004/encounters': [], 'GET /encounter-templates': [injuryTemplate, mh] });
    render(<EncounterList client={api.client} session={sessionFor('PSYCH_CLINICIAN')} onSessionExpired={() => {}} employeeId="E1004" canWrite refreshSummary={() => {}} />);
    expect(await screen.findByRole('option', { name: /Mental-health consult/ })).toBeTruthy();
  });

  it('unsigned notes: mine by default, Nursing Head can switch to all', async () => {
    const user = userEvent.setup();
    const api = mockApi({ 'GET /encounters/unsigned': [{ id: 'e1', employeeId: 'E1004', type: 'INJURY', status: 'DRAFT', title: 'Wrist', author: 'nurse1', createdAt: '2026-10-29T06:00:00Z' }] });
    render(<UnsignedNotes client={api.client} session={sessionFor('NURSING_HEAD')} onSessionExpired={() => {}} />);
    expect(await screen.findByRole('link', { name: 'Wrist' })).toBeTruthy();
    expect(api.calls[0]?.path).toBe('/encounters/unsigned?mine=true');
    await user.click(screen.getByRole('button', { name: 'All clinicians' }));
    await waitFor(() => expect(api.calls.some((c) => c.path === '/encounters/unsigned?mine=false')).toBe(true));
  });

  it('unsigned notes: a nurse has no "all clinicians" switch', async () => {
    const api = mockApi({ 'GET /encounters/unsigned': [] });
    render(<UnsignedNotes client={api.client} session={sessionFor('OH_CLINICIAN')} onSessionExpired={() => {}} />);
    expect(await screen.findByText(/No unsigned notes/)).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'All clinicians' })).toBeNull();
  });
});
