package com.meetme.server.coordination.adapter.output.integration;

import java.util.*;
import java.security.MessageDigest;
import tools.jackson.databind.json.JsonMapper;
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTerm;
import com.meetme.server.submission.domain.time.ApprovedKoreanTimeTermPolicy;

/** Isolated syntax-only producer prototype; not installed in any product provider or reader. */
public final class C5DirectLocatorProducer {
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().findAndAddModules().build();
    private static final int MAX_BYTES = 262144;
    private static final Set<String> LOCATOR = Set.of("source_id", "revision", "quote");
    private static final Set<String> TERMS = new HashSet<>();
    /** Generation-only finite form of the existing owned-expression depth limit; no reader change. */
    public static Map<String,Object> finiteExpressionSchema(Map<String,Object> schema) {
        var result = map(copy(schema));
        var name = "c5_inline_expression";
        var definitions = map(result.get("$defs"));
        if (!definitions.keySet().equals(Set.of(name))) throw fail("UNKNOWN_FINITE_EXPRESSION_SCHEMA");
        var expression = map(definitions.get(name));
        if (!expression.keySet().equals(Set.of("anyOf"))) throw fail("UNKNOWN_FINITE_EXPRESSION_SCHEMA");
        var branches = list(expression.get("anyOf"));
        var operators = branches.stream().map(raw -> map(map(map(raw).get("properties")).get("operator")).get("enum")).toList();
        if (!operators.equals(List.of(List.of("CLAIM_REF"), List.of("ALL_OF"), List.of("ANY_OF"),
                List.of("EXCEPT"), List.of("PREFER"), List.of("CHOICE")))) throw fail("UNKNOWN_FINITE_EXPRESSION_SCHEMA");
        var finite = new LinkedHashMap<String,Object>();
        for (int depth = 1; depth <= 8; depth++) {
            var at = depth == 1 ? name : name + "_depth_" + depth;
            var next = name + "_depth_" + (depth + 1);
            var alternatives = depth == 8 ? branches.subList(0, 1) : branches;
            finite.put(at, Map.of("anyOf", finiteExpressionRefs(copy(alternatives), "#/$defs/" + next)));
        }
        result.put("$defs", finite);
        return result;
    }

    private static Object finiteExpressionRefs(Object value, String next) {
        var reference = "#/$defs/c5_inline_expression";
        if (value instanceof List<?> items) return items.stream().map(item -> finiteExpressionRefs(item, next)).toList();
        if (!(value instanceof Map<?,?>)) return value;
        var node = map(value);
        if ("array".equals(node.get("type")) && node.get("maxItems") instanceof Number maximum && maximum.intValue() == 0
                && Map.of("$ref", reference).equals(node.get("items"))) {
            // maxItems=0 makes this recursive item schema unreachable; the empty array is preserved exactly.
            node.put("items", Map.of("type", "null"));
        }
        var rewritten = new LinkedHashMap<String,Object>();
        node.forEach((key, item) -> {
            if (key.equals("$ref")) {
                if (!reference.equals(item)) throw fail("UNKNOWN_FINITE_EXPRESSION_REFERENCE");
                rewritten.put(key, next);
            } else rewritten.put(key, finiteExpressionRefs(item, next));
        });
        return rewritten;
    }
    static {
        for (var term : ApprovedKoreanTimeTerm.values()) TERMS.addAll(ApprovedKoreanTimeTermPolicy.INSTANCE.lexemes(term));
    }

    /** Optional instruction-only alignment; never rewrites source data, declarations or calculation policy. */
    public static String scopedUnresolvedInstructions(String instructions) {
        var original = "Unknown personal event or before/after boundaries stay UNKNOWN even on a known date; "
                + "retain their date and event context in complete condition evidence without inventing clocks or whole-day availability.";
        var replacement = "Unknown personal event or before/after boundaries stay UNKNOWN unless complete source evidence explicitly confines "
                + "a condition with effect=UNRESOLVED to a finite influence range. Only then use RAW_SCOPE with "
                + "interpretation=UNRESOLVED and temporal_resolution=NON_PRECISE; its endpoints bound uncertainty, "
                + "never personal clocks or actual availability. A known date alone does not justify confinement or a change of effect. "
                + "Retain full date and event evidence without inventing missing clocks or whole-day availability.";
        var at = instructions == null ? -1 : instructions.indexOf(original);
        if (at < 0 || instructions.indexOf(original, at + original.length()) >= 0)
            throw fail("SCOPED_UNRESOLVED_INSTRUCTION_MISMATCH");
        return instructions.substring(0, at) + replacement + instructions.substring(at + original.length());
    }

