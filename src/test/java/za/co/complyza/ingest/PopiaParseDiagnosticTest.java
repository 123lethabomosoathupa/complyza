package za.co.complyza.ingest;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Diagnostic only: shows how many sections the parser finds in data/popia.pdf and where it stops. */
class PopiaParseDiagnosticTest {

    @Test
    void printWhatTheParserSees() throws Exception {
        Path pdf = Path.of("data", "popia.pdf");
        assumeTrue(Files.exists(pdf), "data/popia.pdf not found");

        var reader = new PagePdfDocumentReader(new FileSystemResource(pdf),
                PdfDocumentReaderConfig.builder().withPagesPerDocument(1).build());
        String text = reader.get().stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("\n"));

        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target", "popia-extracted.txt"), text);

        List<SectionChunk> chunks = new LegalSectionParser().parse(text, "POPIA");
        List<String> sections = chunks.stream().map(SectionChunk::section).distinct().toList();

        System.out.println("=== PDF size bytes: " + Files.size(pdf));
        System.out.println("=== Extracted characters: " + text.length());
        System.out.println("=== Chunks: " + chunks.size() + ", sections: " + sections.size());
        System.out.println("=== Section numbers found: " + sections);

        if (!sections.isEmpty()) {
            String last = sections.get(sections.size() - 1);
            int lastNumber = Integer.parseInt(last.replaceAll("[^0-9]", ""));
            String wanted = String.valueOf(lastNumber + 1);
            System.out.println("=== Parser stopped after section " + last + ". Lines starting with " + wanted + ":");
            int shown = 0;
            for (String line : text.replace("\r", "").split("\n")) {
                String t = line.trim();
                if (t.startsWith(wanted) && shown < 6) {
                    System.out.println("    [" + visible(t) + "]");
                    shown++;
                }
            }
            if (shown == 0) {
                System.out.println("    (no line in the text starts with " + wanted + ")");
            }
        }
    }

    /** Shows non-ASCII characters as \\uXXXX so the console can't hide them. */
    private static String visible(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.substring(0, Math.min(80, s.length())).toCharArray()) {
            if (c < 32 || c > 126) {
                sb.append(String.format("\\u%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}