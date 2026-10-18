import com.meetme.server.precheck.adapter.output.classification.AmbiguityClassificationContract;
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderContract;
import com.meetme.server.precheck.adapter.output.provider.TwoStagePreSubmissionCheckProvider;
import com.meetme.server.precheck.application.port.output.*;
import com.meetme.server.shared.adapter.output.aihttp.AiDispatchGateProperties;
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate;
import com.meetme.server.shared.application.port.output.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic LIVE-KO-03 only. No key lookup or HTTP implementation. Only explicit run-live executes a parent-supplied callback. */
public final class Issue123TwoStageSmokeHarness {
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static final AmbiguityClassificationContract A = new AmbiguityClassificationContract(JSON);
  private static final PrecheckProviderContract B = new PrecheckProviderContract(JSON);
  private static final String MODEL = "gemini-3.8-flash";
  private static final String RAW = "10월 24일 토요일 8시부터 9시까지 가능해요.";
  private static final UUID CHECK = UUID.fromString("00000000-0000-0000-0000-000000012303");
  private static final UUID ROOM = UUID.fromString("00000000-0000-0000-0000-000000012300");
  private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000012304");
  private static final double FIXTURE_THRESHOLD = 0.8; // Existing synthetic fixture; never an operational-policy selection.
  private static final Path DEFAULT_A_REQUEST = Path.of(".codex/tdd-evidence/issue123-minimal-smoke/LIVE-KO-03-request.json");
  private static final int LIMIT = 16 * 1024;
  private record Fixture(Path originalA, String raw, Instant reference, ZoneId zone, String locale, LocalDate start, LocalDate end) {}