    @SuppressWarnings("unchecked")
    public static Map<String,Object> schema(Map<String,Object> ownedSchema) {
        var projected = map(copy(ownedSchema));
        var resultFields = map(map(map(map(projected.get("properties")).get("results")).get("items")).get("properties"));
        var condition = map(map(resultFields.get("conditions")).get("items"));
        for (Object raw : list(condition.get("anyOf"))) {
            var branch = map(raw);
            var fields = map(branch.get("properties"));
            var bank = list(map(map(fields.get("proofs")).get("items")).get("anyOf"));
            var general = locatorSchema(map(bank.get(0)));
            var term = locatorSchema(map(bank.get(1)));
            fields.remove("proofs");
            var required = list(branch.get("required"));
            if (!required.remove("proofs")) throw fail("PRODUCER_SCHEMA_NOT_OWNED");
            fields.put("evidence", arrayLocator(map(fields.get("evidence")), general));
            fields.put("temporal", projectTemporalSchema(fields.get("temporal"), general, term));
        }
        return projected;
    }

    /** Optional generation-only restriction; does not alter documents or reader semantics. */
    public static Map<String,Object> termPrecisionSchema(Map<String,Object> directSchema) {
        var projected = map(copy(directSchema));
        var resultFields = map(map(map(map(projected.get("properties")).get("results")).get("items")).get("properties"));
        var condition = map(map(resultFields.get("conditions")).get("items"));
        var branches = list(condition.get("anyOf"));
        if (branches.size() != 2) throw fail("UNKNOWN_TERM_PRECISION_SCHEMA");
        var wholeDay = map(branches.get(0));
        var other = map(branches.get(1));
        var atoms = list(map(map(other.get("properties")).get("temporal")).get("anyOf"));
        if (atoms.size() != 4 || !List.of("APPROVED_TERM").equals(
                map(map(map(atoms.get(0)).get("properties")).get("kind")).get("enum")))
            throw fail("UNKNOWN_TERM_PRECISION_SCHEMA");
        var term = map(copy(other));
        var termFields = map(term.get("properties"));
        map(termFields.get("temporal")).put("anyOf", new ArrayList<>(List.of(copy(atoms.get(0)))));
        map(termFields.get("temporal_resolution")).put("enum", new ArrayList<>(List.of("NON_PRECISE")));
        var remaining = map(copy(other));
        map(map(remaining.get("properties")).get("temporal")).put("anyOf", new ArrayList<>(atoms.subList(1, atoms.size())));
        condition.put("anyOf", new ArrayList<>(List.of(wholeDay, term, remaining)));
        return projected;
    }

