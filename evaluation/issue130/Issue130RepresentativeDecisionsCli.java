import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassificationContract;
import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierOptions;
import com.meetme.server.precheck.adapter.output.classification.OpenAiAmbiguityClassifier;
import com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider;
import com.meetme.server.precheck.application.port.output.*;
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties;
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate;
import com.meetme.server.shared.application.port.output.*;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import okhttp3.*;
import okio.Buffer;
import java.io.*;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** Evaluation-only tool. The production defaults, routing policy and prices remain unchanged. */
public final class Issue130RepresentativeDecisionsCli {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ObjectReader READER = JSON.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final AmbiguityClassificationContract CONTRACT = new AmbiguityClassificationContract(JSON);
    private static final URI ENDPOINT = URI.create("https://api.openai.com/v1/decisions");
    private static final int LIMIT = 16384;
    private static final Set<String> INPUT_KEYS = Set.of("raw_text", "reference_at", "time_zone", "locale", "search_start_date", "search_end_date");
    private static final String[] SOURCES = {
        "src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassificationContract.kt",
        "src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/OpenAiAmbiguityClassifier.kt",
        "src/main/kotlin/com/meetme/server/precheck/adapter/output/classification/AmbiguityClassifierOptions.kt",
        "src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/TwoStagePreSubmissionCheckProvider.kt"
    };
    public record Dependencies(OkHttpClient client, LongSupplier nanoTime, Clock clock, Map<String, String> environment) {
        public Dependencies { environment = Map.copyOf(environment); }
    }
    public static final class EvaluationException extends RuntimeException {
        private final String code;
        public EvaluationException(String code) { super(code, null, false, false); this.code = code; }
        public String code() { return code; }
    }
    private record Case(String id, Path wirePath, byte[] bytes, String sha, AmbiguityClassificationRequest request) {}
    private record Setup(JsonNode plan, Path output, List<Case> cases) {}

