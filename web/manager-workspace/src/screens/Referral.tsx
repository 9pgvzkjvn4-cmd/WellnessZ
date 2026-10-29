import { PageTitle } from '../components/ui';

/**
 * Management referral is delivered by business module E01 (coming in a later
 * increment). `POST /cases/self-referral` is EMPLOYEE-only, so this form is a
 * disabled placeholder that shows the intended shape.
 */
export function Referral() {
  return (
    <section>
      <PageTitle>Refer an employee to Occupational Health</PageTitle>
      <div className="banner banner-muted" role="note">
        <strong>Management referral – module E01 (coming in increment)</strong>
        <span className="small">
          Until then, ask the employee to self-refer in the WellnessZ app or contact Occupational Health directly.
        </span>
      </div>
      <form className="form" aria-disabled="true" onSubmit={(e) => e.preventDefault()}>
        <fieldset disabled>
          <legend className="visually-hidden">Management referral (disabled)</legend>
          <label htmlFor="ref-employee">Employee</label>
          <input id="ref-employee" type="text" placeholder="Search employee…" />
          <label htmlFor="ref-reason">Work-related reason for referral</label>
          <select id="ref-reason" defaultValue="">
            <option value="">Select…</option>
            <option>Attendance</option>
            <option>Fitness for task / safety-critical role</option>
            <option>Workplace adjustment</option>
          </select>
          <label htmlFor="ref-questions">Questions for Occupational Health</label>
          <textarea id="ref-questions" rows={4} />
          <label className="checkbox">
            <input type="checkbox" /> I have discussed this referral with the employee
          </label>
          <button type="submit">Submit referral</button>
        </fieldset>
      </form>
    </section>
  );
}
