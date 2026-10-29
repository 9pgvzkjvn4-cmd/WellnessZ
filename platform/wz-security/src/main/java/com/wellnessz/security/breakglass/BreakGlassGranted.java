package com.wellnessz.security.breakglass;

import java.time.Instant;
import java.util.UUID;

/** In-process event: the DPO is alerted (content-free) when break-glass access is used. */
public record BreakGlassGranted(UUID grantId, String grantedTo, Instant reviewDueAt) {
}
