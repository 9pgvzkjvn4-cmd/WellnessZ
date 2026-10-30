package com.wellnessz.e11.advisory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.wellnessz.e11.advisory.AdvisoryTargeting.RosteredDuty;
import com.wellnessz.e11.advisory.AdvisoryTargeting.StaffMember;
import com.wellnessz.e11.advisory.AdvisoryTargeting.Target;

class AdvisoryTargetingTest {

    static final List<StaffMember> STAFF = List.of(
            new StaffMember("D1", "DXB", "STATION_AGENT", true),
            new StaffMember("D2", "DXB", "CABIN_CREW", true),
            new StaffMember("D3", "DXB", "STATION_AGENT", false),
            new StaffMember("L1", "LHR", "STATION_AGENT", true),
            new StaffMember("E1003", "DXB", "CABIN_CREW", true));

    @Test
    void rosteredCrewAreTargetedOnceEachWithTheDuty() {
        List<Target> t = AdvisoryTargeting.targets(List.of(new RosteredDuty("E1003", "WZ205"), new RosteredDuty("E1002", "WZ205"),
                new RosteredDuty("E1003", "WZ207")), STAFF, null, Set.of());
        assertThat(t).containsExactly(new Target("E1003", "ROSTERED", "WZ205"), new Target("E1002", "ROSTERED", "WZ205"));
    }

    @Test
    void baseStaffWithTheRolesAreAddedButRosterWins() {
        List<Target> t = AdvisoryTargeting.targets(List.of(new RosteredDuty("E1003", "WZ205")), STAFF, "DXB", Set.of("CABIN_CREW"));
        assertThat(t).containsExactly(new Target("E1003", "ROSTERED", "WZ205"), new Target("D2", "BASE_ROLE", null));
    }

    @Test
    void withoutRolesAllActiveBaseStaffAreTargeted() {
        List<Target> t = AdvisoryTargeting.targets(List.of(), STAFF, "DXB", Set.of());
        assertThat(t).extracting(Target::employeeId).containsExactly("D1", "D2", "E1003");
    }

    @Test
    void acknowledgementRate() {
        assertThat(AdvisoryTargeting.rate(1, 2)).isEqualTo(50.0);
        assertThat(AdvisoryTargeting.rate(2, 3)).isEqualTo(66.7);
        assertThat(AdvisoryTargeting.rate(0, 0)).isNull();
    }
}
