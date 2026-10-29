import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { formatDuration, slaFromDue, slaView } from '../domain/sla';
import { SafetyBadge, SlaBadge, UrgencyBadge } from './badges';

const NOW = Date.parse('2026-10-29T10:00:00Z');

describe('SLA badge rendering', () => {
  it.each([
    ['ON_TRACK', 'sla-green', 'On track', '●'],
    ['AT_RISK', 'sla-amber', 'At risk', '▲'],
    ['BREACHED', 'sla-red', 'Breached', '■'],
    ['MET', 'sla-met', 'Met', '✓'],
    ['NONE', 'sla-none', 'No SLA', '–'],
  ] as const)('%s → %s with text label "%s" and symbol (not colour alone)', (state, cls, label, symbol) => {
    const html = renderToStaticMarkup(<SlaBadge sla={{ state, minutesRemaining: 12 }} now={NOW} />);
    expect(html).toContain(cls);
    expect(html).toContain(`>${label}<`);
    expect(html).toContain(symbol);
    expect(html).toContain('aria-hidden="true"'); // symbol is decorative; label carries meaning
  });

  it('shows time remaining for open SLAs', () => {
    expect(renderToStaticMarkup(<SlaBadge sla={{ state: 'AT_RISK', minutesRemaining: 12, milestone: 'TRIAGE' }} now={NOW} />)).toContain('12 min left');
    expect(renderToStaticMarkup(<SlaBadge sla={{ state: 'ON_TRACK', minutesRemaining: 125 }} now={NOW} />)).toContain('2 h 5 min left');
  });

  it('shows overdue time for breached SLAs', () => {
    const html = renderToStaticMarkup(<SlaBadge sla={{ state: 'BREACHED', minutesRemaining: -45 }} now={NOW} />);
    expect(html).toContain('Overdue by 45 min');
    expect(html).toContain('sla-red');
  });

  it('derives remaining time from dueAt when minutesRemaining is absent', () => {
    expect(slaView({ state: 'ON_TRACK', dueAt: '2026-10-29T11:30:00Z' }, NOW).remaining).toBe('1 h 30 min left');
  });

  it('has an accessible description in the title', () => {
    const html = renderToStaticMarkup(<SlaBadge sla={{ state: 'AT_RISK', minutesRemaining: 12, milestone: 'TRIAGE' }} now={NOW} />);
    expect(html).toContain('title="SLA triage at risk, 12 min left"');
  });

  it('compact mode keeps the remaining time for screen readers only', () => {
    const html = renderToStaticMarkup(<SlaBadge sla={{ state: 'AT_RISK', minutesRemaining: 5 }} now={NOW} compact />);
    expect(html).toContain('visually-hidden">5 min left');
  });

  it('treats missing / unknown SLA as "No SLA"', () => {
    expect(renderToStaticMarkup(<SlaBadge sla={null} />)).toContain('No SLA');
    expect(slaView({ state: 'WHATEVER' as never }).tone).toBe('none');
  });

  it('formats durations and derives SLA from a due time', () => {
    expect(formatDuration(59)).toBe('59 min');
    expect(formatDuration(60)).toBe('1 h');
    expect(formatDuration(-1500)).toBe('1 d 1 h');
    expect(slaFromDue('2026-10-29T09:00:00Z', NOW)).toMatchObject({ state: 'BREACHED', minutesRemaining: -60 });
    expect(slaFromDue(undefined, NOW)).toBeNull();
  });
});

describe('urgency and safety badges', () => {
  it('urgency has a text label and a symbol', () => {
    const html = renderToStaticMarkup(<UrgencyBadge urgency="EMERGENCY" />);
    expect(html).toContain('urgency-emergency');
    expect(html).toContain('Emergency');
    expect(renderToStaticMarkup(<UrgencyBadge urgency={null} />)).toContain('Not triaged');
  });

  it('safety badge only for safety-critical cases', () => {
    expect(renderToStaticMarkup(<SafetyBadge safetyCritical category="FOOD_HANDLER" />)).toContain('Safety-critical: Food handler');
    expect(renderToStaticMarkup(<SafetyBadge safetyCritical={false} />)).toBe('');
  });
});
