import { describe, expect, it } from 'vitest';
import { isCandidateOnly, navFor, resolveRoute } from './navigation';

const paths = (roles: string[]) => navFor(roles).map((n) => n.path);

describe('candidate navigation (role CANDIDATE, E07-F03)', () => {
  it('a candidate without employee features sees only pre-employment', () => {
    expect(isCandidateOnly(['CANDIDATE'])).toBe(true);
    expect(isCandidateOnly(['role_candidate'])).toBe(true);
    expect(paths(['CANDIDATE'])).toEqual(['/pre-employment']);
    for (const r of ['/', '/refer', '/documents', '/appointments', '/absence', '/advice', '/surveillance', '/nope']) expect(resolveRoute(r, ['CANDIDATE'])).toBe('/pre-employment');
  });

  it('employees get the new sections and no pre-employment', () => {
    const p = paths(['EMPLOYEE']);
    expect(p).toEqual(expect.arrayContaining(['/', '/appointments', '/absence', '/advice', '/surveillance', '/documents', '/privacy']));
    expect(p).not.toContain('/pre-employment');
    expect(resolveRoute('/pre-employment', ['EMPLOYEE'])).toBe('/');
    expect(resolveRoute('/absence', ['EMPLOYEE'])).toBe('/absence');
    expect(isCandidateOnly(['EMPLOYEE'])).toBe(false);
  });

  it('an employee who is also a candidate keeps everything plus pre-employment', () => {
    const p = paths(['EMPLOYEE', 'CANDIDATE']);
    expect(p[0]).toBe('/');
    expect(p).toContain('/pre-employment');
    expect(p).toContain('/absence');
    expect(resolveRoute('/pre-employment', ['EMPLOYEE', 'CANDIDATE'])).toBe('/pre-employment');
  });

  it('employees get the increment-4 treatment and telehealth sections; candidates do not', () => {
    expect(paths(['EMPLOYEE'])).toEqual(expect.arrayContaining(['/treatment', '/telehealth']));
    expect(navFor(['EMPLOYEE']).filter((n) => n.source === 'care').map((n) => n.key)).toEqual(['treatment', 'telehealth']);
    expect(resolveRoute('/treatment', ['CANDIDATE'])).toBe('/pre-employment');
    expect(resolveRoute('/telehealth', ['EMPLOYEE'])).toBe('/telehealth');
  });

  it('nav labels come from the right string table', () => {
    expect(navFor(['EMPLOYEE']).filter((n) => n.source === 'oh').map((n) => n.key)).toEqual(['appointments', 'absence', 'surveillance']);
    expect(navFor(['CANDIDATE'])[0]).toEqual({ path: '/pre-employment', key: 'preEmployment', source: 'oh' });
  });
});
