import type { AuthClient, Session } from '../auth/AuthClient';

export interface ScreenProps {
  client: AuthClient;
  session: Session;
  onSessionExpired: () => void;
}