    /** Generation-only contract candidate. RAW syntax and rejection-witness compatibility stay intact. */
    public static Map<String,Object> coherentTemporalSchema(Map<String,Object> directSchema) {
        var projected = map(copy(directSchema));
        var fields = map(map(map(map(projected.get("properties")).get("results")).get("items")).get("properties"));
        var condition = map(map(fields.get("conditions")).get("items"));
        var original = list(condition.get("anyOf"));
        if (original.size() != 2) throw fail("UNKNOWN_COHERENCE_SCHEMA");
        var other = map(original.get(1));
        var atoms = list(map(map(other.get("properties")).get("temporal")).get("anyOf"));
        var kinds = atoms.stream().map(atom -> map(map(map(atom).get("properties")).get("kind")).get("enum")).toList();
        if (!kinds.equals(List.of(List.of("APPROVED_TERM"), List.of("BOUNDS"), List.of("UNKNOWN"), List.of("RAW_SCOPE"))))
            throw fail("UNKNOWN_COHERENCE_SCHEMA");
        var term = map(atoms.get(0));
        var pure = map(copy(term));
        map(pure.get("properties")).put("model_scope", nullableArm(map(term.get("properties")).get("model_scope"), "null"));
        var context = map(copy(term));
        map(context.get("properties")).put("model_scope", nullableArm(map(term.get("properties")).get("model_scope"), "object"));
        var bounds = map(atoms.get(1));
        var lower = nullableArm(map(bounds.get("properties")).get("lower"), "object");
        var upper = nullableArm(map(bounds.get("properties")).get("upper"), "object");
        var absent = nullableArm(map(bounds.get("properties")).get("lower"), "null");
        var known = List.of("AVAILABLE", "UNAVAILABLE", "PREFERRED");
        var effects = List.of("AVAILABLE", "UNAVAILABLE", "PREFERRED", "UNRESOLVED");
        var interpretations = List.of("EXPLICIT", "INFERRED", "UNRESOLVED");
        var imprecise = List.of("NON_PRECISE");
        var branches = new ArrayList<Object>();
        branches.add(original.get(0));
        branches.add(coherenceBranch(other, pure, effects, interpretations, imprecise));
        branches.add(coherenceBranch(other, context, effects, List.of("INFERRED"), imprecise));
        branches.add(coherenceBranch(other, boundsAtom(bounds, lower, absent), known, List.of("EXPLICIT", "INFERRED"), imprecise));
        branches.add(coherenceBranch(other, boundsAtom(bounds, absent, upper), known, List.of("EXPLICIT", "INFERRED"), imprecise));
        branches.add(coherenceBranch(other, boundsAtom(bounds, lower, upper), effects, List.of("EXPLICIT"), List.of("PRECISE_INTERVAL")));
        branches.add(coherenceBranch(other, boundsAtom(bounds, lower, upper), effects, List.of("INFERRED"), imprecise));
        branches.add(coherenceBranch(other, map(atoms.get(2)), effects, interpretations, imprecise));
        branches.add(coherenceBranch(other, map(atoms.get(3)), null, null, null));
        condition.put("anyOf", branches);
        return projected;
    }

    private static Map<String,Object> nullableArm(Object schema, String type) {
        var matches = list(map(schema).get("anyOf")).stream().map(C5DirectLocatorProducer::map)
                .filter(arm -> type.equals(arm.get("type"))).toList();
        if (matches.size() != 1) throw fail("UNKNOWN_COHERENCE_SCHEMA");
        return map(copy(matches.get(0)));
    }

    private static Map<String,Object> boundsAtom(Map<String,Object> original, Object lower, Object upper) {
        var result = map(copy(original));
        var fields = map(result.get("properties"));
        fields.put("lower", copy(lower));
        fields.put("upper", copy(upper));
        return result;
    }

    private static Map<String,Object> coherenceBranch(Map<String,Object> original, Map<String,Object> atom,
            List<String> effects, List<String> interpretations, List<String> resolutions) {
        var result = map(copy(original));
        var fields = map(result.get("properties"));
        map(fields.get("temporal")).put("anyOf", new ArrayList<>(List.of(copy(atom))));
        if (effects != null) map(fields.get("effect")).put("enum", new ArrayList<>(effects));
        if (interpretations != null) map(fields.get("interpretation")).put("enum", new ArrayList<>(interpretations));
        if (resolutions != null) map(fields.get("temporal_resolution")).put("enum", new ArrayList<>(resolutions));
        return result;
    }

    public static Map<String,Object> lower(Map<String,Object> directDocument) {
        size(directDocument);
        var lowered = map(copy(directDocument));
        var results = list(lowered.get("results"));
        if (results.size() < 2 || results.size() > 50) throw fail("RESULT_COUNT");
        for (Object rawResult : results) {
            var result = map(rawResult);
            var claims = list(result.get("conditions"));
            if (claims.size() > 32) throw fail("CONDITION_COUNT");
            for (Object rawClaim : claims) {
                var claim = map(rawClaim);
                if (claim.containsKey("proofs")) throw fail("MIXED_PROOF_SYNTAX");
                Bank bank = new Bank(); // Never shared across conditions or input namespaces.
                claim.put("evidence", bank.refs(claim.get("evidence")));
                var atom = map(claim.get("temporal"));
                if (atom.containsKey("date_evidence")) atom.put("date_evidence", bank.refs(atom.get("date_evidence")));
                if (atom.containsKey("term_evidence")) atom.put("term_evidence", bank.id(atom.get("term_evidence"), true));
                for (String side : List.of("lower", "upper")) {
                    if (atom.get(side) != null) {
                        var bound = map(atom.get(side));
                        if (bound.containsKey("clock_evidence")) bound.put("clock_evidence", bank.refs(bound.get("clock_evidence")));
                    }
                }
                if (bank.entries.isEmpty()) throw fail("EMPTY_PROOF_BANK");
                claim.put("proofs", new ArrayList<>(bank.entries.values()));
            }
        }
        size(lowered);
        return lowered;
    }

