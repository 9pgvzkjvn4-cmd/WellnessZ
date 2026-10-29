import { describe, expect, it } from 'vitest';
import { ApiError } from './AuthClient';
import { loadMe, orgCodeOf, toMe } from './me';

describe('/me mapping', () => {
  it('derives the organisation code from the last org_scope segment', () => {
    expect(orgCodeOf('/PARTNERS/CITYHOSP')).toBe('CITYHOSP');
    expect(orgCodeOf('/PARTNERS/GULFTPA/')).toBe('GULFTPA');
    expect(orgCodeOf('')).toBeUndefined();
    expect(orgCodeOf(undefined)).toBeUndefined();
  });

  it('normalises roles and keeps only display fields', () => {
    expect(toMe({ displayName: 'Pat', orgScope: '/PARTNERS/PHYSIOPLUS', roles: ['ROLE_partner_provider'], employeeId: 'X1' })).toEqual({
      displayName: 'Pat',
      orgScope: '/PARTNERS/PHYSIOPLUS',
      orgCode: 'PHYSIOPLUS',
      roles: ['PARTNER_PROVIDER'],
    });
    expect(toMe(null)).toEqual({ displayName: undefined, orgScope: undefined, orgCode: undefined, roles: [] });
  });

  it('treats 403 as "no partner roles" and rethrows other errors', async () => {
    await expect(loadMe({ api: async () => Promise.reject(new ApiError(403)) })).resolves.toEqual({ roles: [] });
    await expect(loadMe({ api: async () => Promise.reject(new ApiError(503)) })).rejects.toBeInstanceOf(ApiError);
  });
});
