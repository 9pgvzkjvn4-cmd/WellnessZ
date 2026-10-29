import { useId, useState } from 'react';
import { CODE_SYSTEMS, recordApi, type CodeSystem, type Coding } from '../api/record';
import type { AuthClient } from '../auth/AuthClient';
import { humanise } from './format';
import { ErrorAlert } from './ui';
import { useAction, useAsync } from './useAsync';

const keyOf = (c: Pick<Coding, 'system' | 'code'>) => `${c.system}|${c.code}`;

/**
 * E03-F03 coding picker: word-prefix search (`/terminology/search`), per-user/role favourites and the user's recent
 * codes. No nested <form>: Enter in the search box runs the search without submitting the surrounding form.
 */
export function TerminologyPicker({
  client,
  onSessionExpired,
  label,
  system: fixedSystem,
  onSelect,
}: {
  client: AuthClient;
  onSessionExpired: () => void;
  label: string;
  system?: CodeSystem;
  onSelect: (c: Coding) => void;
}) {
  const api = recordApi(client);
  const id = useId();
  const [system, setSystem] = useState<CodeSystem>(fixedSystem ?? 'ICD_10');
  const [q, setQ] = useState('');
  const [results, setResults] = useState<Coding[]>();
  const [view, setView] = useState<'search' | 'favourites' | 'recent'>('search');
  const favs = useAsync(() => api.favourites(), onSessionExpired);
  const recent = useAsync(() => (view === 'recent' ? api.recentTerms() : Promise.resolve<Coding[]>([])), onSessionExpired, [view]);
  const action = useAction(onSessionExpired);
  const favKeys = new Set((favs.data ?? []).map(keyOf));

  async function search() {
    if (q.trim().length < 2) return;
    setView('search');
    await action.run(async () => setResults(await api.searchTerms(system, q.trim())));
  }

  async function toggleFavourite(c: Coding) {
    const ok = await action.run(async () => {
      if (favKeys.has(keyOf(c))) await api.removeFavourite(c);
      else await api.addFavourite(c);
    });
    if (ok) favs.reload();
  }

  const list = view === 'favourites' ? (favs.data ?? []).filter((c) => !fixedSystem || c.system === fixedSystem) : view === 'recent' ? (recent.data ?? []) : (results ?? []);

  return (
    <fieldset className="term-picker">
      <legend>{label}</legend>
      <div className="inline-row">
        {!fixedSystem && (
          <div className="field">
            <label htmlFor={`${id}-sys`}>Code system</label>
            <select id={`${id}-sys`} value={system} onChange={(e) => setSystem(e.target.value as CodeSystem)}>
              {CODE_SYSTEMS.map((s) => (
                <option key={s} value={s}>{humanise(s)}</option>
              ))}
            </select>
          </div>
        )}
        <div className="field">
          <label htmlFor={`${id}-q`}>Search {fixedSystem ? humanise(fixedSystem) : 'codes'}</label>
          <input
            id={`${id}-q`}
            type="search"
            value={q}
            minLength={2}
            autoComplete="off"
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                e.preventDefault();
                void search();
              }
            }}
          />
        </div>
        <button type="button" className="secondary" onClick={() => void search()} disabled={action.busy || q.trim().length < 2}>
          Search
        </button>
      </div>
      <div className="mode-switch" role="group" aria-label="Code lists">
        {(['search', 'favourites', 'recent'] as const).map((v) => (
          <button key={v} type="button" className={`secondary small-button${view === v ? ' mode-active' : ''}`} aria-pressed={view === v} onClick={() => setView(v)}>
            {v === 'search' ? 'Results' : humanise(v)}
          </button>
        ))}
      </div>
      {!!action.error && <ErrorAlert error={action.error} />}
      <div aria-live="polite">
        {view === 'search' && results && results.length === 0 && <p className="muted small">No matching codes.</p>}
        {view !== 'search' && list.length === 0 && <p className="muted small">No {view === 'favourites' ? 'favourites' : 'recent codes'} yet.</p>}
      </div>
      {list.length > 0 && (
        <ul className="term-list" aria-label={view === 'search' ? 'Search results' : humanise(view)}>
          {list.map((c) => (
            <li key={keyOf(c)}>
              <button type="button" className="link-button" onClick={() => onSelect(c)}>
                <span className="mono">{c.code}</span> {c.display}
              </button>{' '}
              <span className="small muted">{humanise(c.system)}</span>{' '}
              <button
                type="button"
                className="link-button fav-toggle"
                aria-pressed={favKeys.has(keyOf(c))}
                aria-label={`${favKeys.has(keyOf(c)) ? 'Remove' : 'Add'} ${c.code} ${favKeys.has(keyOf(c)) ? 'from' : 'to'} favourites`}
                onClick={() => void toggleFavourite(c)}
              >
                <span aria-hidden="true">{favKeys.has(keyOf(c)) ? '★' : '☆'}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </fieldset>
  );
}
