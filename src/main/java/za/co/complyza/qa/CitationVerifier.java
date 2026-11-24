package za.co.complyza.qa;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import za.co.complyza.search.RetrievedSection;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic guard against invented law. A citation is kept only if:
 * 1. its sourceId refers to a section we actually retrieved, and
 * 2. its quote appears verbatim in that section. The comparison looks at letters and digits only, so
 * differences in whitespace, case, dashes, punctuation, ligatures and PDF line-break hyphenation do not
 * matter. "..." may be used to skip text, but the pieces must appear in order.
 *
 * Small models often write "S2(a)(i)" instead of "S2". The id is only a lookup key, so the
 * subsection suffix is ignored; the quote must still match the retrieved text word for word.
 *
 * Act, section number and URL always come from the retrieved source, never from the model.
 */
public class CitationVerifier {

    private static final Logger log = LoggerFactory.getLogger(CitationVerifier.class);

    /** Minimum number of letters/digits a quote must contain (after removing spaces and punctuation). */
    private static final int MIN_QUOTE_CHARS = 12;

    /** Leading "S" plus digits, e.g. "S2" from "S2(a)(i)" or "s 2". */
    private static final Pattern SOURCE_ID = Pattern.compile("^\\s*S\\s*(\\d+)");

    /** Splits a quote on "..." or the single ellipsis character. Must run before squash(). */
    private static final Pattern ELLIPSIS = Pattern.compile("\\s*(?:\\.\\.\\.|\u2026)\\s*");

    /**
     * Government Gazette PDFs have margin line numbers (5, 10, 15, ...). Text extraction leaves them at the
     * end of lines, e.g. "between the data 5" followed by "subject and ...". Only used as a fallback.
     */
    private static final Pattern LINE_END_NUMBER = Pattern.compile("(?m)[ \\t]+(?:5|[1-9][05])[ \\t]*$");

    public record Outcome(List<VerifiedCitation> verified, int rejected) {
    }

    public Outcome verify(List<LlmAnswer.LlmCitation> citations, Map<String, RetrievedSection> sourcesById) {
        List<VerifiedCitation> ok = new ArrayList<>();
        int rejected = 0;
        if (citations == null) {
            return new Outcome(ok, 0);
        }
        for (LlmAnswer.LlmCitation c : citations) {
            if (c == null || c.sourceId() == null || c.quote() == null) {
                log.warn("Rejected citation: missing sourceId or quote ({})", c);
                rejected++;
                continue;
            }
            String id = canonicalId(c.sourceId());
            RetrievedSection src = sourcesById.get(id);
            if (src == null) {
                log.warn("Rejected citation: unknown sourceId '{}' (known: {})", c.sourceId(), sourcesById.keySet());
                rejected++;
                continue;
            }
            if (!quoteAppearsIn(c.quote(), src.text())) {
                log.warn("Rejected citation for {}: {} | quote: \"{}\"",
                        id, divergence(c.quote(), src.text()), c.quote());
                rejected++;
                continue;
            }
            boolean duplicate = ok.stream().anyMatch(v -> v.sourceId().equals(id)
                    && squash(v.quote()).equals(squash(c.quote())));
            if (!duplicate) {
                ok.add(new VerifiedCitation(id, src.act(), src.section(), src.heading(),
                        c.quote().trim(), src.sourceUrl(), src.versionLabel()));
            }
        }
        return new Outcome(ok, rejected);
    }

    /** "S2(a)(i)" -> "S2"; anything that does not start with S<digits> is only upper-cased and trimmed. */
    static String canonicalId(String raw) {
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        Matcher m = SOURCE_ID.matcher(upper);
        return m.find() ? "S" + m.group(1) : upper;
    }

    static boolean quoteAppearsIn(String quote, String sourceText) {
        // Strict first. Only if that fails, retry with PDF margin line numbers removed from the SOURCE
        // (never from the quote), so a quote with a different number in it is still rejected.
        return matches(quote, squash(sourceText))
                || matches(quote, squash(stripMarginLineNumbers(sourceText)));
    }

    static String stripMarginLineNumbers(String s) {
        return LINE_END_NUMBER.matcher(s).replaceAll("");
    }

    private static boolean matches(String quote, String haystack) {
        int from = 0;
        int matchedChars = 0;
        // Split on the ellipsis BEFORE squashing, because squashing removes the dots.
        for (String part : ELLIPSIS.split(quote)) {
            String p = squash(part);
            if (p.isEmpty()) {
                continue;
            }
            int idx = haystack.indexOf(p, from);
            if (idx < 0) {
                return false;
            }
            from = idx + p.length();
            matchedChars += p.length();
        }
        return matchedChars >= MIN_QUOTE_CHARS;
    }

    /** Diagnostic only: how much of the quote matches, and what the source has where the match stops. */
    static String divergence(String quote, String sourceText) {
        String haystack = squash(sourceText);
        String q = squash(quote);
        int lo = 0;
        int hi = q.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (haystack.contains(q.substring(0, mid))) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        if (lo == q.length()) {
            return "the whole quote appears in the source but is shorter than " + MIN_QUOTE_CHARS
                    + " letters/digits";
        }
        int end = haystack.indexOf(q.substring(0, lo)) + lo;
        return "first " + lo + " of " + q.length() + " letters/digits match; quote continues '"
                + q.substring(lo, Math.min(q.length(), lo + 25)) + "' but source has '"
                + haystack.substring(end, Math.min(haystack.length(), end + 25)) + "'";
    }

    /**
     * Lower-case letters and digits only. NFKC first, so ligatures such as the "fi" ligature become plain
     * letters. Spaces, dashes, soft hyphens, brackets and other punctuation are dropped, which also repairs
     * PDF extraction problems such as "informa- tion" or "ofthe".
     */
    static String squash(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                sb.appendCodePoint(cp);
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    /** Readable normalisation (spaces kept). No longer used for matching; kept for other callers. */
    static String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC)
                .replace('\u2018', '\'').replace('\u2019', '\'')
                .replace('\u201C', '"').replace('\u201D', '"')
                .replace('\u2013', '-').replace('\u2014', '-')
                .replaceAll("[\"]", "")
                .toLowerCase(Locale.ROOT);
        // PDF extraction hyphenates words across lines: "informa- tion" -> "information"
        n = n.replaceAll("(\\p{L})-\\s+(\\p{L})", "$1$2");
        return n.replaceAll("\\s+", " ").trim();
    }
}