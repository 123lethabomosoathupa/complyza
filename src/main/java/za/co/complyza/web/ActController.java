package za.co.complyza.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only endpoints for the Acts browser: which Acts are loaded, and the full text of one Act
 * grouped by section. Open to analysts (anything under /api needs the ANALYST or ADMIN role).
 */
@RestController
@RequestMapping("/api/acts")
public class ActController {

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+)(.*)$");

    private static final Map<String, String[]> INFO = Map.of(
            "POPIA", new String[]{
                    "Protection of Personal Information Act 4 of 2013",
                    "South Africa's data protection law. It gives effect to the constitutional right to privacy by "
                            + "setting conditions for how a responsible party may process personal information, "
                            + "what rights data subjects have, how personal information may be sent to other "
                            + "countries, and how the Information Regulator enforces the Act."},
            "FICA", new String[]{
                    "Financial Intelligence Centre Act 38 of 2001",
                    "South Africa's anti-money-laundering law. It establishes the Financial Intelligence Centre and "
                            + "sets duties for accountable institutions, such as knowing their customers, keeping "
                            + "records and reporting suspicious and unusual transactions."},
            "FIC", new String[]{
                    "Financial Intelligence Centre Act 38 of 2001",
                    "South Africa's anti-money-laundering law. It establishes the Financial Intelligence Centre and "
                            + "sets duties for accountable institutions, such as knowing their customers, keeping "
                            + "records and reporting suspicious and unusual transactions."});

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public ActController(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** One entry per loaded Act (latest version). */
    @GetMapping
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT s.act, s.version_label, s.chunk_count, s.ingested_at,
                       (SELECT count(DISTINCT v.metadata ->> 'section')
                          FROM vector_store v WHERE v.metadata ->> 'act' = s.act) AS sections
                FROM source_document s
                WHERE s.id = (SELECT max(x.id) FROM source_document x WHERE x.act = s.act)
                ORDER BY s.act
                """);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String act = String.valueOf(r.get("act"));
            String[] info = INFO.getOrDefault(act, new String[]{
                    act, "Regulation text loaded into Comply ZA. Open it to read every section."});
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("act", act);
            m.put("name", info[0]);
            m.put("summary", info[1]);
            m.put("versionLabel", r.get("version_label"));
            m.put("sections", r.get("sections"));
            m.put("chunks", r.get("chunk_count"));
            m.put("ingestedAt", r.get("ingested_at"));
            out.add(m);
        }
        return out;
    }

    /** Full text of one Act, grouped by section and sorted by section number. */
    @GetMapping("/{act}")
    public ResponseEntity<Map<String, Object>> detail(@PathVariable String act) {
        String key = act.trim().toUpperCase();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT content, metadata::text AS metadata
                FROM vector_store
                WHERE metadata ->> 'act' = ?
                """, key);
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        Map<String, List<String[]>> pieces = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> md = parse((String) row.get("metadata"));
            String section = str(md, "section");
            if (section.isBlank()) {
                continue;
            }
            // The first line of every chunk is a "POPIA section 19: Heading" label; the heading is sent separately.
            String content = Objects.toString(row.get("content"), "");
            int newline = content.indexOf('\n');
            String body = (newline >= 0 ? content.substring(newline + 1) : content).trim();

            Map<String, Object> s = sections.computeIfAbsent(section, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("section", k);
                m.put("heading", "");
                m.put("chapter", "");
                m.put("chapterTitle", "");
                return m;
            });
            fillIfBlank(s, "heading", str(md, "heading"));
            fillIfBlank(s, "chapter", str(md, "chapter"));
            fillIfBlank(s, "chapterTitle", str(md, "chapterTitle"));
            pieces.computeIfAbsent(section, k -> new ArrayList<>()).add(new String[]{str(md, "subsections"), body});
        }

        List<Map<String, Object>> ordered = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : sections.entrySet()) {
            List<String[]> parts = pieces.get(e.getKey());
            parts.sort(Comparator.comparing(p -> p[0]));
            StringBuilder text = new StringBuilder();
            for (String[] p : parts) {
                if (text.length() > 0) {
                    text.append("\n\n");
                }
                text.append(p[1]);
            }
            e.getValue().put("text", text.toString());
            ordered.add(e.getValue());
        }
        Comparator<Map<String, Object>> order = Comparator
                .<Map<String, Object>>comparingInt(m -> number(str(m, "section")))
                .thenComparing(m -> suffix(str(m, "section")));
        ordered.sort(order);

        String[] info = INFO.getOrDefault(key, new String[]{
                key, "Regulation text loaded into Comply ZA. Every section is listed below."});
        List<Map<String, Object>> versions = jdbc.queryForList(
                "SELECT version_label FROM source_document WHERE act = ? ORDER BY id DESC LIMIT 1", key);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("act", key);
        body.put("name", info[0]);
        body.put("summary", info[1]);
        body.put("versionLabel", versions.isEmpty() ? "" : versions.get(0).get("version_label"));
        body.put("sectionCount", ordered.size());
        body.put("sections", ordered);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> parse(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static void fillIfBlank(Map<String, Object> target, String key, String value) {
        if (!value.isBlank() && String.valueOf(target.get(key)).isBlank()) {
            target.put(key, value);
        }
    }

    private static String str(Map<String, Object> m, String key) {
        return Objects.toString(m.get(key), "");
    }

    private static int number(String section) {
        Matcher m = LEADING_NUMBER.matcher(section);
        return m.find() ? Integer.parseInt(m.group(1)) : Integer.MAX_VALUE;
    }

    private static String suffix(String section) {
        Matcher m = LEADING_NUMBER.matcher(section);
        return m.find() ? m.group(2) : section;
    }
}