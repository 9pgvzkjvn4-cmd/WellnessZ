import { useState } from 'react';
import type { EmployeeRef } from '../../api/types';
import { EmployeeSearch } from '../../components/EmployeeSearch';
import { PageTitle } from '../../components/ui';
import type { ScreenProps } from '../types';
import { ScanUpload } from './DocumentsPanel';

/** `#/documents/scan` – clinic reception / OH: scan an external report into an employee's record (E03-BL-016). */
export function ScanDocuments({ client, onSessionExpired }: ScreenProps) {
  const [emp, setEmp] = useState<EmployeeRef>();
  const [caseId, setCaseId] = useState('');
  return (
    <section>
      <PageTitle sub="Upload external reports (PDF, JPEG, PNG). The text is extracted and indexed; the document type is classified automatically if you leave it empty.">Scan documents</PageTitle>
      {!emp ? (
        <EmployeeSearch client={client} onSessionExpired={onSessionExpired} onPick={setEmp} label="Employee (name or employee ID)" />
      ) : (
        <>
          <p>
            Employee: <strong>{emp.displayName}</strong> ({emp.employeeId}){' '}
            <button type="button" className="link-button" onClick={() => setEmp(undefined)}>
              Change
            </button>
          </p>
          <div className="field">
            <label htmlFor="scan-case">Case reference id (optional)</label>
            <input id="scan-case" maxLength={80} value={caseId} onChange={(e) => setCaseId(e.target.value)} />
          </div>
          <ScanUpload client={client} onSessionExpired={onSessionExpired} employeeId={emp.employeeId} caseId={caseId.trim() || undefined} onUploaded={() => undefined} />
        </>
      )}
    </section>
  );
}
