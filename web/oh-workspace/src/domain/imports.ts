import type { ConceptImport } from '../api/record';
import type { JobDemandProfile } from '../api/platform';

export type ParseResult<T> = { ok: true; items: T[] } | { ok: false; error: string };

const isObj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/**
 * Terminology release file → concepts. Accepts a JSON array of `{system, code, display, active}` or CSV with the header
 * `system,code,display,active` (display may be quoted).
 */
export function parseConcepts(text: string): ParseResult<ConceptImport> {
  const t = text.trim();
  if (!t) return { ok: false, error: 'The file is empty.' };
  let rows: unknown[];
  if (t.startsWith('[')) {
    try {
      rows = JSON.parse(t) as unknown[];
    } catch {
      return { ok: false, error: 'The JSON could not be read.' };
    }
    if (!Array.isArray(rows)) return { ok: false, error: 'Expected a JSON array.' };
  } else {
    const lines = t.split(/\r?\n/).filter((l) => l.trim());
    const header = (lines.shift() ?? '').toLowerCase().split(',').map((h) => h.trim());
    if (header.join(',') !== 'system,code,display,active') return { ok: false, error: 'CSV header must be: system,code,display,active' };
    rows = lines.map((l) => {
      const m = /^([^,]*),([^,]*),("(?:[^"]|"")*"|[^,]*),([^,]*)$/.exec(l.trim());
      if (!m) return null;
      const display = (m[3] ?? '').startsWith('"') ? (m[3] ?? '').slice(1, -1).replace(/""/g, '"') : (m[3] ?? '');
      return { system: m[1]?.trim(), code: m[2]?.trim(), display: display.trim(), active: (m[4] ?? '').trim().toLowerCase() !== 'false' };
    });
  }
  const items: ConceptImport[] = [];
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i];
    if (!isObj(r) || typeof r.system !== 'string' || !r.system || typeof r.code !== 'string' || !r.code || typeof r.display !== 'string' || !r.display)
      return { ok: false, error: `Row ${i + 1}: system, code and display are required.` };
    items.push({ system: r.system, code: r.code, display: r.display, active: r.active !== false });
  }
  if (items.length === 0) return { ok: false, error: 'No concepts found.' };
  return { ok: true, items };
}

/** Job-demand JSON (single profile or array) → profiles with jobCode, title, unit and a demands object. */
export function parseJobDemands(text: string): ParseResult<JobDemandProfile> {
  let v: unknown;
  try {
    v = JSON.parse(text);
  } catch {
    return { ok: false, error: 'The JSON could not be read.' };
  }
  const list = Array.isArray(v) ? v : [v];
  const items: JobDemandProfile[] = [];
  for (let i = 0; i < list.length; i++) {
    const p = list[i];
    if (!isObj(p) || typeof p.jobCode !== 'string' || !p.jobCode || typeof p.title !== 'string' || typeof p.unit !== 'string' || !isObj(p.demands))
      return { ok: false, error: `Profile ${i + 1}: jobCode, title, unit and a demands object are required.` };
    items.push(p as unknown as JobDemandProfile);
  }
  if (items.length === 0) return { ok: false, error: 'No profiles found.' };
  return { ok: true, items };
}

/** "NOISE, DUST:Wood dust" → hazards (code[:name]). */
export function parseHazards(text: string): { code: string; name: string }[] {
  return text
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => {
      const [code = '', ...rest] = s.split(':');
      return { code: code.trim().toUpperCase().replace(/\s+/g, '_'), name: rest.join(':').trim() || code.trim() };
    });
}
