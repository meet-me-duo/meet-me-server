package meetme.diagnostic;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Public-token projection contract; exclusively synthetic private data. No provider requests. */
public class SafeRejectionProjectionContractTest {
    private static final Pattern TOKENS = Pattern.compile("[a-z_][a-z0-9_]*");
    private static final String TECHNICAL =
        "invalid schema: recursive references must be optional or nullable.";
    private static final List<String> TECHNICAL_TOKENS = List.of(
        "invalid", "schema", "recursive", "references", "must", "be", "optional", "or", "nullable");

    @Test
    void SRP01_publicTechnicalCauseCompositionSurvivesWithoutAReasonDictionary() {
        var result = SafeRejectionProjection.project(TECHNICAL, Set.of(), List.of());
        assertEquals(TECHNICAL_TOKENS, tokens(result.text()));
        assertEquals(0, result.redactions());
        assertFalse(result.truncated());
        assertBoundedAscii(result);
    }

    @Test
    void SRP02_keysKoreanNamesIdentifiersAddressesUrlsAndUnknownNamesAreRedacted() {
        List<String> privateValues = List.of(
            "sk-proj-DUMMY_NOT_A_REAL_KEY_8b6a",
            "AIzaDUMMY_NOT_A_REAL_KEY_8b6a",
            "홍길동의 가상 일정",
            "c390fef8-63b8-4e82-937b-c34b33017a5e",
            "dummy.person@example.invalid",
            "https://schema.example.invalid/nullable?key=DUMMY",
            "Zorblax",
            "1234567890");
        var result = SafeRejectionProjection.project(
            "invalid schema: " + String.join(" ", privateValues), Set.of(), List.of());
        for (String value : privateValues) {
            assertFalse(result.text().contains(value));
            assertFalse(result.text().contains(value.toLowerCase(Locale.ROOT)));
        }
        assertFalse(result.text().contains("example"));
        assertFalse(result.text().contains("dummy"));
        assertFalse(result.text().matches(".*[0-9].*"));
        assertTrue(result.redactions() > 0);
        assertTrue(tokens(result.text()).stream().allMatch(Set.of("invalid", "schema", "redacted")::contains));
        assertBoundedAscii(result);
    }

    @Test
    void SRP03_knownPrivateLiteralsOverrideTechnicalVocabularyAndTrustedSchemaSymbols() {
        var result = SafeRejectionProjection.project(
            "schema WHOLE_DAY invalid recursive references", Set.of("WHOLE_DAY"),
            List.of("schema", "WHOLE_DAY", "recursive references"));
        assertFalse(result.text().contains("schema"));
        assertFalse(result.text().contains("whole_day"));
        assertFalse(result.text().contains("recursive"));
        assertFalse(result.text().contains("references"));
        assertTrue(result.text().contains("invalid"));
        assertTrue(result.redactions() >= 3);
        assertBoundedAscii(result);
    }

    @Test
    void SRP04_unknownTokensAreMaskedIndividuallyAndDoNotEraseUsefulTechnicalContext() {
        var result = SafeRejectionProjection.project(
            "invalid Zorblax schema: recursive references must be optional or nullable.",
            Set.of(), List.of());
        assertEquals(
            List.of("invalid", "redacted", "schema", "recursive", "references", "must", "be", "optional", "or", "nullable"),
            tokens(result.text()));
        assertTrue(result.text().contains("[redacted]"));
        assertTrue(result.redactions() > 0);
        assertBoundedAscii(result);
    }

    @Test
    void SRP05_outputUsesCanonicalPublicTokensAndOnlyValidTrustedSchemaIdentifiers() {
        var result = SafeRejectionProjection.project(
            "INVALID SCHEMA date_scope WHOLE_DAY https://example.invalid 98765 비공개",
            Set.of("date_scope", "WHOLE_DAY", "https://example.invalid", "98765", "비공개"), List.of());
        List<String> output = tokens(result.text());
        assertTrue(output.containsAll(List.of("invalid", "schema", "date_scope", "whole_day")));
        assertTrue(output.stream().allMatch(Set.of("invalid", "schema", "date_scope", "whole_day", "redacted")::contains));
        assertFalse(result.text().contains("98765"));
        assertEquals(result.text().toLowerCase(Locale.ROOT), result.text());
        assertBoundedAscii(result);
    }

    @Test
    void SRP06_longOutputIsCappedAndReportsTruncationWithoutInventingPartialPublicTokens() {
        var result = SafeRejectionProjection.project("invalid schema ".repeat(500), Set.of(), List.of());
        assertTrue(result.truncated());
        assertTrue(tokens(result.text()).stream().allMatch(Set.of("invalid", "schema")::contains));
        assertEquals(0, result.redactions());
        assertBoundedAscii(result);
    }

    private static List<String> tokens(String text) {
        return TOKENS.matcher(text).results().map(m -> m.group()).toList();
    }

    private static void assertBoundedAscii(SafeRejectionProjection.Projection result) {
        assertTrue(result.text().length() <= 2048);
        assertTrue(result.text().chars().allMatch(c -> c >= 32 && c <= 126));
        assertTrue(result.redactions() >= 0);
    }
}