    public static Map<String,Object> projectRequest(Map<String,Object> ownedRequest) {
        if (!"c5-owned-proof-choice-2".equals(ownedRequest.get("producer_schema_revision")))
            throw fail("UNKNOWN_PRODUCER_REQUEST");
        var result = map(copy(ownedRequest));
        String prompt = text(result.get("prompt"));
        try {
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (!digest.equals("2e8b647ef9bd92d48b1b256159ddd406e42a24e995c54b6f7f08ec3bf206f047"))
                throw fail("UNKNOWN_WITNESS_PROMPT");
        } catch (java.security.NoSuchAlgorithmException e) { throw fail("PROMPT_DIGEST_UNAVAILABLE"); }
        for (var pair : WITNESS_SENTENCES.entrySet()) {
            int start = prompt.indexOf(pair.getKey());
            if (start < 0 || prompt.indexOf(pair.getKey(), start + 1) >= 0) throw fail("UNKNOWN_WITNESS_PROMPT");
            prompt = prompt.replace(pair.getKey(), pair.getValue());
        }
        result.put("prompt", prompt);
        result.put("schema", schema(map(result.get("schema"))));
        result.put("producer_schema_revision", "c5-direct-locator-prototype-1");
        return result;
    }

    private static Map<String,Object> locatorSchema(Map<String,Object> proofSchema) {
        var schema = map(copy(proofSchema));
        map(schema.get("properties")).remove("id");
        if (!list(schema.get("required")).remove("id")) throw fail("PROOF_SCHEMA_MISSING_ID");
        return schema;
    }

    private static Map<String,Object> arrayLocator(Map<String,Object> original, Map<String,Object> locator) {
        var result = map(copy(original));
        result.put("items", copy(locator));
        return result;
    }

    private static Object projectTemporalSchema(Object value, Map<String,Object> general, Map<String,Object> term) {
        if (value instanceof List<?> items) {
            var out = new ArrayList<>();
            for (Object item : items) out.add(projectTemporalSchema(item, general, term));
            return out;
        }
        if (!(value instanceof Map<?,?>)) return value;
        var out = map(copy(value));
        for (var entry : new ArrayList<>(out.entrySet())) out.put(entry.getKey(), projectTemporalSchema(entry.getValue(), general, term));
        if (out.get("properties") instanceof Map<?,?>) {
            var fields = map(out.get("properties"));
            for (String key : List.of("date_evidence", "clock_evidence"))
                if (fields.containsKey(key)) fields.put(key, arrayLocator(map(fields.get(key)), general));
            if (fields.containsKey("term_evidence")) fields.put("term_evidence", copy(term));
        }
        return out;
    }

    private static final class Bank {
        final Map<String,Map<String,Object>> entries = new LinkedHashMap<>();
        final Map<String,byte[]> tuples = new HashMap<>();

        List<String> refs(Object value) {
            var values = list(value);
            if (values.isEmpty() || values.size() > 32) throw fail("WITNESS_ARRAY_LIMIT");
            var refs = new ArrayList<String>();
            for (Object item : values) refs.add(id(item, false));
            return refs;
        }