  public static void main(String[] args) {
    try {
      Object result = switch (args[0]) {
        case "prepare" -> prepare(fixture(Path.of(args[1])), Path.of(args[2]));
        case "routeA" -> routeA(fixture(args.length > 2 ? Path.of(args[2]) : DEFAULT_A_REQUEST), Path.of(args[1]));
        case "count" -> countResult(Path.of(args[1]));
        case "parseB" -> parseBResult(fixture(Path.of(args[1])), Path.of(args[2]));
        case "verify" -> verify(fixture(Path.of(args[1])), Path.of(args[2]), Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
        case "run-live" -> runLive(fixture(Path.of(args[1])), Path.of(args[2]), Path.of(args[3]));
        default -> throw new IllegalArgumentException("UNSUPPORTED_COMMAND");
      };
      System.out.println(JSON.writeValueAsString(result));
    } catch (Throwable failure) {
      Map<String,Object> result = map("accepted",false,"failureCode", failureCode(failure),"exceptionType",failure.getClass().getSimpleName());
      System.out.println(JSON.writeValueAsString(result));
      System.exit(2);
    }
  }

  private static Fixture fixture(Path aRequest) throws Exception {
    JsonNode outer = JSON.readTree(read(aRequest));
    JsonNode input = JSON.readTree(outer.get("input").asString());
    Set<String> keys = new HashSet<>(); input.propertyNames().forEach(keys::add);
    require(keys.equals(Set.of("raw_text","reference_at","time_zone","locale","search_start_date","search_end_date")));
    Fixture f = new Fixture(aRequest,input.get("raw_text").asString(),Instant.parse(input.get("reference_at").asString()),ZoneId.of(input.get("time_zone").asString()),input.get("locale").asString(),LocalDate.parse(input.get("search_start_date").asString()),LocalDate.parse(input.get("search_end_date").asString()));
    require(RAW.equals(f.raw) && f.reference.equals(Instant.parse("2026-10-18T04:00:00Z")) && f.zone.equals(ZoneId.of("Asia/Seoul")) && f.locale.equals("ko-KR") && f.start.equals(LocalDate.of(2026,10,24)) && f.end.equals(LocalDate.of(2026,10,25)));
    require(outer.equals(JSON.readTree(A.requestBytes(aRequest(f,Duration.ofSeconds(15),null)))));
    return f;
  }

  private static Map<String,Object> prepare(Fixture f, Path directory) throws Exception {
    directory=directory.toAbsolutePath().normalize();
    Files.createDirectories(directory);
    requireFreshExecution(directory);
    byte[] generation = B.requestBytes(request(f),true);
    byte[] count = B.countRequestBytes(generation,MODEL);
    require(generation.length <= LIMIT && count.length <= LIMIT);
    JsonNode generated = JSON.readTree(generation);
    JsonNode config = generated.get("generationConfig");
    require(config.get("candidateCount").asInt()==1 && config.get("maxOutputTokens").asInt()==2048 && config.get("thinkingConfig").get("thinkingLevel").asString().equals("LOW"));
    JsonNode decoded = JSON.readTree(generated.get("contents").get(0).get("parts").get(0).get("text").asString());
    require(decoded.get("raw_text").asString().equals(f.raw) && decoded.get("input_ref").asString().equals(CHECK.toString()));
    writeExact(directory.resolve("a-request.json"),read(f.originalA));
    writeExact(directory.resolve("generation-request.json"),generation);
    writeExact(directory.resolve("count-request.json"),count);
    Map<String,Object> manifest=map("caseId","LIVE-KO-03","checkId",CHECK.toString(),"originalARequestSha256",sha(read(f.originalA)),"rawTextSha256",sha(f.raw.getBytes(StandardCharsets.UTF_8)),"aRequestPath",directory.resolve("a-request.json").toAbsolutePath().toString(),"classifyResponsePath",directory.resolve("classify-response.raw.json").toAbsolutePath().toString(),"countRequestPath",directory.resolve("count-request.json").toAbsolutePath().toString(),"countResponsePath",directory.resolve("count-response.raw.json").toAbsolutePath().toString(),"generationRequestPath",directory.resolve("generation-request.json").toAbsolutePath().toString(),"generationResponsePath",directory.resolve("generation-response.raw.json").toAbsolutePath().toString(),"generationRequestBytes",generation.length,"generationRequestSha256",sha(generation),"countRequestBytes",count.length,"countRequestSha256",sha(count),"model",MODEL,"maxOutputTokens",2048,"thinkingLevel","LOW","candidateCount",1,"maximumInputPreflightTokens",4096,"validationOnlyConfidenceThreshold",FIXTURE_THRESHOLD,"productionConfidencePolicySelected",false,"networkCallsByPrepare",0);
    write(directory.resolve("manifest.json"),manifest);
    return manifest;
  }

  private static AmbiguityClassificationResult parseA(Path response) throws Exception {
    AmbiguityClassificationResult parsed=parseAUnrestricted(response);
    requireAUsage(parsed);
    return parsed;
  }

  private static AmbiguityClassificationResult parseAUnrestricted(Path response) throws Exception {
    JsonNode envelope=A.read(read(response));
    return A.parse(envelope,A.usage(envelope),FIXTURE_THRESHOLD);
  }

  private static void requireAUsage(AmbiguityClassificationResult parsed) {
    Long input=parsed.getInputTokens(), output=parsed.getOutputTokens();
    if (!parsed.getUsageComplete() || input==null || input<0 || input>65536 || output==null || output!=0)
      throw new AmbiguityClassificationException("INVALID_RESPONSE");
  }

  private static Map<String,Object> routeA(Fixture f,Path response) throws Exception {
    JsonNode envelope=A.read(read(response));
    AmbiguityClassificationResult classified=A.parse(envelope,A.usage(envelope),FIXTURE_THRESHOLD);
    AtomicInteger aCalls=new AtomicInteger(), bCalls=new AtomicInteger();
    Map<String,Object> result=aResult(classified);
    try {
      requireAUsage(classified);
      result.put("evaluationUsageGatePassed",true);
      pipeline(req -> { aCalls.incrementAndGet(); return classified; }, req -> { bCalls.incrementAndGet(); return clear(); }).check(request(f));
      result.put("pipelineFailure",null);
    } catch (AmbiguityClassificationException failure) { result.put("accepted",false); result.put("evaluationUsageGatePassed",false); result.put("pipelineFailure",failure.getFailureCode()); }
    catch (PrecheckProviderException failure) { result.put("pipelineFailure",failure.getFailureCode()); }
    result.put("shouldGenerate",bCalls.get()==1);
    result.put("actualTwoStageClassifierPortCalls",aCalls.get());
    result.put("actualTwoStageSyntheticGeneratorProbeCalls",bCalls.get());
    result.put("routeDerivedByActualTwoStage",true);
    result.put("actualHttpCalls",0);
    return result;
  }

  private static long parseCount(Path response) throws Exception {
    long count=B.parseCount(read(response));
    if (count>4096) throw new PrecheckProviderException("INVALID_REQUEST");
    return count;
  }
  private static Map<String,Object> countResult(Path response) throws Exception { return map("accepted",true,"totalTokens",parseCount(response),"generationAdmissionAllowed",true,"actualHttpCalls",0); }

  private static PrecheckProviderResult parseB(Fixture f,Path response) throws Exception {
    byte[] bytes=read(response);
    PrecheckProviderContract.UsageObservation usage=B.observeUsage(bytes);
    if (usage.getInvalid()) throw new PrecheckProviderException("INVALID_RESPONSE");
    PrecheckProviderResult parsed=B.parse(bytes,request(f));
    if (parsed.getInputTokens()!=null && parsed.getInputTokens()>4096) throw new PrecheckProviderException("INVALID_RESPONSE");
    return new PrecheckProviderResult(parsed.getVerdict(),parsed.getPrompt(),parsed.getSourceExcerpt(),parsed.getChoices(),parsed.getInputTokens(),parsed.getOutputTokens(),parsed.getResponseBytes(),"GEMINI",MODEL);
  }
  private static Map<String,Object> parseBResult(Fixture f,Path response) throws Exception {
    Map<String,Object> result=bResult(parseB(f,response));
    result.put("returnedUsage",bUsage(response)); return result;
  }
  private static Map<String,Object> bUsage(Path response) throws Exception {
    PrecheckProviderContract.UsageObservation usage=B.observeUsage(read(response));
    return map("inputTokens",usage.getTokens().get("INPUT"),"candidateTokens",usage.getTokens().get("CANDIDATE"),"thoughtTokens",usage.getTokens().get("THOUGHT"),"invalid",usage.getInvalid());
  }

  private static Map<String,Object> verify(Fixture f,Path aResponse,Path countResponse,Path bResponse,Path directory) throws Exception {
    prepare(f,directory);
    AmbiguityClassificationResult classified=parseA(aResponse);
    long counted=parseCount(countResponse);
    PrecheckProviderResult generated=parseB(f,bResponse);
    AtomicInteger aCalls=new AtomicInteger(), bCalls=new AtomicInteger();
    PrecheckProviderResult result=pipeline(req -> { aCalls.incrementAndGet(); return classified; },req -> { bCalls.incrementAndGet(); require(req.getRawText().equals(f.raw)); return generated; }).check(request(f));
    require(aCalls.get()==1 && bCalls.get()==1 && result.equals(generated));
    Map<String,Object> report=map("accepted",true,"mode","RECORDED_RESPONSES_OFFLINE_REPLAY","normalizedA",aResult(classified),"preflightTotalTokens",counted,"parsedB",bResult(generated),"returnedBUsage",bUsage(bResponse),"actualTwoStageResult",bResult(result),"actualTwoStageClassifierPortCalls",aCalls.get(),"actualTwoStageGeneratorPortCalls",bCalls.get(),"actualHttpCalls",0,"productionSdkTransportClaimed",false);
    write(directory.resolve("offline-two-stage.json"),report); return report;
  }

  /** Only the parent explicitly invokes this. Python owns approved exact-wire HTTP, keys and financial gate. */
  private static Map<String,Object> runLive(Fixture f,Path directory,Path callback) throws Exception {
    directory=directory.toAbsolutePath().normalize();
    prepare(f,directory); require(Files.isRegularFile(callback));
    final Path liveDirectory=directory;
    AtomicInteger aCalls=new AtomicInteger(),countCalls=new AtomicInteger(),bCalls=new AtomicInteger();
    List<Map<String,Object>> exchanges=new ArrayList<>();
    AmbiguityClassificationResult[] aRecord={null};
    long[] countRecord={-1};
    PrecheckProviderResult result;
    try {
      result=pipeline(req -> {
      try {
        require(aCalls.incrementAndGet()==1);
        callback(callback,"CLASSIFY",liveDirectory.resolve("a-request.json"),liveDirectory.resolve("classify-response.raw.json"),req.getTimeout(),exchanges);
        aRecord[0]=parseAUnrestricted(liveDirectory.resolve("classify-response.raw.json"));
        requireAUsage(aRecord[0]); return aRecord[0];
      } catch (Exception failure) { throw safeA(failure); }
    },req -> {
      long deadline=System.nanoTime()+req.getTimeout().toNanos();
      try {
        require(req.getRawText().equals(f.raw) && req.getCheckId().equals(CHECK));
        require(countCalls.incrementAndGet()==1);
        callback(callback,"COUNT",liveDirectory.resolve("count-request.json"),liveDirectory.resolve("count-response.raw.json"),remaining(deadline),exchanges);
        countRecord[0]=parseCount(liveDirectory.resolve("count-response.raw.json"));
        require(bCalls.incrementAndGet()==1);
        callback(callback,"GENERATE",liveDirectory.resolve("generation-request.json"),liveDirectory.resolve("generation-response.raw.json"),remaining(deadline),exchanges);
        return parseB(f,liveDirectory.resolve("generation-response.raw.json"));
      } catch (Exception failure) { throw safeB(failure); }
    }).check(request(f));
    } catch (Exception failure) {
      Path bResponse=liveDirectory.resolve("generation-response.raw.json");
      write(liveDirectory.resolve("live-two-stage.json"),map("accepted",false,"mode","ACTUAL_TWO_STAGE_COORDINATES_BOUNDED_PYTHON_CALLBACKS","failureCode",failureCode(failure),"normalizedA",aRecord[0]==null?null:aResult(aRecord[0]),"classifierCallbacks",aCalls.get(),"countCallbacks",countCalls.get(),"generationCallbacks",bCalls.get(),"preflightTotalTokens",countRecord[0]<0?null:countRecord[0],"returnedBUsage",Files.isRegularFile(bResponse)?bUsage(bResponse):null,"exchanges",exchanges,"productionSdkTransportClaimed",false,"productionActivationClaimed",false));
      throw failure;
    }
    Map<String,Object> report=map("accepted",true,"mode","ACTUAL_TWO_STAGE_COORDINATES_BOUNDED_PYTHON_CALLBACKS","normalizedA",aResult(aRecord[0]),"preflightTotalTokens",countRecord[0]<0?null:countRecord[0],"actualTwoStageResult",bResult(result),"classifierCallbacks",aCalls.get(),"countCallbacks",countCalls.get(),"generationCallbacks",bCalls.get(),"returnedBUsage",bCalls.get()==1?bUsage(liveDirectory.resolve("generation-response.raw.json")):null,"exchanges",exchanges,"productionSdkTransportClaimed",false,"productionActivationClaimed",false,"operationalThresholdSelected",false);
    write(liveDirectory.resolve("live-two-stage.json"),report); return report;
  }

  private static void callback(Path script,String operation,Path request,Path response,Duration timeout,List<Map<String,Object>> records) throws Exception {
    long nanos=timeout.toNanos(); if(nanos<=0) throw new PrecheckProviderException("TIMEOUT");
    Path metadata=response.resolveSibling(response.getFileName()+".callback-metadata.json");
    Files.createFile(metadata);
    long started=System.nanoTime();
    Process process=new ProcessBuilder("python3",script.toString(),operation,request.toAbsolutePath().toString(),response.toAbsolutePath().toString(),Long.toString(Math.max(1,nanos/1_000_000)))
        .redirectOutput(ProcessBuilder.Redirect.appendTo(metadata.toFile())).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    if(!process.waitFor(Math.max(1,nanos-(System.nanoTime()-started)),TimeUnit.NANOSECONDS)) {
      process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); throw new PrecheckProviderException("TIMEOUT");
    }
    if(process.exitValue()!=0 || !Files.isRegularFile(response) || !Files.isRegularFile(metadata) || Files.size(metadata)>LIMIT) throw new PrecheckProviderException("CONFIGURATION");
    JsonNode returned=JSON.readTree(read(metadata));
    require(returned.isObject());
    records.add(map("operation",operation,"requestSha256",sha(read(request)),"responseSha256",sha(read(response)),"elapsedNanos",System.nanoTime()-started,"callbackMetadata",returned));
  }

