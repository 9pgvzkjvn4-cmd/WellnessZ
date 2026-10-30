import { useState } from 'react';
import { canMarkErgonomicDone, ERGONOMICS_PATH, ergonomicActionPath, toErgonomicActions, type UnitAction } from '../api/unit';
import { SessionExpiredError } from '../auth/AuthClient';
import { describeError } from '../components/format';
import { ActionTable } from '../components/UnitActions';
import { Alert, Loading, PageTitle } from '../components/ui';
import { useAsync } from '../components/useAsync';
import type { ScreenProps } from './types';

/** `#/ergonomics` (MANAGER) – the unit's ergonomic actions with due / overdue; the manager may mark own-unit actions done. */
export function Ergonomics({ client, onSessionExpired }: ScreenProps) {
  const actions = useAsync(async () => toErgonomicActions(await client.api<unknown>(ERGONOMICS_PATH)), onSessionExpired);
  const [busyId, setBusyId] = useState<string>();
  const [message, setMessage] = useState<{ kind: 'error' | 'success'; text: string }>();

  async function markDone(a: UnitAction) {
    setBusyId(a.id);
    setMessage(undefined);
    try {
      // MANAGER may change the status only (contract §6).
      await client.api(ergonomicActionPath(a), { method: 'PUT', body: { status: 'DONE' } });
      setMessage({ kind: 'success', text: `"${a.title}" marked done.` });
      actions.reload();
    } catch (e) {
      if (e instanceof SessionExpiredError) onSessionExpired();
      setMessage({ kind: 'error', text: describeError(e) });
    } finally {
      setBusyId(undefined);
    }
  }

  return (
    <section>
      <PageTitle>Ergonomics</PageTitle>
      <p className="lead">Actions from ergonomic assessments of workstations, manual handling and tasks in your unit. Actions should be closed within 60 days.</p>
      <div aria-live="polite">{message && <Alert kind={message.kind}>{message.text}</Alert>}</div>
      <div className="toolbar">
        <button type="button" className="secondary" onClick={actions.reload} disabled={actions.loading}>
          Refresh
        </button>
      </div>
      {actions.loading && <Loading />}
      {!!actions.error && <Alert kind="error">{describeError(actions.error)}</Alert>}
      {actions.data && <ActionTable caption="Ergonomic actions of your unit" actions={actions.data} busyId={busyId} canMarkDone={canMarkErgonomicDone} onMarkDone={(a) => void markDone(a)} />}
    </section>
  );
}
