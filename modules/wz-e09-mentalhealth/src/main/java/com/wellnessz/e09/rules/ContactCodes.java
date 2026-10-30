package com.wellnessz.e09.rules;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

/**
 * Pseudonymous peer-support contact codes (E09-BL-007): {@code PS-XXXX-XXXX} from an unambiguous 32-character alphabet
 * (no 0/O, 1/I), 40 bits of randomness from a cryptographic generator. Codes are random – they cannot be derived from or
 * linked back to an employee, and the requester is never stored next to the code.
 */
public final class ContactCodes {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    public static final Pattern FORMAT = Pattern.compile("PS-[A-Z0-9]{4}-[A-Z0-9]{4}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ContactCodes() {
    }

    public static String generate() {
        return generate(RANDOM);
    }

    public static String generate(RandomGenerator random) {
        StringBuilder sb = new StringBuilder("PS-");
        for (int i = 0; i < 8; i++) {
            if (i == 4) {
                sb.append('-');
            }
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static boolean isValid(String code) {
        return code != null && FORMAT.matcher(code).matches();
    }
}
