import type { AuthClient, Session } from '../auth/AuthClient';

export interface ScreenProps {
  client: AuthClient;
  session: Session;
  onSessionExpired: () => void;
}

/** Increment-2 portal screens additionally render in the app language (en/ar/fr, Arabic right-to-left). */
export interface PortalScreenProps extends ScreenProps {
  lang: string;
}
