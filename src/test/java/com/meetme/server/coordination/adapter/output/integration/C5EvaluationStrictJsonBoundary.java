package com.meetme.server.coordination.adapter.output.integration;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import com.meetme.server.coordination.application.port.output.ScheduleDslStructuralResponseParser;
import com.meetme.server.coordination.application.port.output.NaturalLanguageParserException;
import com.meetme.server.coordination.application.port.output.ParserFailureKind;
import com.meetme.server.coordination.adapter.output.integration.StrictScheduleDslStructuralResponseParser;
import com.meetme.server.submission.domain.dsl.ScheduleDslStructuralEnvelope;
import com.meetme.server.submission.domain.ir.ScheduleEvidenceSnapshot;

/** Explicit isolated candidate boundary; old product readers and defaults are not changed. */
public final class C5EvaluationStrictJsonBoundary implements ScheduleDslStructuralResponseParser {
    private final ObjectMapper mapper;
    private final ObjectReader reader;
    private final StrictScheduleDslStructuralResponseParser previous;
    public C5EvaluationStrictJsonBoundary(ObjectMapper mapper) {
        this(mapper, false);
    }
    public C5EvaluationStrictJsonBoundary(ObjectMapper mapper, boolean allowScopedTermEvidence) {
        this(mapper, allowScopedTermEvidence, false);
    }
    public C5EvaluationStrictJsonBoundary(ObjectMapper mapper, boolean allowScopedTermEvidence, boolean allowPlaceConstraints) {
        this.mapper=mapper;
        reader=mapper.readerFor(Map.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        previous=new StrictScheduleDslStructuralResponseParser(mapper, allowScopedTermEvidence, allowPlaceConstraints);
    }
    @Override public boolean supportsResponseContractVersion(String version) {
        return "schedule-dsl-response-5".equals(version) || "schedule-dsl-evidence-response-1".equals(version) || "schedule-dsl-evidence-response-2".equals(version) || "schedule-dsl-evidence-response-3".equals(version) || "schedule-dsl-evidence-response-4".equals(version) || "schedule-dsl-evidence-response-5".equals(version);
    }
    @Override public String getSupportedResponseContractVersion() { return "schedule-dsl-response-5"; }
    @Override public ScheduleDslStructuralEnvelope parse(String raw, ScheduleEvidenceSnapshot snapshot,
            String response, String semantic, String ast, String vocabulary) {
        if (!supportsResponseContractVersion(response) || !"schedule-ir-product-6".equals(semantic) ||
                !"schedule-ast-1".equals(ast) || !"ko-time-terms-1".equals(vocabulary))
            throw new IllegalArgumentException("CANDIDATE_ROUTE_CONTRACT_MISMATCH");
        try {
            unicode(raw);
            byte[] frozen=raw.getBytes(StandardCharsets.UTF_8);
            if (frozen.length>262144) throw new IllegalArgumentException("RAW_RESPONSE_BYTE_LIMIT");
            Map<String,Object> decoded=reader.readValue(frozen);
            unicodeTree(decoded);
            if ("schedule-dsl-evidence-response-1".equals(response) || "schedule-dsl-evidence-response-2".equals(response) || "schedule-dsl-evidence-response-3".equals(response) || "schedule-dsl-evidence-response-4".equals(response) || "schedule-dsl-evidence-response-5".equals(response))
                return previous.parse(raw,snapshot,response,semantic,ast,vocabulary);
            Map<String,Object> compiled=C5DirectLocatorProducer.lower(decoded);
            String lowered=mapper.writeValueAsString(compiled);
            if (lowered.getBytes(StandardCharsets.UTF_8).length>262144) throw new IllegalArgumentException("LOWERED_RESPONSE_BYTE_LIMIT");
            return previous.parse(lowered,snapshot,response,semantic,ast,vocabulary);
        } catch (NaturalLanguageParserException e) { throw e; }
        catch (Exception e) { throw new NaturalLanguageParserException(ParserFailureKind.INVALID_RESPONSE,null,null); }
    }
    private static void unicodeTree(Object value) {
        if (value instanceof String s) unicode(s);
        else if(value instanceof Map<?,?> fields) fields.forEach((key,item)->{unicode((String)key);unicodeTree(item);});
        else if(value instanceof List<?> items) items.forEach(C5EvaluationStrictJsonBoundary::unicodeTree);
    }
    private static void unicode(String value) {
        if (value==null) throw new IllegalArgumentException("NULL_JSON");
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(Character.isHighSurrogate(c)){
                if(i+1>=value.length()||!Character.isLowSurrogate(value.charAt(i+1)))throw new IllegalArgumentException("INVALID_UNICODE");i++;
            }else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("INVALID_UNICODE");
        }
    }
}