    public static Map<String,Object> prepareOne(Path input, Path output) throws Exception {
        try {
            byte[] bytes = wire(input);
            writeNew(output, bytes);
            return map("prepared", true, "requestBytes", bytes.length, "requestSha256", sha(bytes), "model", "gpt-6-luna");
        } catch (Exception failure) { throw safe(failure, "INVALID_REQUEST"); }
    }
    public static Map<String,Object> prepareIndex(Path inputIndex, Path preparedIndex) throws Exception {
        try {
            JsonNode index = readJson(inputIndex, 1048576);
            keys(index, Set.of("status", "input_contract", "key_network_access_permitted", "records"));
            require(text(index.path("status")).equals("INPUTS_FROZEN_WIRE_PREP_NOT_EXECUTION"), "INVALID_REQUEST");
            require(index.path("key_network_access_permitted").isBoolean() && !index.path("key_network_access_permitted").asBoolean(), "INVALID_REQUEST");
            JsonNode inputContract = index.path("input_contract");
            require(inputContract.isArray() && inputContract.size() == 6, "INVALID_REQUEST");
            Set<String> fields = new HashSet<>(); for (JsonNode n : inputContract) fields.add(text(n));
            require(fields.equals(INPUT_KEYS), "INVALID_REQUEST");
            JsonNode records = index.path("records"); require(records.isArray() && records.size() == 16, "INVALID_REQUEST");
            List<Map<String,Object>> prepared = new ArrayList<>(); List<Path> targets = new ArrayList<>(); List<byte[]> wires = new ArrayList<>();
            Set<String> ids = new HashSet<>(); Set<Path> outputs = new HashSet<>();
            canonical(preparedIndex); require(!Files.exists(preparedIndex, LinkOption.NOFOLLOW_LINKS), "OUTPUT_CONFLICT");
            for (JsonNode record : records) {
                keys(record, Set.of("opaque_call_id", "canonical_input_sha256", "canonical_input_bytes", "private_input_path", "private_wire_output_path"));
                String id = identity(record.path("opaque_call_id")); require(ids.add(id), "INVALID_REQUEST");
                Path source = canonical(Path.of(text(record.path("private_input_path"))));
                byte[] original = read(source, 1048576);
                require(original.length == integer(record.path("canonical_input_bytes")) && sha(original).equals(digest(record.path("canonical_input_sha256"))), "INVALID_REQUEST");
                Path target = canonical(Path.of(text(record.path("private_wire_output_path"))));
                require(outputs.add(target) && !target.equals(canonical(preparedIndex)) && !Files.exists(target, LinkOption.NOFOLLOW_LINKS), "OUTPUT_CONFLICT");
                byte[] payload = wire(source); targets.add(target); wires.add(payload);
                prepared.add(map("opaque_call_id", id, "request_path", target.toString(), "request_sha256", sha(payload), "request_bytes", payload.length));
            }
            for (int i = 0; i < targets.size(); i++) writeNew(targets.get(i), wires.get(i));
            writeJson(preparedIndex, map("status", "WIRES_PREPARED_NOT_EXECUTION", "records", prepared));
            return map("prepared", true, "caseCount", 16, "indexSha256", hash(preparedIndex));
        } catch (Exception failure) { throw safe(failure, "INVALID_REQUEST"); }
    }
    private static byte[] wire(Path input) throws Exception {
        AmbiguityClassificationRequest request = input(readJson(input, 1048576), Duration.ofSeconds(15));
        byte[] bytes = CONTRACT.requestBytes(request); require(bytes.length <= LIMIT, "INVALID_REQUEST"); return bytes;
    }
    private static AmbiguityClassificationRequest input(JsonNode n, Duration timeout) {
        keys(n, INPUT_KEYS);
        String raw = text(n.path("raw_text")), locale = text(n.path("locale"));
        require(!raw.isEmpty() && raw.codePointCount(0, raw.length()) <= 500 && raw.indexOf(0) < 0 && !locale.isBlank() && locale.indexOf(0) < 0, "INVALID_REQUEST");
        Instant reference = Instant.parse(text(n.path("reference_at")));
        ZoneId zone = ZoneId.of(text(n.path("time_zone"))); reference.atZone(zone);
        require(!new Locale.Builder().setLanguageTag(locale).build().getLanguage().isBlank(), "INVALID_REQUEST");
        LocalDate start = LocalDate.parse(text(n.path("search_start_date"))), end = LocalDate.parse(text(n.path("search_end_date")));
        require(!start.isAfter(end), "INVALID_REQUEST");
        return new AmbiguityClassificationRequest(raw, reference, zone, locale, start, end, timeout, null);
    }

