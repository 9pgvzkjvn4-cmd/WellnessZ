package com.wellnessz.e03.terminology;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.wellnessz.common.error.ApiException;
import com.wellnessz.common.model.Roles;
import com.wellnessz.e03.support.CodeRef;
import com.wellnessz.security.audit.AuditService;
import com.wellnessz.security.identity.CurrentUser;

/**
 * Terminology services over the local concept cache {@code platform.code_concept} (E03-F03): word-prefix search on the
 * display (GIN full-text index) plus code-prefix search, per-user and per-role favourites, recently used codes, local
 * reporting-category mapping, bulk import of annual releases and the release register. Production loads the licensed
 * national editions through the import endpoint.
 */
@Service
public class TerminologyCatalog {

    static final Set<String> SYSTEMS = Set.of("SNOMED_CT", "ICD_10", "ICD_11", "LOINC", "ODH");
    static final int MAX_IMPORT = 20_000;

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public TerminologyCatalog(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    public record Concept(String system, String code, String display) {
    }

    public record Favourite(String system, String code, String display, String scope, String role) {
    }

    public record Mapping(String system, String code, String display, String category, String categoryLabel) {
    }

    public record Release(String system, String version, Instant loadedAt, String loadedBy, String licence, int conceptCount) {
    }

    public record ImportResult(int upserted, int deactivated) {
    }

    public record ImportItem(String system, String code, String display, Boolean active) {
    }

    // ------------------------------------------------------------------ search

    /**
     * Word-prefix search on the display ({@code to_tsquery('simple', 'word:*')}) combined with a code-prefix match.
     * The query is reduced to letters, digits, '.' and '-' before it reaches SQL; all values are bound parameters.
     */
    @Transactional(readOnly = true)
    public List<Concept> search(String system, String q, int limit) {
        String tsQuery = toPrefixQuery(q);
        String codePrefix = codePrefix(q);
        if (tsQuery == null && codePrefix == null) {
            return List.of();
        }
        return jdbc.query("""
                select system, code, display from platform.code_concept
                 where active
                   and (cast(? as varchar) is null or system = ?)
                   and ((cast(? as varchar) is not null and upper(code) like ?)
                     or (cast(? as varchar) is not null and to_tsvector('simple', display) @@ to_tsquery('simple', ?)))
                 order by case when upper(code) like coalesce(?, '') then 0 else 1 end, length(display), code
                 limit ?
                """, (rs, i) -> new Concept(rs.getString(1), rs.getString(2), rs.getString(3)),
                system, system, codePrefix, codePrefix == null ? "" : codePrefix, tsQuery, tsQuery == null ? "x" : tsQuery,
                codePrefix, limit);
    }

    /** {@code "ankle spr"} → {@code "ankle:* & spr:*"}; null when the query contains no word characters. */
    static String toPrefixQuery(String q) {
        if (q == null) {
            return null;
        }
        List<String> words = new ArrayList<>();
        StringBuilder w = new StringBuilder();
        String input = q.length() > 100 ? q.substring(0, 100) : q;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                w.append(Character.toLowerCase(c));
            } else if (!w.isEmpty()) {
                words.add(w.toString());
                w.setLength(0);
            }
        }
        if (!w.isEmpty()) {
            words.add(w.toString());
        }
        if (words.isEmpty()) {
            return null;
        }
        List<String> terms = words.stream().limit(8).map(x -> x + ":*").toList();
        return String.join(" & ", terms);
    }

    /** Code prefix for codes such as {@code S93}, {@code S93.4}, {@code 28539-}: letters, digits, '.' and '-' only. */
    static String codePrefix(String q) {
        if (q == null) {
            return null;
        }
        String t = q.trim();
        if (t.isEmpty() || t.length() > 50) {
            return null;
        }
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 128 && (Character.isLetterOrDigit(c) || c == '.' || c == '-')) {
                b.append(Character.toUpperCase(c));
            } else {
                return null; // a phrase, not a code
            }
        }
        return b + "%";
    }

    @Transactional(readOnly = true)
    public Optional<Concept> lookup(String system, String code) {
        return jdbc.query("select system, code, display from platform.code_concept where system = ? and code = ? and active",
                (rs, i) -> new Concept(rs.getString(1), rs.getString(2), rs.getString(3)), system, code).stream().findFirst();
    }

    /** Validates a coded value against the terminology cache and normalises its display; LOCAL codes are passed through. */
    public CodeRef require(CodeRef ref) {
        if (ref == null) {
            return null;
        }
        if ("LOCAL".equals(ref.system())) {
            return ref;
        }
        Concept c = lookup(ref.system(), ref.code())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "unknown_code",
                        "Code " + ref.code() + " is not an active concept of " + ref.system()));
        return new CodeRef(c.system(), c.code(), c.display());
    }

    // ------------------------------------------------------------------ favourites & recent

    @Transactional(readOnly = true)
    public List<Favourite> favourites(CurrentUser user) {
        List<Favourite> out = new ArrayList<>(jdbc.query("""
                select f.system, f.code, coalesce(c.display, f.code) from ohrecord.term_favourite f
                  left join platform.code_concept c on c.system = f.system and c.code = f.code
                 where f.owner_type = 'USER' and f.owner = ? order by f.created_at desc
                """, (rs, i) -> new Favourite(rs.getString(1), rs.getString(2), rs.getString(3), "USER", null), user.username()));
        Set<String> seen = new java.util.HashSet<>();
        out.forEach(f -> seen.add(f.system() + "|" + f.code()));
        for (String role : user.roles().stream().sorted().toList()) {
            for (Favourite f : jdbc.query("""
                    select f.system, f.code, coalesce(c.display, f.code) from ohrecord.term_favourite f
                      left join platform.code_concept c on c.system = f.system and c.code = f.code
                     where f.owner_type = 'ROLE' and f.owner = ? order by f.code
                    """, (rs, i) -> new Favourite(rs.getString(1), rs.getString(2), rs.getString(3), "ROLE", role), role)) {
                if (seen.add(f.system() + "|" + f.code())) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    /** Adds a personal favourite, or a role favourite when {@code role} is given (NURSING_HEAD / CMO curate role sets). */
    @Transactional
    public Favourite addFavourite(CurrentUser user, String system, String code, String role) {
        Concept c = lookup(system, code).orElseThrow(() -> ApiException.badRequest("unknown_code", "Unknown code"));
        String ownerType = role == null ? "USER" : "ROLE";
        if (role != null && !user.hasAnyRole(Roles.NURSING_HEAD, Roles.CMO)) {
            throw ApiException.forbidden("Only the nursing head or CMO maintain role favourites");
        }
        String owner = role == null ? user.username() : role;
        try {
            jdbc.update("insert into ohrecord.term_favourite (id, owner_type, owner, system, code, created_at) values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), ownerType, owner, system, code, Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException already) {
            // idempotent
        }
        return new Favourite(c.system(), c.code(), c.display(), ownerType, role);
    }

    @Transactional
    public int removeFavourite(CurrentUser user, String system, String code, String role) {
        if (role != null && !user.hasAnyRole(Roles.NURSING_HEAD, Roles.CMO)) {
            throw ApiException.forbidden("Only the nursing head or CMO maintain role favourites");
        }
        return jdbc.update("delete from ohrecord.term_favourite where owner_type = ? and owner = ? and system = ? and code = ?",
                role == null ? "USER" : "ROLE", role == null ? user.username() : role, system, code);
    }

    /** Remembers that the user coded a concept (recent list, E03-BL-009). */
    @Transactional
    public void recordUsage(String username, String system, String code) {
        if (username == null || system == null || code == null || "LOCAL".equals(system)) {
            return;
        }
        jdbc.update("""
                insert into ohrecord.term_usage (username, system, code, used_at) values (?, ?, ?, ?)
                on conflict (username, system, code) do update set used_at = excluded.used_at
                """, username, system, code, Timestamp.from(clock.instant()));
    }

    @Transactional(readOnly = true)
    public List<Concept> recent(CurrentUser user) {
        return jdbc.query("""
                select u.system, u.code, coalesce(c.display, u.code) from ohrecord.term_usage u
                  left join platform.code_concept c on c.system = u.system and c.code = u.code
                 where u.username = ? order by u.used_at desc limit 20
                """, (rs, i) -> new Concept(rs.getString(1), rs.getString(2), rs.getString(3)), user.username());
    }

    // ------------------------------------------------------------------ mappings

    @Transactional(readOnly = true)
    public Mapping mapping(String system, String code) {
        Concept c = lookup(system, code).orElseThrow(() -> ApiException.notFound("Code"));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select category, category_label from ohrecord.term_mapping
                 where system = ? and ? like code_prefix || '%' order by length(code_prefix) desc limit 1
                """, system, code);
        if (rows.isEmpty()) {
            return new Mapping(c.system(), c.code(), c.display(), "UNMAPPED", "Not mapped to a local reporting category");
        }
        return new Mapping(c.system(), c.code(), c.display(), (String) rows.get(0).get("category"),
                (String) rows.get(0).get("category_label"));
    }

    // ------------------------------------------------------------------ releases

    /** Annual release update: bulk upsert; {@code active=false} rows deactivate retired concepts. */
    @Transactional
    public ImportResult importConcepts(CurrentUser user, List<ImportItem> items, String version, String licence) {
        if (items.size() > MAX_IMPORT) {
            throw ApiException.badRequest("too_many_concepts", "At most " + MAX_IMPORT + " concepts per request");
        }
        Map<String, Integer> perSystem = new LinkedHashMap<>();
        int deactivated = 0;
        List<Object[]> batch = new ArrayList<>(items.size());
        for (ImportItem it : items) {
            if (it.system() == null || !SYSTEMS.contains(it.system()) || it.code() == null || it.code().isBlank()
                    || it.code().length() > 50 || it.display() == null || it.display().isBlank() || it.display().length() > 500) {
                throw ApiException.badRequest("invalid_concept", "Each concept needs a supported system, a code (≤ 50) and a display (≤ 500)");
            }
            boolean active = it.active() == null || it.active();
            if (!active) {
                deactivated++;
            }
            perSystem.merge(it.system(), 1, Integer::sum);
            batch.add(new Object[] {it.system(), it.code().trim(), it.display().trim(), active});
        }
        jdbc.batchUpdate("""
                insert into platform.code_concept (system, code, display, active) values (?, ?, ?, ?)
                on conflict (system, code) do update set display = excluded.display, active = excluded.active
                """, batch);
        Instant now = clock.instant();
        perSystem.forEach((system, count) -> jdbc.update("""
                insert into platform.terminology_release (id, system, version, licence, concept_count, loaded_by, loaded_at)
                values (?, ?, ?, ?, ?, ?, ?)""", UUID.randomUUID(), system, version == null ? "unversioned" : version,
                licence == null ? "Licence to be recorded" : licence, count, user.username(), Timestamp.from(now)));
        audit.record("TERMINOLOGY_IMPORT", "CODE_SYSTEM", String.join(",", perSystem.keySet()), AuditService.Outcome.SUCCESS,
                "upserted=" + items.size() + " deactivated=" + deactivated);
        return new ImportResult(items.size(), deactivated);
    }

    @Transactional(readOnly = true)
    public List<Release> releases() {
        return jdbc.query("""
                select system, version, loaded_at, loaded_by, licence, concept_count from platform.terminology_release
                 order by loaded_at desc, system
                """, (rs, i) -> new Release(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getString(4),
                rs.getString(5), rs.getInt(6)));
    }
}
