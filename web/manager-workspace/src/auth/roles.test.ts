import { describe, expect, it } from 'vitest';
import { hasWorkspaceAccess } from './roles';

describe('workspace role gating', () => {
  it.each([
    ['MANAGER'],
    ['HR'],
    ['OH_CLINICIAN'],
    ['OH_ADMIN'],
    ['CASE_MANAGER'],
    ['PSYCH_CLINICIAN'],
    ['PLATFORM_ADMIN'],
    ['ROLE_manager'],
    ['hr'],
  ])('allows %s', (role) => {
    expect(hasWorkspaceAccess([role])).toBe(true);
  });

  it.each([[[]], [['EMPLOYEE']], [['AUDITOR']], [['PARTNER_PROVIDER']], [['INTEGRATION']], [['MANAGERX']], [['OH_UNKNOWN']]])(
    'denies %j',
    (roles) => {
      expect(hasWorkspaceAccess(roles)).toBe(false);
    },
  );

  it('denies an undefined role list', () => {
    expect(hasWorkspaceAccess(undefined)).toBe(false);
  });

  it('allows if any role qualifies', () => {
    expect(hasWorkspaceAccess(['EMPLOYEE', 'MANAGER'])).toBe(true);
  });
});