    public static Map<String,Object> runSuite(Path planPath, Path preparedIndex, Path outputDirectory, Dependencies dependencies) throws Exception {
        long started = dependencies.nanoTime().getAsLong();
        try {
            long millis = Long.parseLong(Objects.requireNonNull(dependencies.environment().get("ISSUE130_SUITE_REMAINING_MILLIS")));
            require(millis > 0 && millis <= 300000, "CONFIGURATION");
            long deadline = started + millis * 1000000L;
            Setup setup = validate(planPath, preparedIndex, outputDirectory, dependencies.environment());
            remaining(deadline, dependencies.nanoTime());
            Path output = setup.output(); Files.createDirectories(output, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); canonical(output); canonical(output.resolve(".suite.lock"));
            try (FileChannel lockChannel = FileChannel.open(output.resolve(".suite.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
                Files.setPosixFilePermissions(output.resolve(".suite.lock"), PosixFilePermissions.fromString("rw-------"));
                FileLock held;
                try { held = lockChannel.tryLock(); } catch (OverlappingFileLockException conflict) { throw new EvaluationException("ALREADY_CONSUMED"); }
                require(held != null, "ALREADY_CONSUMED");
                try (FileLock lock = held) {
                    Map<String,Object> consumption = map("state", "FULL_N16_RESERVATION_HELD", "suiteId", text(setup.plan().path("suiteId")), "planSha256", hash(planPath), "maximumNewReservedUsd", "0.23068672", "priorHeldUsd", priorHeld(setup.plan()), "maximumCumulativeReservedUsd", cumulativeHeld(setup.plan()), "startedAt", dependencies.clock().instant().toString());
                    writeJson(output.resolve(".suite-consumed.json"), consumption);
                    List<Map<String,Object>> results = new ArrayList<>(); int physical = 0; boolean stopped = false;
                    for (int i = 0; i < 16; i++) {
                        Case item = setup.cases().get(i); String ordinal = String.format(Locale.ROOT, "%02d", i + 1);
                        Map<String,Object> result;
                        if (stopped) result = notRun(item);
                        else {
                            try { result = runCase(item, output, ordinal, deadline, dependencies); }
                            catch (Exception failure) { result = notRun(item); result.put("outcome", "STOP_API_OR_CONTRACT_OR_TIMEOUT"); result.put("failureCode", safe(failure, "OBSERVATION_FAILURE").code()); }
                            physical += ((Number) result.getOrDefault("classifierAttempts", 0)).intValue();
                            if (!Set.of("CLEAR_STOP", "B_REQUIRED_NOT_RUN").contains(result.get("outcome"))) stopped = true;
                        }
                        results.add(result);
                        try { writeJson(output.resolve(ordinal + "-result.json"), result); }
                        catch (Exception failure) { stopped = true; result.put("outcome", "STOP_API_OR_CONTRACT_OR_TIMEOUT"); result.put("failureCode", "OBSERVATION_FAILURE"); }
                    }
                    long finished = dependencies.nanoTime().getAsLong();
                    if (finished - deadline >= 0) stopped = true;
                    Map<String,Object> result = map("status", stopped ? "STOPPED" : "COMPLETED", "results", results, "physicalDecisionsCalls", physical, "physicalGenerationCalls", 0, "countCalls", 0, "maximumNewReservedUsd", "0.23068672", "maximumCumulativeReservedUsd", cumulativeHeld(setup.plan()), "startedNanos", started, "cutoffNanos", deadline, "finishedNanos", finished, "elapsedMs", (finished - started) / 1000000.0, "clock", "JVM_MONOTONIC_NOT_WALL_TIME");
                    writeJson(output.resolve("suite-result.json"), result);
                    return result;
                }
            }
        } catch (Exception failure) { throw safe(failure, "CONFIGURATION"); }
    }

    private static Setup validate(Path planPath, Path indexPath, Path outputPath, Map<String,String> environment) throws Exception {
        String planSha = hash(planPath);
        require(planSha.equals(environment.get("ISSUE130_FROZEN_PLAN_SHA256")) && planSha.equals(environment.get("ISSUE130_EXECUTE_FROZEN")), "CONFIGURATION");
        require(environment.get("OPENAI_API_KEY") != null && !environment.get("OPENAI_API_KEY").isBlank(), "CONFIGURATION");
        JsonNode p = readJson(planPath, 4194304);
        require(text(p.path("workItem")).equals("issue130-representative-decisions"), "CONFIGURATION"); UUID.fromString(text(p.path("suiteId")));
        Path root = canonical(Path.of(text(p.path("sourceRoot"))));
        String revision = text(p.path("sourceRevision"));
        Process git = new ProcessBuilder("git", "-C", root.toString(), "rev-parse", "HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String head = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim(); require(git.waitFor() == 0 && head.equals(revision), "CONFIGURATION");
        fileEvidence(p.path("sourceManifest")); JsonNode manifest = readJson(Path.of(text(p.path("sourceManifest").path("path"))), 4194304).path("files");
        require(manifest.isObject() && !manifest.isEmpty(), "CONFIGURATION");
        for (String relative : manifest.propertyNames()) { Path target = root.resolve(relative).normalize(); require(target.startsWith(root), "CONFIGURATION"); require(hash(target).equals(digest(manifest.path(relative))), "CONFIGURATION"); }
        JsonNode runtime = p.path("runtimeFiles"); require(runtime.isObject() && !runtime.isEmpty(), "CONFIGURATION");
        for (String name : runtime.propertyNames()) require(hash(Path.of(name)).equals(digest(runtime.path(name))), "CONFIGURATION");
        for (String source : SOURCES) require(runtime.has(root.resolve(source).toString()), "CONFIGURATION");
        for (String source : List.of("evaluation/issue130/Issue130RepresentativeDecisionsCli.java", "evaluation/issue130/run_representative_decisions.py")) require(runtime.has(root.resolve(source).toString()), "CONFIGURATION");
        require(hash(indexPath).equals(digest(p.path("preparedIndexSha256"))), "CONFIGURATION");
        require(text(p.path("endpoint")).equals(ENDPOINT.toString()) && text(p.path("model")).equals("gpt-6-luna"), "CONFIGURATION");
        require(p.path("minimumConfidence").isNumber() && p.path("minimumConfidence").doubleValue() == 0.8, "CONFIGURATION");
        require(p.path("operatingConfigurationChanged").isBoolean() && !p.path("operatingConfigurationChanged").asBoolean(), "CONFIGURATION");
        for (String field : List.of("caseCount", "maximumHttpAttempts")) require(integer(p.path(field)) == 16, "CONFIGURATION");
        for (String field : List.of("requestByteCap", "responseByteCap")) require(integer(p.path(field)) == LIMIT, "CONFIGURATION");
        require(integer(p.path("perCallSeconds")) == 15 && integer(p.path("suiteSeconds")) == 300 && integer(p.path("concurrency")) == 1, "CONFIGURATION");
        for (String field : List.of("countCalls", "generationCalls", "automaticRetries", "redirects", "fallbacks")) require(integer(p.path(field)) == 0, "CONFIGURATION");
        cost(p);
        Path frozenOutput = canonical(Path.of(text(p.path("outputDirectory")))), output = canonical(outputPath);
        require(frozenOutput.equals(output), "CONFIGURATION");
        require(!Files.exists(output.resolve(".suite-consumed.json"), LinkOption.NOFOLLOW_LINKS), "ALREADY_CONSUMED");
        JsonNode index = readJson(indexPath, 1048576); keys(index, Set.of("status", "records")); require(text(index.path("status")).equals("WIRES_PREPARED_NOT_EXECUTION"), "CONFIGURATION");
        JsonNode records = index.path("records"); require(records.isArray() && records.size() == 16, "CONFIGURATION");
        List<Case> cases = new ArrayList<>(); Set<String> ids = new HashSet<>(); Set<Path> paths = new HashSet<>();
        for (JsonNode record : records) {
            keys(record, Set.of("opaque_call_id", "request_path", "request_sha256", "request_bytes")); String id = identity(record.path("opaque_call_id")); require(ids.add(id), "CONFIGURATION");
            Path wirePath = canonical(Path.of(text(record.path("request_path")))); require(paths.add(wirePath), "CONFIGURATION"); byte[] bytes = read(wirePath, LIMIT); String sha = digest(record.path("request_sha256"));
            require(bytes.length == integer(record.path("request_bytes")) && sha(bytes).equals(sha), "CONFIGURATION");
            JsonNode outer = json(bytes); AmbiguityClassificationRequest request = input(json(text(outer.path("input")).getBytes(StandardCharsets.UTF_8)), Duration.ofSeconds(15));
            require(Arrays.equals(CONTRACT.requestBytes(request), bytes), "CONFIGURATION"); cases.add(new Case(id, wirePath, bytes, sha, request));
        }
        return new Setup(p, output, cases);
    }
    private static boolean environmentProxy(JsonNode plan) {
        if (!plan.has("transportMode")) return false;
        require(text(plan.path("transportMode")).equals("ENVIRONMENT_HTTPS_PROXY"), "CONFIGURATION");
        return true;
    }
    private static String priorHeld(JsonNode plan) { return environmentProxy(plan) ? "1.56254208" : "1.33185536"; }
    private static String cumulativeHeld(JsonNode plan) { return environmentProxy(plan) ? "1.79322880" : "1.56254208"; }
    private static void cost(JsonNode plan) throws Exception {
        boolean proxy = environmentProxy(plan);
        JsonNode c = plan.path("costProfile");
        require(c.isObject(), "CONFIGURATION");
        Map<String,String> expected = Map.of("inputUsdPerMillion", "0.10", "margin", "2.2", "perCallReservedUsd", "0.01441792", "priorHeldUsd", priorHeld(plan), "maximumNewReservedUsd", "0.23068672", "maximumCumulativeReservedUsd", cumulativeHeld(plan), "approvedAggregateUsd", "2.00");
        for (Map.Entry<String,String> e : expected.entrySet()) require(decimal(c.path(e.getKey())).compareTo(new BigDecimal(e.getValue())) == 0, "CONFIGURATION");
        require(integer(c.path("inputTokenProxy")) == 65536, "CONFIGURATION");
        require(c.path("officialInvoiceHardCapProven").isBoolean() && !c.path("officialInvoiceHardCapProven").asBoolean(), "CONFIGURATION");
        require(decimal(c.path("perCallReservedUsd")).multiply(BigDecimal.valueOf(16)).compareTo(decimal(c.path("maximumNewReservedUsd"))) == 0, "CONFIGURATION");
        require(decimal(c.path("priorHeldUsd")).add(decimal(c.path("maximumNewReservedUsd"))).compareTo(decimal(c.path("maximumCumulativeReservedUsd"))) == 0 && decimal(c.path("maximumCumulativeReservedUsd")).compareTo(decimal(c.path("approvedAggregateUsd"))) <= 0, "CONFIGURATION");
        fileEvidence(c.path("review"));
        if (proxy) {
            JsonNode reference = plan.path("priorReservation");
            require(Path.of(text(reference.path("path"))).isAbsolute(), "CONFIGURATION");
            fileEvidence(reference);
            JsonNode previous = readJson(Path.of(text(reference.path("path"))), 4194304);
            require(text(previous.path("status")).equals("FULL_SUITE_RESERVED_BEFORE_EXECUTION"), "CONFIGURATION");
            Map<String,String> retained = Map.of("previousRetainedReservationUsd", "1.33185536", "newReservationUsd", "0.23068672", "cumulativeReservationUsd", "1.56254208", "approvedAggregateUsd", "2.00");
            for (Map.Entry<String,String> entry : retained.entrySet()) require(decimal(previous.path(entry.getKey())).compareTo(new BigDecimal(entry.getValue())) == 0, "CONFIGURATION");
            require(integer(previous.path("refunds")) == 0, "CONFIGURATION");
        }
    }

    private static Map<String,Object> runCase(Case item, Path output, String ordinal, long suiteDeadline, Dependencies deps) throws Exception {
        long started = deps.nanoTime().getAsLong(), cutoff = started + Math.min(Duration.ofSeconds(15).toNanos(), remaining(suiteDeadline, deps.nanoTime()));
        String utc = deps.clock().instant().toString(); Map<String,Object> result = notRun(item);
        result.put("outcome", "STOP_API_OR_CONTRACT_OR_TIMEOUT");
        writeJson(output.resolve(ordinal + "-consumed.json"), map("state", "UNKNOWN_HELD", "callId", item.id(), "requestSha256", item.sha(), "perCallReservedUsd", "0.01441792", "startedAt", utc));
        AtomicInteger network = new AtomicInteger(), applications = new AtomicInteger(), probe = new AtomicInteger(); AtomicReference<Integer> status = new AtomicReference<>(); AtomicReference<byte[]> captured = new AtomicReference<>(); AtomicReference<AmbiguityClassificationResult> classified = new AtomicReference<>();
        OkHttpClient.Builder builder = deps.client().newBuilder();
        builder.eventListener(new okhttp3.EventListener() { @Override public void responseHeadersEnd(Call call, Response response) { status.set(response.code()); } });
        builder.interceptors().add(0, chain -> {
            requireIo(applications.incrementAndGet() == 1, "CONFIGURATION"); remainingIo(cutoff, deps.nanoTime());
            Request r = chain.request(); requireIo(r.method().equals("POST") && r.url().toString().equals(ENDPOINT.toString()), "CONFIGURATION");
            Buffer body = new Buffer(); Objects.requireNonNull(r.body()).writeTo(body); byte[] bytes = body.readByteArray(); requireIo(Arrays.equals(item.bytes(), bytes), "CONFIGURATION");
            Response response = chain.proceed(r); status.set(response.code());
            try { byte[] raw = response.peekBody(LIMIT + 1L).bytes(); requireIo(raw.length <= LIMIT, "INVALID_RESPONSE"); captured.set(raw); writeNew(output.resolve(ordinal + "-response.raw.json"), raw); remainingIo(cutoff, deps.nanoTime()); return response; }
            catch (Exception failure) { response.close(); throw new IOException("OBSERVATION_FAILURE"); }
        });
        builder.addInterceptor(chain -> { remainingIo(cutoff, deps.nanoTime()); return chain.proceed(chain.request()); });
        builder.networkInterceptors().add(0, chain -> {
            remainingIo(cutoff, deps.nanoTime()); requireIo(network.incrementAndGet() == 1, "CONFIGURATION");
            Request wire = chain.request(); Buffer snapshot = new Buffer(); Objects.requireNonNull(wire.body()).writeTo(snapshot);
            requireIo(wire.method().equals("POST") && wire.url().toString().equals(ENDPOINT.toString()) && Arrays.equals(snapshot.readByteArray(), item.bytes()), "CONFIGURATION");
            remainingIo(cutoff, deps.nanoTime()); return chain.proceed(wire);
        });
        OpenAiAmbiguityClassifier actual = new OpenAiAmbiguityClassifier(deps.environment().get("OPENAI_API_KEY"), JSON, new AmbiguityClassifierOptions(ENDPOINT, true, 0.8), builder.build(), null, null);
        AmbiguityClassificationPort classifier = req -> {
            AmbiguityClassificationResult value = actual.classify(req); classified.set(value);
            if (!value.getUsageComplete() || value.getInputTokens() == null || value.getInputTokens() > 65536) throw new AmbiguityClassificationException("INVALID_RESPONSE");
            return value;
        };
        String failureCode = null; boolean clear = false;
        try {
            PrecheckProviderResult terminal = pipeline(classifier, req -> { probe.incrementAndGet(); throw new PrecheckProviderException("CONFIGURATION"); }, deps.nanoTime()).check(request(item.request(), cutoff, deps.nanoTime()));
            clear = terminal.getVerdict() == PrecheckVerdict.CLEAR;
        } catch (PrecheckProviderException failure) { failureCode = failure.getFailureCode(); }
        catch (Exception failure) { failureCode = "INVALID_RESPONSE"; }
        long finished = deps.nanoTime().getAsLong();
        result.putAll(map("classifierAttempts", network.get(), "logicalBProbeEntries", probe.get(), "httpStatus", status.get(), "startedAt", utc, "finishedAt", deps.clock().instant().toString(), "startedNanos", started, "cutoffNanos", cutoff, "finishedNanos", finished, "elapsedMs", (finished - started) / 1000000.0, "clock", "JVM_MONOTONIC_NOT_WALL_TIME"));
        byte[] raw = captured.get();
        if (raw != null) {
            result.put("responseSha256", sha(raw)); result.put("responseBytes", raw.length);
            try {
                JsonNode envelope = CONTRACT.read(raw); AmbiguityClassificationContract.UsageSnapshot usage = CONTRACT.usage(envelope);
                result.put("inputTokens", usage.getInput()); result.put("outputTokens", usage.getOutput()); result.put("usageComplete", usage.getComplete());
                JsonNode answer = envelope.path("answers").path(0); String type = answer.path("type").isString() ? answer.path("type").asString() : null;
                if (Set.of("choice", "refusal").contains(type == null ? "" : type)) result.put("rawAnswerType", type);
                if (answer.path("choice").isString() && Set.of("CLEAR", "NEEDS_REVIEW", "INDETERMINATE").contains(answer.path("choice").asString())) result.put("rawChoice", answer.path("choice").asString());
                if (answer.path("confidence").isNumber()) { double value = answer.path("confidence").doubleValue(); if (Double.isFinite(value) && value >= 0 && value <= 1) result.put("rawConfidence", value); }
                if (classified.get() != null && answer.path("probabilities").isArray()) result.put("rawProbabilities", answer.path("probabilities"));
            } catch (RuntimeException ignored) { /* Strict duplicate/malformed envelopes have unknown usage. */ }
        }
        AmbiguityClassificationResult value = classified.get();
        if (value != null) result.putAll(map("assessment", value.getAssessment().name(), "reason", value.getReason() == null ? null : value.getReason().name(), "confidence", value.getConfidence()));
        boolean usageOkay = Boolean.TRUE.equals(result.get("usageComplete")) && result.get("inputTokens") instanceof Long input && input <= 65536;
        if (finished - cutoff >= 0 || finished - suiteDeadline >= 0) failureCode = "TIMEOUT";
        else if (value != null && value.getReason() == AmbiguityClassificationReason.REFUSED) result.put("outcome", "REFUSED_SAFE_INCOMPLETE");
        else if (usageOkay && clear) { result.put("outcome", "CLEAR_STOP"); failureCode = null; }
        else if (usageOkay && probe.get() == 1) { result.put("outcome", "B_REQUIRED_NOT_RUN"); failureCode = null; }
        result.put("failureCode", failureCode);
        return result;
    }

    private static TwoStagePreSubmissionCheckProvider pipeline(AmbiguityClassificationPort classifier, PreSubmissionCheckProvider generator, LongSupplier nano) {
        DurableAiBudgetPort denied = (DurableAiBudgetPort) Proxy.newProxyInstance(Issue130RepresentativeDecisionsCli.class.getClassLoader(), new Class<?>[]{DurableAiBudgetPort.class}, (p,m,a) -> { throw new EvaluationException("FINANCIAL_PORT_FORBIDDEN"); });
        AiCallBudgetPort legacy = (AiCallBudgetPort) Proxy.newProxyInstance(Issue130RepresentativeDecisionsCli.class.getClassLoader(), new Class<?>[]{AiCallBudgetPort.class}, (p,m,a) -> { throw new EvaluationException("FINANCIAL_PORT_FORBIDDEN"); });
        return new TwoStagePreSubmissionCheckProvider(classifier, generator, legacy, nano, new AiHttpDispatchGate(new AiDispatchGateProperties(true), denied, Clock.systemUTC(), nano));
    }
    private static PrecheckProviderRequest request(AmbiguityClassificationRequest r, long cutoff, LongSupplier nano) {
        UUID id = UUID.randomUUID(), room = UUID.randomUUID(), owner = UUID.randomUUID(); AiDispatchFingerprints f = AiDispatchFingerprints.INSTANCE;
        AiBudgetExecution execution = new AiBudgetExecution(room, AiBudgetExecutionKind.PRECHECK, id, 1, owner, 1, f.personalSource(r.getRawText()), f.personalContext(r.getReferenceAt(), r.getTimeZone(), r.getLocale(), r.getSearchStartDate(), r.getSearchEndDate()));
        return new PrecheckProviderRequest(id, r.getRawText(), r.getReferenceAt(), r.getTimeZone(), r.getLocale(), r.getSearchStartDate(), r.getSearchEndDate(), Duration.ofNanos(remaining(cutoff,nano)), 2048, room, 1, new AiProviderDispatchContext(execution, cutoff, null, 1));
    }
    private static Map<String,Object> notRun(Case item) { return map("callId", item.id(), "outcome", "NOT_RUN_AFTER_SUITE_STOP", "httpStatus", null, "failureCode", null, "inputTokens", null, "outputTokens", null, "usageComplete", false, "rawAnswerType", null, "rawChoice", null, "rawConfidence", null, "rawProbabilities", null, "assessment", null, "reason", null, "confidence", null, "requestSha256", item.sha(), "requestBytes", item.bytes().length, "responseSha256", null, "responseBytes", null, "classifierAttempts", 0, "logicalBProbeEntries", 0, "physicalBCalls", 0, "countCalls", 0); }

    public static OkHttpClient mainClient(Path planPath, Map<String,String> environment) throws Exception {
        try {
            JsonNode plan = readJson(planPath, 4194304);
            if (!environmentProxy(plan)) return new OkHttpClient();
            String value = environment.containsKey("https_proxy") ? environment.get("https_proxy") : environment.get("HTTPS_PROXY");
            require(value != null && !value.isEmpty() && value.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)), "CONFIGURATION");
            URI uri = new URI(value);
            require("http".equals(uri.getScheme()) && !uri.isOpaque() && uri.getHost() != null && !uri.getHost().isBlank(), "CONFIGURATION");
            require(uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null, "CONFIGURATION");
            require(uri.getRawPath() == null || uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"), "CONFIGURATION");
            require(uri.getRawAuthority() != null && !uri.getRawAuthority().endsWith(":"), "CONFIGURATION");
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            require(port > 0 && port <= 65535, "CONFIGURATION");
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved(uri.getHost(), port));
            return new OkHttpClient.Builder().proxy(proxy).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build();
        } catch (Exception failure) { throw safe(failure, "CONFIGURATION"); }
    }

    public static void main(String[] args) {
        try {
            Map<String,Object> value;
            if (args.length == 3 && args[0].equals("prepare-one")) value = prepareOne(Path.of(args[1]), Path.of(args[2]));
            else if (args.length == 3 && args[0].equals("prepare-index")) value = prepareIndex(Path.of(args[1]), Path.of(args[2]));
            else if (args.length == 4 && args[0].equals("run-suite")) value = runSuite(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), new Dependencies(mainClient(Path.of(args[1]), System.getenv()), System::nanoTime, Clock.systemUTC(), System.getenv()));
            else throw new EvaluationException("CONFIGURATION");
            System.out.println(JSON.writeValueAsString(value));
        } catch (Exception failure) { System.out.println(JSON.writeValueAsString(map("accepted", false, "failureCode", safe(failure, "CONFIGURATION").code()))); System.exit(2); }
    }
    private static Path canonical(Path path) throws IOException {
        Path absolute = path.toAbsolutePath(); require(absolute.equals(absolute.normalize()), "CONFIGURATION");
        Path cursor = absolute.getRoot();
        for (Path component : absolute) { cursor = cursor.resolve(component); require(!Files.isSymbolicLink(cursor), "CONFIGURATION"); }
        return absolute;
    }
    private static byte[] read(Path path, int limit) throws IOException { canonical(path); try (InputStream in = Files.newInputStream(path)) { byte[] bytes = in.readNBytes(limit+1); require(bytes.length<=limit,"INVALID_REQUEST"); return bytes; } }
    private static JsonNode readJson(Path p,int limit) throws Exception { return json(read(p,limit)); }
    private static JsonNode json(byte[] raw) throws Exception { String decoded=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString(); return READER.readValue(decoded); }
    private static void writeNew(Path path,byte[] raw) throws IOException { canonical(path); try (var channel=Files.newByteChannel(path,Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))); OutputStream out=Channels.newOutputStream(channel)) { out.write(raw); } }
    private static void writeJson(Path path,Object value) throws IOException { writeNew(path,JSON.writeValueAsBytes(value)); }
    private static String hash(Path path) throws Exception { canonical(path); MessageDigest md=MessageDigest.getInstance("SHA-256"); try(InputStream in=Files.newInputStream(path)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return HexFormat.of().formatHex(md.digest()); }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static String text(JsonNode n) { require(n.isString(),"CONFIGURATION");return n.asString(); }
    private static String digest(JsonNode n) { String s=text(n);require(s.matches("[0-9a-f]{64}"),"CONFIGURATION");return s; }
    private static String identity(JsonNode n) { String s=text(n);require(s.matches("[A-Za-z0-9_-]{1,128}"),"INVALID_REQUEST");return s; }
    private static long integer(JsonNode n) { require(n.isIntegralNumber()&&n.canConvertToLong(),"CONFIGURATION");return n.longValue(); }
    private static BigDecimal decimal(JsonNode n) { String s=text(n);require(s.length()<=64&&s.matches("[0-9]+(?:\\.[0-9]{1,8})?"),"CONFIGURATION");return new BigDecimal(s); }
    private static void keys(JsonNode n,Set<String> expected) { Set<String> names = new HashSet<>(); n.propertyNames().forEach(names::add); require(n.isObject()&&names.equals(expected),"INVALID_REQUEST"); }
    private static void fileEvidence(JsonNode n) throws Exception { require(n.isObject(),"CONFIGURATION");require(hash(Path.of(text(n.path("path")))).equals(digest(n.path("sha256"))),"CONFIGURATION"); }
    private static long remaining(long deadline,LongSupplier nano) { long n=deadline-nano.getAsLong();require(n>0,"TIMEOUT");return n; }
    private static void remainingIo(long deadline,LongSupplier nano) throws IOException { if(deadline-nano.getAsLong()<=0)throw new IOException("TIMEOUT"); }
    private static void requireIo(boolean yes,String code) throws IOException { if(!yes)throw new IOException(code); }
    private static void require(boolean yes,String code) { if(!yes)throw new EvaluationException(code); }
    private static EvaluationException safe(Exception failure,String fallback) { return failure instanceof EvaluationException e?e:new EvaluationException(fallback); }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> value=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);return value; }
}
