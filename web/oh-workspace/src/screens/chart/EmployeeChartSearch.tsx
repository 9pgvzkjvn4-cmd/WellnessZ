import { EmployeeSearch } from '../../components/EmployeeSearch';
import { PageTitle } from '../../components/ui';
import type { ScreenProps } from '../types';

/** `#/employees` – find an employee and open their OH chart. */
export function EmployeeChartSearch({ client, onSessionExpired }: ScreenProps) {
  return (
    <section>
      <PageTitle sub="Search by name or employee ID. Opening a chart is audited; restricted records stay hidden unless you are allowed to see them.">Employee chart</PageTitle>
      <EmployeeSearch client={client} onSessionExpired={onSessionExpired} />
    </section>
  );
}
