package com.wellnessz.e11.advisory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Advisory targeting (E11-F05, pure): crew rostered to the destination inside the validity window, plus active staff
 * based at the base station whose job code is one of the advisory's roles (all staff at the base when no role is given).
 * Each person is targeted once; a roster reason wins over a base reason.
 */
public final class AdvisoryTargeting {

    public static final String ROSTERED = "ROSTERED";
    public static final String BASE_ROLE = "BASE_ROLE";

    private AdvisoryTargeting() {
    }

    public record RosteredDuty(String employeeId, String dutyRef) {
    }

    public record StaffMember(String employeeId, String locationCode, String jobCode, boolean active) {
    }

    public record Target(String employeeId, String reason, String dutyRef) {
    }

    public static List<Target> targets(List<RosteredDuty> rostered, List<StaffMember> staff, String baseCode, Set<String> roles) {
        Map<String, Target> out = new LinkedHashMap<>();
        for (RosteredDuty d : rostered) {
            out.putIfAbsent(d.employeeId(), new Target(d.employeeId(), ROSTERED, d.dutyRef()));
        }
        if (baseCode != null) {
            for (StaffMember s : staff) {
                boolean roleMatch = roles == null || roles.isEmpty() || s.jobCode() != null && roles.contains(s.jobCode());
                if (s.active() && baseCode.equals(s.locationCode()) && roleMatch) {
                    out.putIfAbsent(s.employeeId(), new Target(s.employeeId(), BASE_ROLE, null));
                }
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Acknowledgement rate in % (one decimal), {@code null} without targets. */
    public static Double rate(int acknowledged, int targeted) {
        return targeted <= 0 ? null : Math.round(acknowledged * 1000.0 / targeted) / 10.0;
    }
}
