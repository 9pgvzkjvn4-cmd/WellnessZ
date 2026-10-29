import { useState, type FormEvent } from 'react';
import { seg } from '../../api/links';
import { CASE_TYPES, INCIDENT_CLASSES, RECORDABILITY, SAFETY_CATEGORIES, URGENCIES } from '../../api/types';
import { can } from '../../auth/roles';
import { ConfigTable, type FieldSpec } from '../../components/ConfigTable';
import { humanise, localToIso, toLocalInput } from '../../components/format';
import { ActionFeedback, Dl, Field, PageTitle, SelectOptions } from '../../components/ui';
import { useAction } from '../../components/useAsync';
import type { ScreenProps } from '../types';

const AUDIT_FIELDS: FieldSpec[] = [
  { key: 'version', label: 'Version', kind: 'number', readOnly: true },
  { key: 'updatedBy', label: 'Updated by', kind: 'text', readOnly: true, column: false },
  { key: 'updatedAt', label: 'Updated', kind: 'text', readOnly: true },
];

// ---------------------------------------------------------------- Assignment rules (E02-F03)
const ASSIGNMENT_FIELDS: FieldSpec[] = [
  { key: 'priority', label: 'Priority', kind: 'number', required: true, hint: 'Lowest wins' },
  { key: 'name', label: 'Name', kind: 'text', required: true },
  { key: 'active', label: 'Active', kind: 'bool' },
  { key: 'subsidiaryCode', label: 'Subsidiary', kind: 'text', nullable: true },
  { key: 'siteCode', label: 'Site', kind: 'text', nullable: true },
  { key: 'language', label: 'Language', kind: 'text', nullable: true },
  { key: 'caseType', label: 'Case type', kind: 'select', options: CASE_TYPES, nullable: true },
  { key: 'safetyCategory', label: 'Safety category', kind: 'select', options: SAFETY_CATEGORIES, nullable: true },
  { key: 'specialty', label: 'Specialty', kind: 'text', nullable: true, column: false },
  { key: 'targetQueue', label: 'Target queue', kind: 'text', required: true },
  { key: 'strategy', label: 'Strategy', kind: 'select', options: ['NONE', 'ROUND_ROBIN', 'LEAST_LOADED', 'SPECIFIC_USER'], required: true },
  { key: 'targetUsername', label: 'Target user', kind: 'text', nullable: true, hint: 'Only for SPECIFIC_USER' },
];

