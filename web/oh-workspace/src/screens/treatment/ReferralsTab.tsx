import { useState } from 'react';
import { treatmentApi } from '../../api/treatment';
import { can } from '../../auth/roles';
import { Alert, Empty, ErrorAlert, Loading } from '../../components/ui';
import { useAsync } from '../../components/useAsync';
import type { CaseTabProps } from '../case/caseTabProps';
import { ReferralDetailPanel, ReferralTable } from './Referrals';
import { ReferralWizard } from './ReferralWizard';

/** `#/cases/{id}/referrals` – the case's external referrals, the referral wizard and the detail / rating panel. */
export function ReferralsTab(props: CaseTabProps) {
  const { client, session, onSessionExpired, caseId } = props;
  const list = useAsync(() => treatmentApi(client).caseReferrals(caseId), onSessionExpired);
  const [wizard, setWizard] = useState(false);
  const [open, setOpen] = useState<string>();
  const [message, setMessage] = useState<string>();
  const rows = list.data ?? [];
  const canWrite = can(session.roles, 'referralWrite');

  return (
    <div>
      <h2 className="visually-hidden">Referrals</h2>
      {message && <Alert kind="success">{message}</Alert>}
      {wizard ? (
        <ReferralWizard
          props={props}
          caseId={caseId}
          onDone={(m) => {
            setWizard(false);
            setMessage(m);
            list.reload();
          }}
        />
      ) : (
        canWrite && <button type="button" onClick={() => { setMessage(undefined); setWizard(true); }}>New referral</button>
      )}
      {list.loading && !list.data && <Loading label="Loading referrals…" />}
      {!!list.error && <ErrorAlert error={list.error} />}
      {list.data && rows.length === 0 && <Empty>No referrals for this case.</Empty>}
      {rows.length > 0 && (
        <>
          <ReferralTable rows={rows} caption="Referrals for this case" showCase={false} />
          <div className="inline-form">
            {rows.map((r) => (
              <button key={r.id} type="button" className="secondary small-button" aria-pressed={open === r.id} onClick={() => setOpen(open === r.id ? undefined : r.id)}>
                {open === r.id ? `Hide ${r.ref}` : `Show ${r.ref}`}
              </button>
            ))}
          </div>
        </>
      )}
      {open && <ReferralDetailPanel key={open} id={open} props={props} onChanged={list.reload} />}
    </div>
  );
}