        String id(Object value, boolean term) {
            var locator = map(value);
            if (!locator.keySet().equals(LOCATOR)) throw fail("LOCATOR_FIELDS");
            String source = text(locator.get("source_id")), quote = text(locator.get("quote"));
            Object revision = locator.get("revision");
            if (source.isBlank() || quote.isEmpty() || quote.codePointCount(0, quote.length()) > 500 || !(revision instanceof Byte || revision instanceof Short ||
                    revision instanceof Integer || revision instanceof Long || revision instanceof java.math.BigInteger))
                throw fail("INVALID_LOCATOR");
            java.math.BigInteger number = new java.math.BigInteger(revision.toString());
            if (number.signum() < 1 || number.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) > 0)
                throw fail("REVISION_RANGE");
            if (term && !TERMS.contains(quote)) throw fail("TERM_LITERAL");
            try {
                byte[] tuple = JSON.writeValueAsBytes(List.of(source, number.intValue(), quote));
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tuple)).substring(0, 60);
                String id = (term ? "t:" : "p:") + digest;
                if (tuples.containsKey(id) && !Arrays.equals(tuples.get(id), tuple)) throw fail("PROOF_DIGEST_COLLISION");
                if (!entries.containsKey(id)) {
                    if (entries.size() >= 32) throw fail("PROOF_BANK_LIMIT");
                    var proof = new LinkedHashMap<String,Object>(); proof.put("id", id); proof.putAll(locator);
                    entries.put(id, proof); tuples.put(id, tuple);
                }
                return id;
            } catch (IllegalArgumentException e) { throw e; }
            catch (Exception e) { throw fail("PROOF_DIGEST_FAILURE"); }
        }
    }

    private static Object copy(Object value) {
        if (value instanceof Map<?,?> fields) {
            var result = new LinkedHashMap<String,Object>();
            fields.forEach((key, item) -> result.put(text(key), copy(item)));
            return result;
        }
        if (value instanceof List<?> items) {
            var result = new ArrayList<>(); for (Object item : items) result.add(copy(item)); return result;
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        throw fail("UNSUPPORTED_JSON_VALUE");
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {
        if (!(value instanceof Map<?,?>)) throw fail("EXPECTED_OBJECT"); return (Map<String,Object>) value;
    }
    @SuppressWarnings("unchecked") private static List<Object> list(Object value) {
        if (!(value instanceof List<?>)) throw fail("EXPECTED_ARRAY"); return (List<Object>) value;
    }
    private static String text(Object value) { if (!(value instanceof String s)) throw fail("EXPECTED_STRING"); return s; }
    private static void size(Object value) {
        if (JSON.writeValueAsBytes(value).length > MAX_BYTES) throw fail("RESPONSE_BYTE_LIMIT");
    }
    private static IllegalArgumentException fail(String code) { return new IllegalArgumentException(code); }

    private static final Map<String,String> WITNESS_SENTENCES = witnessSentences();
    private static Map<String,String> witnessSentences() {
        return Map.ofEntries(
                Map.entry("Each condition owns one proofs bank. Declare each native source_id/revision/quote once there, using p: IDs for general text and t: IDs for approved terms. condition.evidence lists its nonempty meaning/effect proof IDs; temporal date_evidence/clock_evidence and term_evidence reference that same local bank. All entries must be used. Never borrow another condition's ID or invent a source, revision, quote, answer or participant confirmation.", "Write exact native source_id/revision/quote locators directly in condition.evidence and temporal date_evidence/clock_evidence/term_evidence. Never invent a source, revision, quote, answer or participant confirmation."),
                Map.entry("Quotes locate native evidence; they do not confirm meaning. Use exact contiguous quotations with a unique occurrence; the server counts positions. A component reuses its proof's original source, including the linked supplement for an answer; it does not repeat source metadata.", "Quotes locate native evidence; they do not confirm meaning. Use exact contiguous quotations with a unique occurrence; the server counts positions. Each locator keeps its actual original or linked supplement source."),
                Map.entry("For term proof entries (id starts with t:), quote only the exact approved lexeme, with no date, particle, surrounding phrase or sentence.", "For term_evidence.quote, use only the exact approved lexeme, with no date, particle, surrounding phrase or sentence."),
                Map.entry("General proof quotes may use broader exact native quotations to retain context and uniquely locate the source.", "General evidence quotes may use broader exact native quotations to retain context and uniquely locate the source."),
                Map.entry("If an approved lexeme occurs more than once in the same source, do not broaden a term proof quote or choose the first occurrence; preserve the unresolved evidence failure instead. A lexeme alone does not establish an effect.", "If an approved lexeme occurs more than once in the same source, do not broaden term_evidence.quote or choose the first occurrence; preserve the unresolved evidence failure instead. A lexeme alone does not establish an effect.")
        );
    }
}
