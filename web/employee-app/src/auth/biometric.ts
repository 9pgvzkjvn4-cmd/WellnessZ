/**
 * Biometric unlock hook, enforced when MDM sets `wz.requireBiometric=true`.
 *
 * Register a native implementation at startup, e.g. with
 * `@aparajita/capacitor-biometric-auth`:
 *   registerBiometricVerifier({ verify: async () => { await BiometricAuth.authenticate({ reason: 'Unlock WellnessZ' }); return true; } });
 *
 * If biometrics are required but no verifier is registered the app fails CLOSED
 * (the stored refresh token is not used and interactive sign-in is required).
 */
export interface BiometricVerifier {
  verify(): Promise<boolean>;
}

let verifier: BiometricVerifier | undefined;

export function registerBiometricVerifier(v: BiometricVerifier): void {
  verifier = v;
}

export async function verifyBiometric(): Promise<boolean> {
  if (!verifier) return false;
  try {
    return await verifier.verify();
  } catch {
    return false;
  }
}
