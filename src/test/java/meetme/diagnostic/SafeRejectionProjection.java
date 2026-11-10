package meetme.diagnostic;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Test-only projection of public protocol words, never a provider reason dictionary or raw log. */
public final class SafeRejectionProjection {
    public record Projection(String text, int redactions, boolean truncated) {}
    private static final int LIMIT = 2048;
    private static final int INPUT_LIMIT = 16384;
    private static final String MARK = String.valueOf((char) 1);
    private static final String REDACTED = "[redacted]";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]{0,63}");
    private static final Pattern WORD = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*|[0-9]+|.");
    private static final Pattern UUID = Pattern.compile(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern EMAIL = Pattern.compile(
        "(?i)[A-Z0-9.!#$%&'*+/=?^_\\x60{|}~-]+@[A-Z0-9.-]+\\.[A-Z]{2,}");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|mailto:)[^\\s<>\"']+");
    private static final Pattern KEY_PREFIX = Pattern.compile("(?i)^(?:sk[_-]|aiza|eyj)");
    private static final Set<String> PUBLIC_WORDS = Set.copyOf(new HashSet<>(List.of((
        "a an the to of for from at in on by as is are was were be been being has have had non " +
        "and or with without not no only one any all this that these those it its can cannot " +
        "could must should may will would please use using used pass given provided selected " +
        "invalid valid validation json schema schemas response request payload body content " +
        "received unknown name find field fields required optional nullable null string integer " +
        "number boolean object array arrays type types value values expected unexpected " +
        "error errors failed failure parse parsing parsed parameter parameters argument arguments " +
        "unsupported supported support supports allow allowed disallowed forbidden incompatible " +
        "compatible compatibility format formats combination combined additional other " +
        "recursive recursion cyclic cycle cycles reference references referenced ref defs definition " +
        "definitions property properties items item enum const pattern minimum maximum min max " +
        "less greater than equal zero positive negative empty nonempty bounded unbounded finite " +
        "infinite depth nesting nested deeply exceeded exceed exceeds limit limits complexity " +
        "complex too many much large small size level levels states state machine automaton grammar constraint constraints " +
        "resolve resolved resolving unresolved process processes processed processing compile compiled compiling compilation unpack unpacking expand expanded expanding conversion " +
        "convert converting duplicate duplicates repeated missing malformed mismatch violate " +
        "violates violation violations restriction restrictions before after during while when " +
        "which because unless instead between each exactly least most more validly correctly " +
        "incorrectly able unable reached reaches supportedness failed_precondition invalid_argument " +
        "api key keys authentication authorization authenticate unauthorized permission denied " +
        "expired expiration enabled disabled service billing quota exhausted found model models " +
        "token tokens bytes byte characters character length encoding encoded decoding decode " +
        "configuration config generation generationconfig generation_config responsejsonschema " +
        "response_json_schema response_schema responsemimetype response_mime_type candidatecount " +
        "candidate_count maxoutputtokens max_output_tokens thinkingconfig thinking_config " +
        "thinkinglevel thinking_level temperature topp top_p topk top_k generatecontent v1beta " +
        "gemini flash status code details fieldviolations field_violations description " +
        "badrequest bad_request invalidrequest invalid_request api_key_invalid " +
        "minitems maxitems min_items max_items minlength maxlength min_length max_length " +
        "additionalproperties additional_properties anyof oneof allof"
    ).split(" "))));

    public static Projection project(
        String message, Set<String> trustedPublicSchemaSymbols, List<String> privateLiterals
    ) {
        if (message == null || trustedPublicSchemaSymbols == null || privateLiterals == null) {
            throw new IllegalArgumentException("Projection arguments required");
        }
        boolean truncated = message.length() > INPUT_LIMIT;
        String value = message.substring(0, Math.min(message.length(), INPUT_LIMIT));
        for (String secret : privateLiterals) {
            if (secret != null && !secret.isBlank()) {
                value = Pattern.compile(Pattern.quote(secret), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(value).replaceAll(MARK);
            }
        }
        value = URL.matcher(value).replaceAll(MARK);
        value = EMAIL.matcher(value).replaceAll(MARK);
        value = UUID.matcher(value).replaceAll(MARK);
        Set<String> allowed = new HashSet<>(PUBLIC_WORDS);
        for (String symbol : trustedPublicSchemaSymbols) {
            if (symbol != null && IDENTIFIER.matcher(symbol).matches()
                && !KEY_PREFIX.matcher(symbol).find()) {
                allowed.add(symbol.toLowerCase(Locale.ROOT));
            }
        }
        StringBuilder output = new StringBuilder();
        int redactions = 0;
        var matcher = WORD.matcher(value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' '));
        while (matcher.find()) {
            String token = matcher.group();
            String normalized = token.toLowerCase(Locale.ROOT);
            boolean redact = false;
            String rendered;
            if (token.equals(MARK)) {
                rendered = REDACTED;
                redact = true;
            } else if (IDENTIFIER.matcher(token).matches() && allowed.contains(normalized)) {
                rendered = normalized;
            } else if (token.equals(" ")) {
                rendered = output.isEmpty() || output.charAt(output.length() - 1) == ' ' ? "" : " ";
            } else if (token.length() == 1 && ".,:;()[]{}'\"/\\-_$#=<>!?+".indexOf(token.charAt(0)) >= 0) {
                rendered = token;
            } else {
                rendered = REDACTED;
                redact = true;
            }
            if (output.length() + rendered.length() > LIMIT) {
                truncated = true;
                break;
            }
            output.append(rendered);
            if (redact) redactions++;
        }
        return new Projection(output.toString().trim(), redactions, truncated);
    }

    private SafeRejectionProjection() {}
}
