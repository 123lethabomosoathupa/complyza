package za.co.complyza.qa;

import org.junit.jupiter.api.Test;
import za.co.complyza.search.RetrievedSection;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CitationVerifierTest {

    private final CitationVerifier verifier = new CitationVerifier();

    private final RetrievedSection s3 = new RetrievedSection("id-3", "SAMPLE", "2", "3", "Security safeguards", "",
            "SAMPLE section 3: Security safeguards\n(1) A responsible party must secure the integrity and "
                    + "confidentiality of personal\ninformation in its possession by taking appropriate measures.",
            "https://example.org/act", "Act 1 of 2024", 0.9);

    private final Map<String, RetrievedSection> sources = Map.of("S1", s3);

    @Test
    void keepsVerbatimQuoteAcrossLineBreaks() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S1",
                "must secure the integrity and confidentiality of personal information")), sources);

        assertThat(outcome.rejected()).isZero();
        assertThat(outcome.verified()).singleElement().satisfies(c -> {
            assertThat(c.section()).isEqualTo("3");
            assertThat(c.sourceUrl()).isEqualTo("https://example.org/act");
        });
    }

    @Test
    void rejectsInventedQuote() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S1",
                "must notify the Regulator within 72 hours")), sources);

        assertThat(outcome.verified()).isEmpty();
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownSourceId() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S9",
                "must secure the integrity and confidentiality")), sources);

        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void acceptsSourceIdWithSubsectionSuffixWhenQuoteIsReal() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S1(a)(i)",
                "must secure the integrity and confidentiality of personal information")), sources);

        assertThat(outcome.rejected()).isZero();
        assertThat(outcome.verified()).singleElement()
                .satisfies(c -> assertThat(c.sourceId()).isEqualTo("S1"));
    }

    @Test
    void stillRejectsSuffixedSourceIdWhenQuoteIsInvented() {
        var outcome = verifier.verify(List.of(new LlmAnswer.LlmCitation("S1(a)",
                "must notify the Regulator within 72 hours")), sources);

        assertThat(outcome.verified()).isEmpty();
        assertThat(outcome.rejected()).isEqualTo(1);
    }

    @Test
    void allowsEllipsisWhenPiecesAppearInOrder() {
        assertThat(CitationVerifier.quoteAppearsIn(
                "A responsible party must secure ... by taking appropriate measures", s3.text())).isTrue();
        assertThat(CitationVerifier.quoteAppearsIn(
                "by taking appropriate measures ... A responsible party must secure", s3.text())).isFalse();
    }

    @Test
    void rejectsTrivialQuotes() {
        assertThat(CitationVerifier.quoteAppearsIn("the", s3.text())).isFalse();
    }

    @Test
    void matchesAcrossLigatureInSource() {
        String source = "the transfer is for the bene\uFB01t of the data subject";
        assertThat(CitationVerifier.quoteAppearsIn(
                "the transfer is for the benefit of the data subject", source)).isTrue();
    }

    @Test
    void matchesAcrossLigatureInQuote() {
        String source = "the transfer is for the benefit of the data subject";
        assertThat(CitationVerifier.quoteAppearsIn(
                "the transfer is for the bene\uFB01t of the data subject", source)).isTrue();
    }

    @Test
    void matchesAcrossCurlyApostrophe() {
        String source = "in response to the data subject\u2019s request";
        assertThat(CitationVerifier.quoteAppearsIn(
                "in response to the data subject's request", source)).isTrue();
    }

    @Test
    void matchesAcrossDashVariants() {
        String source = "to the data subject, and\u2014(i) it is not reasonably practicable";
        assertThat(CitationVerifier.quoteAppearsIn(
                "to the data subject, and-(i) it is not reasonably practicable", source)).isTrue();
        assertThat(CitationVerifier.quoteAppearsIn(
                "to the data subject, and (i) it is not reasonably practicable", source)).isTrue();
    }

    @Test
    void matchesDashFollowedByLineBreakSpace() {
        String source = "to the data subject, and- (i) it is not reasonably practicable";
        assertThat(CitationVerifier.quoteAppearsIn(
                "to the data subject, and-(i) it is not reasonably practicable", source)).isTrue();
    }

    @Test
    void toleratesMissingSpaceFromPdfExtraction() {
        String source = "obtain the consent ofthe data subject to that transfer";
        assertThat(CitationVerifier.quoteAppearsIn(
                "obtain the consent of the data subject to that transfer", source)).isTrue();
    }

    @Test
    void toleratesLineBreakHyphenation() {
        String source = "effectively upholds principles for reasonable processing of the informa- tion";
        assertThat(CitationVerifier.quoteAppearsIn(
                "principles for reasonable processing of the information", source)).isTrue();
    }

    @Test
    void stillRejectsListMarkerMisread() {
        String source = "transfer personal information to a third party who is in a foreign country unless "
                + "(a) the third party who is the recipient of the information is subject to a law";
        assertThat(CitationVerifier.quoteAppearsIn(
                "a third party who is the recipient of the information is subject to a law", source)).isFalse();
    }

    @Test
    void ignoresMarginLineNumbersLeftInSourceText() {
        String source = "the transfer is necessary for the performance of a contract between the data 5\n"
                + "subject and the responsible party, or for the implementation of pre-contractual measures";
        assertThat(CitationVerifier.quoteAppearsIn(
                "the transfer is necessary for the performance of a contract between the data subject "
                        + "and the responsible party", source)).isTrue();
    }

    @Test
    void marginNumberFallbackDoesNotAcceptADifferentNumber() {
        String source = "must respond within 90\ndays of receiving the request";
        assertThat(CitationVerifier.quoteAppearsIn(
                "must respond within 30 days of receiving the request", source)).isFalse();
    }

    @Test
    void divergenceNamesWhatTheSourceHasInstead() {
        String source = "the transfer is necessary for the performance of a contract";
        String msg = CitationVerifier.divergence(
                "the transfer is necessary for the conclusion of a contract", source);

        assertThat(msg).contains("letters/digits match").contains("but source has");
    }
}