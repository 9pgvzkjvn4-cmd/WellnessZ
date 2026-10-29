import { useState, type FormEvent } from 'react';
import {
  CATEGORY_LABELS,
  CONTACT_OPTIONS,
  REFERRAL_CATEGORIES,
  type MyCase,
  type PreferredContact,
  type ReferralCategory,
  type SelfReferralRequest,
} from '../api/types';
import { SessionExpiredError } from '../auth/AuthClient';
import { describeError, humanise } from '../components/format';
import { Alert, PageTitle } from '../components/ui';
import type { ScreenProps } from './types';

const MAX_DESCRIPTION = 2000;

export function SelfReferral({ client, onSessionExpired }: ScreenProps) {
  const [category, setCategory] = useState<ReferralCategory | ''>('');
  const [description, setDescription] = useState('');
  const [preferredContact, setPreferredContact] = useState<PreferredContact>('EMAIL');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();
  const [created, setCreated] = useState<MyCase>();

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!category) {
      setError('Please choose a category.');
      return;
    }
    if (description.trim().length < 10) {
      setError('Please describe your concern in a few words (at least 10 characters).');
      return;
    }
    setSubmitting(true);
    setError(undefined);
    try {
      const body: SelfReferralRequest = { category, description: description.trim(), preferredContact };
      const res = await client.api<MyCase>('/cases/self-referral', { method: 'POST', body });
      setCreated(res ?? { id: '', status: 'SUBMITTED' });
      setDescription('');
      setCategory('');
    } catch (err) {
      if (err instanceof SessionExpiredError) onSessionExpired();
      setError(describeError(err));
    } finally {
      setSubmitting(false);
    }
  }

  if (created) {
    return (
      <section>
        <PageTitle>Referral sent</PageTitle>
        <Alert kind="success">
          Thank you. Occupational Health has received your request
          {created.caseRef ? ` (reference ${created.caseRef})` : ''} and will contact you.
        </Alert>
        <p>
          If you need urgent help, contact your local emergency number or the 24/7 crew support line.
        </p>
        <a className="button" href="#/">
          Back to home
        </a>
      </section>
    );
  }

  return (
    <section>
      <PageTitle>Contact Occupational Health</PageTitle>
      <p className="lead">
        Use this form to refer yourself to Occupational Health. Only OH staff can read your description.
      </p>
      <form onSubmit={onSubmit} noValidate className="form">
        <fieldset>
          <legend>What is it about?</legend>
          {REFERRAL_CATEGORIES.map((c) => (
            <label key={c} className="radio">
              <input
                type="radio"
                name="category"
                value={c}
                checked={category === c}
                onChange={() => setCategory(c)}
                required
              />
              {CATEGORY_LABELS[c]}
            </label>
          ))}
        </fieldset>

        <label htmlFor="description">Describe your concern</label>
        <textarea
          id="description"
          value={description}
          maxLength={MAX_DESCRIPTION}
          rows={6}
          required
          aria-describedby="description-hint"
          autoComplete="off"
          spellCheck
          onChange={(e) => setDescription(e.target.value)}
        />
        <p id="description-hint" className="muted small">
          {description.length}/{MAX_DESCRIPTION} characters. Please do not include other people's health information.
        </p>

        <label htmlFor="preferredContact">How should OH contact you?</label>
        <select
          id="preferredContact"
          value={preferredContact}
          onChange={(e) => setPreferredContact(e.target.value as PreferredContact)}
        >
          {CONTACT_OPTIONS.map((o) => (
            <option key={o} value={o}>
              {o === 'IN_APP' ? 'In the app' : humanise(o)}
            </option>
          ))}
        </select>

        {error && <Alert kind="error">{error}</Alert>}
        <button type="submit" disabled={submitting}>
          {submitting ? 'Sending…' : 'Send to Occupational Health'}
        </button>
      </form>
    </section>
  );
}
