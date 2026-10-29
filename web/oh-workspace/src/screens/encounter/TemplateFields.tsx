import { codingText, type AnswerValue, type Coding, type EncounterTemplate, type TemplateField } from '../../api/record';
import type { AuthClient } from '../../auth/AuthClient';
import { errorFor, IdField, type FieldError } from '../../components/forms';
import { TerminologyPicker } from '../../components/TerminologyPicker';
import { answerFieldId } from '../../domain/encounter';

function isCoding(v: unknown): v is Coding {
  return !!v && typeof v === 'object' && !Array.isArray(v) && 'code' in v && 'system' in v;
}

function FieldControl({ f, value, onChange, readOnly, describedBy, invalid, client, onSessionExpired }: {
  f: TemplateField;
  value: AnswerValue | undefined;
  onChange: (v: AnswerValue) => void;
  readOnly: boolean;
  describedBy?: string;
  invalid: boolean;
  client: AuthClient;
  onSessionExpired: () => void;
}) {
  const id = answerFieldId(f.key);
  const common = { id, 'aria-describedby': describedBy, 'aria-invalid': invalid || undefined, disabled: readOnly, required: f.required };
  switch (f.type) {
    case 'TEXTAREA':
      return <textarea {...common} rows={3} maxLength={4000} value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)} />;
    case 'NUMBER':
      return <input {...common} type="number" value={typeof value === 'number' ? value : ''} onChange={(e) => onChange(e.target.value === '' ? null : e.target.valueAsNumber)} />;
    case 'DATE':
      return <input {...common} type="date" value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)} />;
    case 'BOOLEAN':
      return <input {...common} type="checkbox" checked={value === true} onChange={(e) => onChange(e.target.checked)} />;
    case 'SELECT':
      return (
        <select {...common} value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)}>
          <option value="">Choose…</option>
          {(f.options ?? []).map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      );
    case 'MULTISELECT': {
      const sel = Array.isArray(value) ? value : [];
      return (
        <div id={id} role="group" aria-describedby={describedBy} tabIndex={-1} className="checkbox-group">
          {(f.options ?? []).map((o) => (
            <label key={o.value} className="checkbox">
              <input type="checkbox" disabled={readOnly} checked={sel.includes(o.value)} onChange={(e) => onChange(e.target.checked ? [...sel, o.value] : sel.filter((x) => x !== o.value))} /> {o.label}
            </label>
          ))}
        </div>
      );
    }
    case 'CODE':
      return (
        <div id={id} tabIndex={-1} aria-describedby={describedBy}>
          {isCoding(value) ? (
            <p className="selected-code">
              <strong>{codingText(value)}</strong>{' '}
              {!readOnly && (
                <button type="button" className="link-button" onClick={() => onChange(null)}>
                  Clear
                </button>
              )}
            </p>
          ) : (
            <p className="small muted">No code selected.</p>
          )}
          {!readOnly && <TerminologyPicker client={client} onSessionExpired={onSessionExpired} label={`Code for ${f.label}`} system={f.codeSystem} onSelect={(c) => onChange(c)} />}
        </div>
      );
    default:
      return <input {...common} maxLength={500} value={typeof value === 'string' ? value : ''} onChange={(e) => onChange(e.target.value)} />;
  }
}

/** Renders the encounter template sections and fields (TEXT, TEXTAREA, NUMBER, SELECT, MULTISELECT, BOOLEAN, DATE, CODE). */
export function TemplateFields({ template, answers, onChange, readOnly, errors, client, onSessionExpired }: {
  template: EncounterTemplate;
  answers: Record<string, AnswerValue>;
  onChange: (a: Record<string, AnswerValue>) => void;
  readOnly: boolean;
  errors: readonly FieldError[];
  client: AuthClient;
  onSessionExpired: () => void;
}) {
  return (
    <>
      {template.sections.map((s) => (
        <fieldset key={s.key} className="template-section">
          <legend>{s.title}</legend>
          <div className="form-grid">
            {s.fields.map((f) => {
              const id = answerFieldId(f.key);
              const err = errorFor(errors, id);
              const label = `${f.label}${f.unit ? ` (${f.unit})` : ''}`;
              return (
                <div key={f.key} className={f.type === 'TEXTAREA' || f.type === 'CODE' || f.type === 'MULTISELECT' ? 'span-all' : undefined}>
                  <IdField id={id} label={label} required={f.required} error={err}>
                    {(d) => <FieldControl f={f} value={answers[f.key]} onChange={(v) => onChange({ ...answers, [f.key]: v })} readOnly={readOnly} describedBy={d} invalid={!!err} client={client} onSessionExpired={onSessionExpired} />}
                  </IdField>
                </div>
              );
            })}
          </div>
        </fieldset>
      ))}
    </>
  );
}