  private static TwoStagePreSubmissionCheckProvider pipeline(AmbiguityClassificationPort classifier,PreSubmissionCheckProvider generator) {
    AtomicInteger forbidden=new AtomicInteger();
    DurableAiBudgetPort noAuthority=(DurableAiBudgetPort)Proxy.newProxyInstance(Issue123TwoStageSmokeHarness.class.getClassLoader(),new Class<?>[]{DurableAiBudgetPort.class},(proxy,method,args)->{forbidden.incrementAndGet();throw new AssertionError("HARNESS_HAS_NO_DURABLE_AUTHORITY");});
    AiCallBudgetPort noLegacy=(AiCallBudgetPort)Proxy.newProxyInstance(Issue123TwoStageSmokeHarness.class.getClassLoader(),new Class<?>[]{AiCallBudgetPort.class},(proxy,method,args)->{forbidden.incrementAndGet();throw new AssertionError("HARNESS_HAS_NO_LEGACY_AUTHORITY");});
    AiHttpDispatchGate gate=new AiHttpDispatchGate(new AiDispatchGateProperties(true),noAuthority,Clock.systemUTC(),System::nanoTime);
    return new TwoStagePreSubmissionCheckProvider(classifier,generator,noLegacy,System::nanoTime,gate);
  }
  private static PrecheckProviderRequest request(Fixture f) {
    AiDispatchFingerprints fp=AiDispatchFingerprints.INSTANCE;
    AiBudgetExecution execution=new AiBudgetExecution(ROOM,AiBudgetExecutionKind.PRECHECK,CHECK,1,OWNER,1,fp.personalSource(f.raw),fp.personalContext(f.reference,f.zone,f.locale,f.start,f.end));
    AiProviderDispatchContext context=new AiProviderDispatchContext(execution,System.nanoTime()+Duration.ofSeconds(15).toNanos(),null,1);
    return new PrecheckProviderRequest(CHECK,f.raw,f.reference,f.zone,f.locale,f.start,f.end,Duration.ofSeconds(15),2048,ROOM,1,context);
  }
  private static AmbiguityClassificationRequest aRequest(Fixture f,Duration timeout,AiProviderDispatchContext context) { return new AmbiguityClassificationRequest(f.raw,f.reference,f.zone,f.locale,f.start,f.end,timeout,context); }
  private static PrecheckProviderResult clear() { return new PrecheckProviderResult(PrecheckVerdict.CLEAR,null,null,List.of(),null,null,null,null,null); }
  private static Map<String,Object> aResult(AmbiguityClassificationResult r) { return map("accepted",true,"assessment",r.getAssessment().name(),"reason",r.getReason()==null?null:r.getReason().name(),"confidence",r.getConfidence(),"inputTokens",r.getInputTokens(),"outputTokens",r.getOutputTokens(),"usageComplete",r.getUsageComplete(),"provider",r.getProvider(),"model",r.getModel(),"validationOnlyConfidenceThreshold",FIXTURE_THRESHOLD,"operationalThresholdSelected",false); }
  private static Map<String,Object> bResult(PrecheckProviderResult r) {
    List<Object> choices=new ArrayList<>(); r.getChoices().forEach(c->choices.add(map("id",c.getId(),"label",c.getLabel(),"proposedText",c.getProposedText())));
    return map("accepted",true,"verdict",r.getVerdict().name(),"prompt",r.getPrompt(),"sourceExcerpt",r.getSourceExcerpt(),"choices",choices,"inputTokens",r.getInputTokens(),"outputTokens",r.getOutputTokens(),"responseBytes",r.getResponseBytes(),"provider",r.getProvider(),"model",r.getModel());
  }
  private static AmbiguityClassificationException safeA(Exception failure) { return new AmbiguityClassificationException(failureCode(failure)); }
  private static PrecheckProviderException safeB(Exception failure) { return new PrecheckProviderException(failureCode(failure)); }
  private static String failureCode(Throwable failure) { if(failure instanceof PrecheckProviderException e)return e.getFailureCode(); if(failure instanceof AmbiguityClassificationException e)return e.getFailureCode(); return "CONFIGURATION"; }
  private static Duration remaining(long cutoff) { long nanos=cutoff-System.nanoTime(); if(nanos<=0)throw new PrecheckProviderException("TIMEOUT"); return Duration.ofNanos(nanos); }
  private static byte[] read(Path path) throws Exception { if(Files.size(path)>LIMIT)throw new IllegalArgumentException("BODY_LIMIT"); return Files.readAllBytes(path); }
  private static void requireFreshExecution(Path directory) throws Exception {
    for(String name:List.of("CLASSIFY-consumed.json","COUNT-consumed.json","GENERATE-consumed.json","live-two-stage.json",
        "classify-response.raw.json","count-response.raw.json","generation-response.raw.json",
        "a-response.json","count-response.json","generation-response.json")) {
      require(!Files.exists(directory.resolve(name),LinkOption.NOFOLLOW_LINKS));
      require(!Files.exists(directory.resolve(name+".callback-metadata.json"),LinkOption.NOFOLLOW_LINKS));
    }
  }
  private static void writeExact(Path path,byte[] bytes) throws Exception {
    if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
      require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && Arrays.equals(Files.readAllBytes(path),bytes));
    } else Files.write(path,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
  }
  private static void write(Path path,Object value) throws Exception { writeExact(path,JSON.writeValueAsBytes(value)); }
  private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private static void require(boolean condition) { if(!condition)throw new IllegalArgumentException("FIXED_SYNTHETIC_CONTRACT"); }
  private static Map<String,Object> map(Object... pairs) { Map<String,Object> r=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)r.put((String)pairs[i],pairs[i+1]); return r; }
}
