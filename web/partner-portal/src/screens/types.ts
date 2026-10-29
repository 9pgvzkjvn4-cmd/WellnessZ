import type { AuthClient, Session } from '../auth/AuthClient';
import type { Me } from '../auth/me';
import { S } from '../strings';

export interface ScreenProps {
  client: AuthClient;
  session: Session;
  me: Me;
  onSessionExpired: () => void;
}

/** Organisation label for confidentiality hints. */
export const orgLabel = (me: Me) => me.orgCode ?? S.app.yourOrg;