function AssignmentDryRun({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const [caseId, setCaseId] = useState('');
  const [res, setRes] = useState<{ ruleId?: number | null; queue?: string; username?: string | null; explanation?: string }>();
  const action = useAction(onSessionExpired);
  async function run(e: FormEvent) {
    e.preventDefault();
    await action.run(async () => setRes(await client.api('/assignment/rules/test', { method: 'POST', body: { caseId: caseId.trim() } })));
  }
  return (
    <div className="tile">
      <h2>Test (dry run)</h2>
      <form className="form inline-form" onSubmit={run}>
        <Field label="Case id">{(id) => <input id={id} required value={caseId} onChange={(e) => setCaseId(e.target.value)} />}</Field>
        <button type="submit" className="secondary" disabled={action.busy}>
          Test rules
        </button>
      </form>
      <ActionFeedback error={action.error} />
      {res && <Dl items={[['Matched rule', res.ruleId ?? 'None'], ['Queue', res.queue], ['Assignee', res.username ?? 'Unassigned'], ['Explanation', res.explanation]]} />}
    </div>
  );
}

export function AssignmentRules({ client, session, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Rules route new cases to a queue and owner (unit, site, language, case type, safety category, specialty).">Assignment rules</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="Assignment rules (lowest priority wins)"
        fields={ASSIGNMENT_FIELDS}
        listPath="/assignment/rules"
        updatePath={(r) => `/assignment/rules/${seg(String(r.id))}`}
        rowKey={(r, i) => String(r.id ?? i)}
        canWrite={can(session.roles, 'adminWrite')}
        mode="perRow"
        newRow={{ priority: 100, name: '', active: true, targetQueue: 'OH_GENERAL', strategy: 'LEAST_LOADED' }}
      />
      {can(session.roles, 'adminWrite') && <AssignmentDryRun client={client} onSessionExpired={onSessionExpired} />}
    </section>
  );
}

// ---------------------------------------------------------------- SLA policies (E02-F04)
const SLA_FIELDS: FieldSpec[] = [
  { key: 'caseType', label: 'Case type', kind: 'select', options: CASE_TYPES, nullable: true },
  { key: 'urgency', label: 'Urgency', kind: 'select', options: URGENCIES, required: true },
  { key: 'milestone', label: 'Milestone', kind: 'select', options: ['TRIAGE', 'FIRST_CONTACT', 'ESCALATION_RESPONSE'], required: true },
  { key: 'targetMinutes', label: 'Target (minutes)', kind: 'number', required: true },
  { key: 'amberPercent', label: 'Amber at (%)', kind: 'number', required: true },
  { key: 'escalateToRole', label: 'Escalate to role', kind: 'text', nullable: true },
];

export function SlaPolicies({ client, session, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Defaults: triage EMERGENCY 15 min, URGENT 60 min, SEMI_URGENT 4 h, ROUTINE 8 working h, ADVICE 2 working days; amber at 75 %.">SLA policies</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="SLA policies"
        fields={SLA_FIELDS}
        listPath="/sla/policies"
        updatePath={(r) => `/sla/policies/${seg(String(r.id))}`}
        rowKey={(r, i) => String(r.id ?? i)}
        canWrite={can(session.roles, 'adminWrite')}
        mode="perRow"
      />
    </section>
  );
}

// ---------------------------------------------------------------- Intake rules (E01-F01)
const INTAKE_FIELDS: FieldSpec[] = [
  { key: 'priority', label: 'Priority', kind: 'number', required: true, hint: 'Lowest wins' },
  { key: 'name', label: 'Name', kind: 'text', required: true },
  { key: 'active', label: 'Active', kind: 'bool' },
  { key: 'incidentClass', label: 'Incident class', kind: 'select', options: INCIDENT_CLASSES, nullable: true },
  { key: 'minSeverity', label: 'Min severity', kind: 'select', options: ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'], nullable: true },
  { key: 'lostTime', label: 'Lost time', kind: 'tristate' },
  { key: 'exposureType', label: 'Exposure type', kind: 'text', nullable: true, column: false },
  { key: 'unitCode', label: 'Unit', kind: 'text', nullable: true },
  { key: 'bodyPart', label: 'Body part', kind: 'text', nullable: true, column: false },
  { key: 'action', label: 'Action', kind: 'select', options: ['AUTO_CREATE', 'SUGGEST', 'IGNORE'], required: true },
  { key: 'caseType', label: 'Case type', kind: 'select', options: CASE_TYPES, nullable: true },
  { key: 'urgency', label: 'Urgency', kind: 'select', options: URGENCIES, nullable: true },
  ...AUDIT_FIELDS,
];

function IntakeDryRun({ client, onSessionExpired }: Pick<ScreenProps, 'client' | 'onSessionExpired'>) {
  const [inc, setInc] = useState({ externalRef: 'TEST-1', occurredAt: toLocalInput(), incidentClass: 'INJURY_MEDICAL_TREATMENT', severity: 'MEDIUM', lostTime: false, exposureType: '', bodyPart: '', unitCode: '', location: '' });
  const [res, setRes] = useState<{ ruleId?: number | null; ruleName?: string | null; action?: string; caseType?: string | null; urgency?: string | null }>();
  const action = useAction(onSessionExpired);
  async function run(e: FormEvent) {
    e.preventDefault();
    const incident = {
      externalRef: inc.externalRef,
      sourceSystem: 'EHS',
      occurredAt: localToIso(inc.occurredAt),
      incidentClass: inc.incidentClass,
      severity: inc.severity || null,
      lostTime: inc.lostTime,
      exposureType: inc.exposureType || null,
      bodyPart: inc.bodyPart || null,
      unitCode: inc.unitCode || null,
      location: inc.location || null,
    };
    await action.run(async () => setRes(await client.api('/intake/rules/test', { method: 'POST', body: { incident } })));
  }
  return (
    <div className="tile">
      <h2>Test with a sample incident (dry run)</h2>
      <form className="form" onSubmit={run}>
        <div className="form-grid">
          <Field label="Incident class">
            {(id) => (
              <select id={id} value={inc.incidentClass} onChange={(e) => setInc({ ...inc, incidentClass: e.target.value })}>
                <SelectOptions values={INCIDENT_CLASSES} />
              </select>
            )}
          </Field>
          <Field label="Severity">
            {(id) => (
              <select id={id} value={inc.severity} onChange={(e) => setInc({ ...inc, severity: e.target.value })}>
                <SelectOptions values={['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']} empty="–" />
              </select>
            )}
          </Field>
          <Field label="Occurred">{(id) => <input id={id} type="datetime-local" value={inc.occurredAt} onChange={(e) => setInc({ ...inc, occurredAt: e.target.value })} />}</Field>
          <Field label="Unit">{(id) => <input id={id} value={inc.unitCode} onChange={(e) => setInc({ ...inc, unitCode: e.target.value.toUpperCase() })} />}</Field>
          <Field label="Body part">{(id) => <input id={id} value={inc.bodyPart} onChange={(e) => setInc({ ...inc, bodyPart: e.target.value.toUpperCase() })} />}</Field>
          <Field label="Exposure type">{(id) => <input id={id} value={inc.exposureType} onChange={(e) => setInc({ ...inc, exposureType: e.target.value.toUpperCase() })} />}</Field>
          <label className="checkbox">
            <input type="checkbox" checked={inc.lostTime} onChange={(e) => setInc({ ...inc, lostTime: e.target.checked })} /> Lost time
          </label>
        </div>
        <button type="submit" className="secondary" disabled={action.busy}>
          Test rules
        </button>
      </form>
      <ActionFeedback error={action.error} />
      {res && (
        <Dl
          items={[
            ['Matched rule', res.ruleName ?? (res.ruleId ? `#${res.ruleId}` : 'No match')],
            ['Action', humanise(res.action ?? 'NO_MATCH')],
            ['Case type', humanise(res.caseType)],
            ['Urgency', humanise(res.urgency)],
          ]}
        />
      )}
    </div>
  );
}

export function IntakeRules({ client, session, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Decide which EHS incidents create a case automatically, are suggested for review, or are ignored. Empty condition = any.">Intake rules</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="Intake rules (lowest priority wins; no match → NO_MATCH in reconciliation)"
        fields={INTAKE_FIELDS}
        listPath="/intake/rules"
        updatePath={(r) => `/intake/rules/${seg(String(r.id))}`}
        rowKey={(r, i) => String(r.id ?? i)}
        canWrite={can(session.roles, 'adminWrite')}
        mode="perRow"
        newRow={{ priority: 100, name: '', active: true, action: 'SUGGEST', lostTime: null }}
      />
      {can(session.roles, 'adminWrite') && <IntakeDryRun client={client} onSessionExpired={onSessionExpired} />}
    </section>
  );
}

// ---------------------------------------------------------------- Notification matrix (E01-F08)
const NOTIFICATION_FIELDS: FieldSpec[] = [
  { key: 'event', label: 'Event', kind: 'select', options: ['CASE_OPENED', 'CASE_ASSIGNED', 'SLA_AT_RISK', 'SLA_BREACHED', 'ESCALATED'], required: true },
  { key: 'source', label: 'Source', kind: 'select', nullable: true, options: ['EHS_INCIDENT', 'SELF_REFERRAL', 'MANAGER_REFERRAL', 'HR_REFERRAL', 'WALK_IN', 'EMERGENCY', 'ABSENCE_TRIGGER', 'SURVEILLANCE_TRIGGER', 'FITNESS_DECLARATION', 'DA_TEST', 'SYSTEM_TRIGGER', 'PARTNER_REFERRAL'] },
  { key: 'caseType', label: 'Case type', kind: 'select', options: CASE_TYPES, nullable: true },
  { key: 'recipient', label: 'Recipient', kind: 'select', required: true, options: ['OH_QUEUE', 'CASE_OWNER', 'HSE_REPORTER', 'LINE_MANAGER', 'CREW_SCHEDULING', 'NURSING_HEAD', 'EMPLOYEE', 'HYGIENE_MANAGER'] },
  { key: 'channel', label: 'Channel', kind: 'select', options: ['EMAIL', 'SMS', 'PUSH'], required: true },
  { key: 'templateKey', label: 'Template', kind: 'text', required: true },
  { key: 'active', label: 'Active', kind: 'bool' },
];

export function NotificationMatrix({ client, session, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Who is told what, on which channel. All notification texts are content-free (case number, unit, urgency, next step, contact).">Notification matrix</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="Notification matrix"
        fields={NOTIFICATION_FIELDS}
        listPath="/intake/notification-matrix"
        rowKey={(r, i) => String(r.id ?? `new-${i}`)}
        canWrite={can(session.roles, 'adminOnlyWrite')}
        mode="wholeList"
        newRow={{ event: 'CASE_OPENED', recipient: 'OH_QUEUE', channel: 'EMAIL', templateKey: '', active: true }}
      />
      {!can(session.roles, 'adminOnlyWrite') && <p className="small muted">Read only – changes require the OH administrator role.</p>}
    </section>
  );
}

// ---------------------------------------------------------------- Recordability mapping (E01-BL-022)
const MAPPING_FIELDS: FieldSpec[] = [
  { key: 'recordability', label: 'Recordability', kind: 'select', options: RECORDABILITY, required: true },
  { key: 'localCategory', label: 'Local legal category', kind: 'text', required: true },
  { key: 'caaReportable', label: 'CAA reportable', kind: 'bool' },
  { key: 'description', label: 'Description', kind: 'text' },
];

export function RecordabilityMapping({ client, session, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Maps the internal recordability classes to the local legal category and civil-aviation reportability.">Recordability mapping</PageTitle>
      <ConfigTable
        client={client}
        onSessionExpired={onSessionExpired}
        caption="Local legal mapping"
        fields={MAPPING_FIELDS}
        listPath="/intake/recordability/mapping"
        rowKey={(r, i) => `${String(r.recordability)}-${i}`}
        canWrite={can(session.roles, 'adminOnlyWrite')}
        mode="wholeList"
        newRow={{ recordability: 'FIRST_AID', localCategory: '', caaReportable: false, description: '' }}
      />
      {!can(session.roles, 'adminOnlyWrite') && <p className="small muted">Read only – changes require the OH administrator role.</p>}
    </section>
  );
}
