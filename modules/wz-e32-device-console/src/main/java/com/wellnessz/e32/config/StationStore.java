package com.wellnessz.e32.config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.wellnessz.e32.support.Docs;
import com.wellnessz.e32.support.E32Support;

/** Sites, rooms and stations devices are assigned to ({@code devicecat.station}); test stations allow simulator-only tests. */
@Component("e32StationStore")
public class StationStore {

    private final E32Support support;

    public StationStore(E32Support support) {
        this.support = support;
    }

    public record Station(String stationCode, String siteCode, String roomCode, String name, String kind, boolean testSite, boolean active) {
    }

    public Optional<Station> find(String stationCode) {
        if (stationCode == null) {
            return Optional.empty();
        }
        return support.jdbc().query("select * from devicecat.station where station_code = ?", (rs, i) -> new Station(rs.getString("station_code"),
                rs.getString("site_code"), rs.getString("room_code"), rs.getString("name"), rs.getString("kind"), rs.getBoolean("test_site"),
                rs.getBoolean("active")), stationCode).stream().findFirst();
    }

    public List<Station> all() {
        return support.jdbc().query("select * from devicecat.station order by site_code, station_code", (rs, i) -> new Station(rs.getString("station_code"),
                rs.getString("site_code"), rs.getString("room_code"), rs.getString("name"), rs.getString("kind"), rs.getBoolean("test_site"),
                rs.getBoolean("active")));
    }

    public boolean testSite(String siteCode) {
        return siteCode != null && all().stream().anyMatch(s -> s.siteCode().equals(siteCode) && s.testSite());
    }

    public boolean siteExists(String siteCode) {
        return siteCode != null && all().stream().anyMatch(s -> s.siteCode().equals(siteCode));
    }

    public static Map<String, Object> view(Station s) {
        return Docs.of("stationCode", s.stationCode(), "siteCode", s.siteCode(), "roomCode", s.roomCode(), "name", s.name(), "kind", s.kind(),
                "testSite", s.testSite(), "active", s.active());
    }
}
