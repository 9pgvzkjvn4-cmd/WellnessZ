package com.wellnessz.e33.domain;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;

import com.wellnessz.casekernel.device.DeviceCodes;
import com.wellnessz.casekernel.device.DeviceObservation;
import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Who may verify a capture and what must be true before it is filed (E33-F02, E31-F06). The rule per form comes from
 * {@code devicecap.form_rule}: the verifier roles, whether interpretation needs physician sign-off, the restricted class.
 * <ul>
 *   <li>restricted forms (E10): the collector within their own session, or the MRO (an OH physician designated as acting
 *       MRO counts as MRO);</li>
 *   <li>other forms: any of the form's verifier roles;</li>
 *   <li>a {@code BLOCK} flag prevents posting (422 {@code observation_blocked}); every {@code WARN} flag must be acknowledged
 *       by gate or code (422 {@code flags_not_acknowledged}).</li>
 * </ul>
 */
public final class VerificationRule {

    private VerificationRule() {
    }

    public static Set<String> roles(String csv) {
        return csv == null || csv.isBlank() ? Set.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /** May the user verify (accept / reject) a capture of this form? */
    public static boolean mayVerify(CurrentUser user, Set<String> verifierRoles, boolean restricted, boolean ownSession, boolean actsAsMro) {
        if (restricted) {
            return actsAsMro || (user.hasRole(Roles.COLLECTOR) && ownSession);
        }
        return verifierRoles.stream().anyMatch(user::hasRole);
    }

    /** May the user see values of a restricted capture (preview)? Others see "restricted reading" without values. */
    public static boolean maySeeRestricted(CurrentUser user, boolean ownSession, boolean actsAsMro) {
        return actsAsMro || (user.hasRole(Roles.COLLECTOR) && ownSession);
    }

    public static void requireVerifier(CurrentUser user, Set<String> verifierRoles, boolean restricted, boolean ownSession, boolean actsAsMro) {
        if (!mayVerify(user, verifierRoles, restricted, ownSession, actsAsMro)) {
            throw ApiException.forbidden(restricted ? "Restricted readings are verified by the collector of the session or the MRO"
                    : "Your role cannot verify this form");
        }
    }

    public static void requireSignOff(CurrentUser user, Set<String> signOffRoles) {
        if (signOffRoles.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "sign_off_not_applicable", "This form has no interpretation to sign off");
        }
        if (signOffRoles.stream().noneMatch(user::hasRole)) {
            throw ApiException.forbidden("Interpretation sign-off is reserved to physicians");
        }
    }

    /**
     * Gate check before posting.
     *
     * @param acknowledged gate codes or finding codes the verifier acknowledged
     */
    public static void requirePostable(List<DeviceObservation.QualityFlag> flags, Collection<String> acknowledged) {
        if (flags.stream().anyMatch(f -> f.outcome() == DeviceCodes.GateOutcome.BLOCK)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "observation_blocked",
                    "A quality gate blocks posting – see the messages in the preview");
        }
        Set<String> ack = acknowledged == null ? Set.of() : Set.copyOf(acknowledged);
        List<String> missing = flags.stream().filter(f -> f.outcome() == DeviceCodes.GateOutcome.WARN)
                .filter(f -> !ack.contains(f.code()) && !ack.contains(f.gate())).map(DeviceObservation.QualityFlag::code).toList();
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "flags_not_acknowledged",
                    "Acknowledge the warnings before accepting: " + String.join(", ", missing));
        }
    }

    /** Flags that need nothing from the verifier (batch accept-all takes only these). */
    public static boolean clean(List<DeviceObservation.QualityFlag> flags) {
        return flags.stream().noneMatch(f -> f.outcome() == DeviceCodes.GateOutcome.WARN || f.outcome() == DeviceCodes.GateOutcome.BLOCK);
    }
}
