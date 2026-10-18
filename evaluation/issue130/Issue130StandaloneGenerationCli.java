import com.meetme.server.precheck.adapter.output.provider.*;
import com.meetme.server.config.GeminiProperties;
import com.meetme.server.shared.adapter.output.aihttp.AiHttpProviderStatusException;


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
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.LongSupplier;

/** Evaluation-only, fixed two-case standalone B observations. No production activation. */
public final class Issue130StandaloneGenerationCli {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final ObjectReader READER = JSON.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY, DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final PrecheckProviderContract CONTRACT = new PrecheckProviderContract(JSON);
    private static final int LIMIT = 16384;
    private static final String MODEL = "gemini-3.8-flash", HOST = "generativelanguage.googleapis.com";
    private static final String COUNT = "/v1beta/models/"+MODEL+":countTokens", GENERATE = "/v1beta/models/"+MODEL+":generateContent";
    private static final Set<String> INPUT_KEYS = Set.of("raw_text", "reference_at", "time_zone", "locale", "search_start_date", "search_end_date");
    private static final String[] SOURCES = {
        "src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderContract.kt",
        "src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/GeminiPreSubmissionCheckProvider.kt",
        "src/main/kotlin/com/meetme/server/precheck/adapter/output/provider/PrecheckProviderRuntimeProperties.kt",
        "src/main/kotlin/com/meetme/server/precheck/application/port/output/PreSubmissionCheckProvider.kt",
        "evaluation/issue130/Issue130StandaloneGenerationCli.java", "evaluation/issue130/run_standalone_generation.py"
    };
    public record Dependencies(OkHttpClient client, LongSupplier nanoTime, Clock clock, Map<String,String> environment) {
        public Dependencies { environment = Map.copyOf(environment); }
    }
    public static final class EvaluationException extends RuntimeException {
        private final String code;
        public EvaluationException(String code) { super(code, null, false, false); this.code = code; }
        public String code() { return code; }
    }
    private record Case(String id, PrecheckProviderRequest request, byte[] generation, byte[] count) {}
    private record Setup(JsonNode plan, Path output, List<Case> cases) {}
    private static boolean environmentProxy(JsonNode plan) {
        if (!plan.has("transportMode")) return false;
        require(text(plan.path("transportMode")).equals("ENVIRONMENT_HTTPS_PROXY"), "CONFIGURATION"); return true;
    }
    private static UUID reference(String id) { return UUID.nameUUIDFromBytes(("issue130-standalone-b-v1:"+id).getBytes(StandardCharsets.UTF_8)); }
    private static PrecheckProviderRequest input(String id, JsonNode n, Duration timeout) {
        keys(n, INPUT_KEYS);
        String raw=text(n.path("raw_text")), locale=text(n.path("locale"));
        require(!raw.isEmpty() && raw.codePointCount(0,raw.length())<=500 && raw.indexOf(0)<0 && !locale.isBlank() && locale.indexOf(0)<0,"INVALID_REQUEST");
        Instant at=Instant.parse(text(n.path("reference_at"))); ZoneId zone=ZoneId.of(text(n.path("time_zone"))); at.atZone(zone);
        require(!new Locale.Builder().setLanguageTag(locale).build().getLanguage().isBlank(),"INVALID_REQUEST");
        LocalDate start=LocalDate.parse(text(n.path("search_start_date"))),end=LocalDate.parse(text(n.path("search_end_date")));
        require(!start.isAfter(end),"INVALID_REQUEST");
        return new PrecheckProviderRequest(reference(id),raw,at,zone,locale,start,end,timeout,2048,null,null,null);
    }
    public static Map<String,Object> prepareIndex2(Path inputIndex, Path preparedIndex) throws Exception {
        try {
            JsonNode index=readJson(inputIndex,1048576); keys(index,Set.of("status","input_contract","key_network_access_permitted","records"));
            require(text(index.path("status")).equals("INPUTS_FROZEN_WIRE_PREP_NOT_EXECUTION") && index.path("key_network_access_permitted").isBoolean() && !index.path("key_network_access_permitted").asBoolean(),"INVALID_REQUEST");
            JsonNode contract=index.path("input_contract"); require(contract.isArray() && contract.size()==6,"INVALID_REQUEST"); Set<String> names=new HashSet<>();for(JsonNode n:contract)names.add(text(n));require(names.equals(INPUT_KEYS),"INVALID_REQUEST");
            JsonNode records=index.path("records"); require(records.isArray() && records.size()==2,"INVALID_REQUEST");
            List<Map<String,Object>> prepared=new ArrayList<>(); List<Path> targets=new ArrayList<>(); List<byte[]> bytes=new ArrayList<>();
            Set<Path> inputs=new HashSet<>(Set.of(canonical(inputIndex),canonical(preparedIndex))),outputs=new HashSet<>(Set.of(canonical(preparedIndex))); Set<String> ids=new HashSet<>();
            require(!Files.exists(preparedIndex,LinkOption.NOFOLLOW_LINKS),"OUTPUT_CONFLICT");
            for(JsonNode record:records) {
                keys(record,Set.of("opaque_call_id","canonical_input_sha256","canonical_input_bytes","private_input_path","private_wire_output_path"));
                String id=identity(record.path("opaque_call_id"));require(ids.add(id),"INVALID_REQUEST"); Path source=canonical(Path.of(text(record.path("private_input_path")))); inputs.add(source);
                byte[] original=read(source,1048576); require(original.length==integer(record.path("canonical_input_bytes")) && sha(original).equals(digest(record.path("canonical_input_sha256"))),"INVALID_REQUEST");
                PrecheckProviderRequest request=input(id,json(original),Duration.ofSeconds(15)); byte[] gen=CONTRACT.requestBytes(request,true),count=CONTRACT.countRequestBytes(gen,MODEL);require(gen.length<=LIMIT && count.length<=LIMIT,"INVALID_REQUEST");
                Path gp=canonical(Path.of(text(record.path("private_wire_output_path")))),cp=canonical(Path.of(gp+".count.json"));
                for(Path p:List.of(gp,cp))require(outputs.add(p)&&!Files.exists(p,LinkOption.NOFOLLOW_LINKS),"OUTPUT_CONFLICT");
                targets.add(gp);bytes.add(gen);targets.add(cp);bytes.add(count);
                prepared.add(map("opaque_call_id",id,"input_ref",reference(id).toString(),"generation_request_path",gp.toString(),"generation_request_sha256",sha(gen),"generation_request_bytes",gen.length,"count_request_path",cp.toString(),"count_request_sha256",sha(count),"count_request_bytes",count.length));
            }
            for(Path p:targets)require(!inputs.contains(p),"OUTPUT_CONFLICT");
            for(int i=0;i<targets.size();i++)writeNew(targets.get(i),bytes.get(i));
            writeJson(preparedIndex,map("status","WIRES_PREPARED_NOT_EXECUTION","records",prepared));return map("prepared",true,"caseCount",2,"indexSha256",hash(preparedIndex));
        }catch(Exception failure){throw safe(failure,"INVALID_REQUEST");}
    }
    private static void approved(Request r) throws IOException {
        HttpUrl u=r.url(); URI uri=u.uri();
        requireIo(r.method().equals("POST")&&u.scheme().equals("https")&&u.host().equals(HOST)&&u.port()==443&&uri.getRawUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null&&(u.encodedPath().equals(COUNT)||u.encodedPath().equals(GENERATE)),"CONFIGURATION");
    }
    public static OkHttpClient mainClient(Path planPath, Map<String,String> environment) throws Exception {
        try {
            JsonNode plan=readJson(planPath,4194304); X509Certificate extra=environmentCertificate(plan,environment);
            OkHttpClient.Builder builder=new OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false);
            if(environmentProxy(plan)) {
                String value=environment.containsKey("https_proxy")?environment.get("https_proxy"):environment.get("HTTPS_PROXY");
                require(value!=null&&!value.isEmpty()&&value.codePoints().noneMatch(c->Character.isWhitespace(c)||Character.isISOControl(c)),"CONFIGURATION");
                URI uri=new URI(value);require("http".equals(uri.getScheme())&&!uri.isOpaque()&&uri.getHost()!=null&&!uri.getHost().isBlank()&&uri.getRawUserInfo()==null&&uri.getRawQuery()==null&&uri.getRawFragment()==null,"CONFIGURATION");
                require(uri.getRawPath()==null||uri.getRawPath().isEmpty()||uri.getRawPath().equals("/"),"CONFIGURATION");require(uri.getRawAuthority()!=null&&!uri.getRawAuthority().endsWith(":"),"CONFIGURATION");int port=uri.getPort()==-1?80:uri.getPort();require(port>0&&port<=65535,"CONFIGURATION");
                builder.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,java.net.InetSocketAddress.createUnresolved(uri.getHost(),port)));
            }
            if(extra!=null)addEnvironmentTrust(builder,extra);
            builder.addInterceptor(chain->{approved(chain.request());return chain.proceed(chain.request());});
            builder.addNetworkInterceptor(chain->{approved(chain.request());Response response=chain.proceed(chain.request());if(response.code()!=200){int code=response.code();response.close();throw new AiHttpProviderStatusException(code,null,new byte[0]);}return response;});
            return builder.build();
        }catch(Exception failure){throw new EvaluationException("CONFIGURATION");}
    }
    private static X509Certificate environmentCertificate(JsonNode plan, Map<String,String> environment) throws Exception {
        if (!plan.has("environmentCa")) return null;
        try {
            require(environmentProxy(plan), "CONFIGURATION");
            JsonNode metadata = plan.path("environmentCa");
            keys(metadata, Set.of("environmentVariable", "pemFileSha256", "certificateDerSha256"));
            require(text(metadata.path("environmentVariable")).equals("NODE_EXTRA_CA_CERTS"), "CONFIGURATION");
            String pemPin = digest(metadata.path("pemFileSha256")), derPin = digest(metadata.path("certificateDerSha256"));
            String value = environment.get("NODE_EXTRA_CA_CERTS");
            require(value != null && !value.isBlank(), "CONFIGURATION");
            Path path = Path.of(value); require(path.isAbsolute(), "CONFIGURATION"); canonical(path);
            require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "CONFIGURATION");
            byte[] raw = read(path, LIMIT); require(raw.length > 0 && sha(raw).equals(pemPin), "CONFIGURATION");
            for (byte b : raw) require(b >= 0, "CONFIGURATION");
            String pem = new String(raw, StandardCharsets.US_ASCII);
            var match = java.util.regex.Pattern.compile("\\s*-----BEGIN CERTIFICATE-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END CERTIFICATE-----\\s*").matcher(pem);
            require(match.matches(), "CONFIGURATION");
            byte[] der = Base64.getDecoder().decode(match.group(1).replaceAll("\\s", ""));
            require(der.length > 0 && sha(der).equals(derPin), "CONFIGURATION");
            ByteArrayInputStream input = new ByteArrayInputStream(der);
            X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            require(input.available() == 0 && Arrays.equals(der, certificate.getEncoded()), "CONFIGURATION");
            certificate.checkValidity();
            require(certificate.getBasicConstraints() >= 0, "CONFIGURATION");
            boolean[] usage = certificate.getKeyUsage();
            require(usage == null || (usage.length > 5 && usage[5]), "CONFIGURATION");
            require(certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal()), "CONFIGURATION");
            certificate.verify(certificate.getPublicKey());
            return certificate;
        } catch (Exception failure) { throw new EvaluationException("CONFIGURATION"); }
    }
    private static X509TrustManager trustManager(KeyStore anchors) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(anchors);
        for (TrustManager manager : factory.getTrustManagers()) if (manager instanceof X509TrustManager x509) return x509;
        throw new EvaluationException("CONFIGURATION");
    }
    private static void addEnvironmentTrust(OkHttpClient.Builder builder, X509Certificate extra) throws Exception {
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType()); anchors.load(null, null);
        for (X509Certificate certificate : trustManager(null).getAcceptedIssuers()) anchors.setCertificateEntry(sha(certificate.getEncoded()), certificate);
        anchors.setCertificateEntry(sha(extra.getEncoded()), extra);
        X509TrustManager trust = trustManager(anchors);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(null, new TrustManager[]{trust}, null);
        builder.sslSocketFactory(context.getSocketFactory(), trust);
    }


    private static Setup validate(Path planPath,Path indexPath,Path outputPath,Map<String,String> env)throws Exception {
        String seal=hash(planPath);require(seal.equals(env.get("ISSUE130_B_FROZEN_PLAN_SHA256"))&&seal.equals(env.get("ISSUE130_B_EXECUTE_FROZEN")),"CONFIGURATION");require(env.get("GEMINI_API_KEY")!=null&&!env.get("GEMINI_API_KEY").isBlank(),"CONFIGURATION");
        JsonNode p=readJson(planPath,4194304);require(text(p.path("workItem")).equals("issue130-standalone-generation-quality")&&text(p.path("evaluationProfile")).equals("STANDALONE_B_QUALITY_ONLY"),"CONFIGURATION");UUID.fromString(text(p.path("suiteId")));
        Path root=canonical(Path.of(text(p.path("sourceRoot"))));Process git=new ProcessBuilder("git","-C",root.toString(),"rev-parse","HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start();String head=new String(git.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim();require(git.waitFor()==0&&head.equals(text(p.path("sourceRevision"))),"CONFIGURATION");
        fileEvidence(p.path("sourceManifest"));JsonNode manifest=readJson(Path.of(text(p.path("sourceManifest").path("path"))),4194304).path("files");require(manifest.isObject()&&!manifest.isEmpty(),"CONFIGURATION");for(String relative:manifest.propertyNames()){Path target=canonical(root.resolve(relative));require(target.startsWith(root)&&hash(target).equals(digest(manifest.path(relative))),"CONFIGURATION");}
        JsonNode runtime=p.path("runtimeFiles");require(runtime.isObject()&&!runtime.isEmpty(),"CONFIGURATION");for(String name:runtime.propertyNames())require(hash(Path.of(name)).equals(digest(runtime.path(name))),"CONFIGURATION");for(String source:SOURCES)require(runtime.has(root.resolve(source).toString()),"CONFIGURATION");
        require(hash(indexPath).equals(digest(p.path("preparedIndexSha256"))),"CONFIGURATION");require(text(p.path("endpoint")).equals("https://"+HOST)&&text(p.path("model")).equals(MODEL)&&text(p.path("thinkingLevel")).equals("LOW"),"CONFIGURATION");
        Map<String,Integer> fixed=new LinkedHashMap<>();fixed.put("caseCount",2);fixed.put("perCallSeconds",15);fixed.put("suiteSeconds",120);fixed.put("concurrency",1);fixed.put("maximumHttpAttempts",4);fixed.put("classifierCalls",0);fixed.put("countCalls",2);fixed.put("generationCalls",2);fixed.put("automaticRetries",0);fixed.put("redirects",0);fixed.put("fallbacks",0);fixed.put("requestByteCap",LIMIT);fixed.put("responseByteCap",LIMIT);fixed.put("maximumInputPreflightTokens",4096);fixed.put("maximumOutputTokens",2048);fixed.put("candidateCount",1);
        for(var e:fixed.entrySet())require(integer(p.path(e.getKey()))==e.getValue(),"CONFIGURATION");require(p.path("operatingConfigurationChanged").isBoolean()&&!p.path("operatingConfigurationChanged").asBoolean()&&environmentProxy(p)&&p.path("environmentCa").isObject(),"CONFIGURATION");environmentCertificate(p,env);cost(p);
        Path output=canonical(outputPath);require(output.equals(canonical(Path.of(text(p.path("outputDirectory"))))),"CONFIGURATION");require(!Files.exists(output.resolve(".suite-consumed.json"),LinkOption.NOFOLLOW_LINKS),"ALREADY_CONSUMED");
        JsonNode index=readJson(indexPath,1048576);keys(index,Set.of("status","records"));require(text(index.path("status")).equals("WIRES_PREPARED_NOT_EXECUTION")&&index.path("records").isArray()&&index.path("records").size()==2,"CONFIGURATION");
        List<Case> cases=new ArrayList<>();Set<String> ids=new HashSet<>();Set<Path> paths=new HashSet<>();
        for(JsonNode r:index.path("records")) {
            keys(r,Set.of("opaque_call_id","input_ref","generation_request_path","generation_request_sha256","generation_request_bytes","count_request_path","count_request_sha256","count_request_bytes"));String id=identity(r.path("opaque_call_id"));require(ids.add(id)&&text(r.path("input_ref")).equals(reference(id).toString()),"CONFIGURATION");
            Path gp=canonical(Path.of(text(r.path("generation_request_path")))),cp=canonical(Path.of(text(r.path("count_request_path"))));require(paths.add(gp)&&paths.add(cp)&&cp.toString().equals(gp+".count.json"),"CONFIGURATION");byte[] gen=read(gp,LIMIT),count=read(cp,LIMIT);require(gen.length==integer(r.path("generation_request_bytes"))&&sha(gen).equals(digest(r.path("generation_request_sha256")))&&count.length==integer(r.path("count_request_bytes"))&&sha(count).equals(digest(r.path("count_request_sha256"))),"CONFIGURATION");
            JsonNode outer=json(gen),data=json(text(outer.path("contents").get(0).path("parts").get(0).path("text")).getBytes(StandardCharsets.UTF_8));Set<String> inputKeys=new HashSet<>(INPUT_KEYS);inputKeys.add("input_ref");keys(data,inputKeys);require(text(data.path("input_ref")).equals(reference(id).toString()),"CONFIGURATION");var clean=JSON.createObjectNode();for(String key:INPUT_KEYS)clean.set(key,data.path(key));PrecheckProviderRequest request=input(id,clean,Duration.ofSeconds(15));
            require(Arrays.equals(CONTRACT.requestBytes(request,true),gen)&&Arrays.equals(CONTRACT.countRequestBytes(gen,MODEL),count),"CONFIGURATION");cases.add(new Case(id,request,gen,count));
        }
        return new Setup(p,output,cases);
    }
    private static void cost(JsonNode p)throws Exception {
        JsonNode c=p.path("costProfile");require(c.isObject(),"CONFIGURATION");Map<String,String> expected=new LinkedHashMap<>();expected.put("inputUsdPerMillion","0.75");expected.put("outputUsdPerMillion","3.75");expected.put("margin","2.2");expected.put("perOperationReservedUsd","0.15206400");expected.put("perCaseReservedUsd","0.30412800");expected.put("priorHeldUsd","2.13925888");expected.put("maximumNewReservedUsd","0.60825600");expected.put("maximumCumulativeReservedUsd","2.74751488");expected.put("approvedAggregateUsd","3.00");
        for(var e:expected.entrySet())require(decimal(c.path(e.getKey())).compareTo(new BigDecimal(e.getValue()))==0,"CONFIGURATION");require(integer(c.path("inputTokenProxy"))==81920&&integer(c.path("maximumOutputTokens"))==2048&&c.path("officialInvoiceHardCapProven").isBoolean()&&!c.path("officialInvoiceHardCapProven").asBoolean(),"CONFIGURATION");
        require(decimal(c.path("perOperationReservedUsd")).multiply(BigDecimal.valueOf(2)).compareTo(decimal(c.path("perCaseReservedUsd")))==0&&decimal(c.path("perCaseReservedUsd")).multiply(BigDecimal.valueOf(2)).compareTo(decimal(c.path("maximumNewReservedUsd")))==0,"CONFIGURATION");require(decimal(c.path("priorHeldUsd")).add(decimal(c.path("maximumNewReservedUsd"))).compareTo(decimal(c.path("maximumCumulativeReservedUsd")))==0&&decimal(c.path("maximumCumulativeReservedUsd")).compareTo(decimal(c.path("approvedAggregateUsd")))<=0,"CONFIGURATION");fileEvidence(c.path("review"));
        JsonNode evidence=p.path("priorReservation");require(Path.of(text(evidence.path("path"))).isAbsolute(),"CONFIGURATION");fileEvidence(evidence);JsonNode previous=readJson(Path.of(text(evidence.path("path"))),4194304);require(text(previous.path("status")).equals("FULL_SUITE_RESERVED_BEFORE_EXECUTION"),"CONFIGURATION");for(var e:Map.of("previousRetainedReservationUsd","1.79322880","newReservationUsd","0.34603008","cumulativeReservationUsd","2.13925888","approvedAggregateUsd","3.00").entrySet())require(decimal(previous.path(e.getKey())).compareTo(new BigDecimal(e.getValue()))==0,"CONFIGURATION");require(integer(previous.path("refunds"))==0&&previous.path("retainedUnknownsRefunded").isBoolean()&&!previous.path("retainedUnknownsRefunded").asBoolean(),"CONFIGURATION");
    }
    public static Map<String,Object> runSuite(Path planPath,Path preparedIndex,Path outputDirectory,Dependencies deps)throws Exception {
        long started=deps.nanoTime().getAsLong();
        try {
            Setup setup=validate(planPath,preparedIndex,outputDirectory,deps.environment());long millis=Long.parseLong(Objects.requireNonNull(deps.environment().get("ISSUE130_B_SUITE_REMAINING_MILLIS")));require(millis>0&&millis<=120000,"CONFIGURATION");long deadline=started+millis*1000000L;remaining(deadline,deps.nanoTime());
            Path output=setup.output();Files.createDirectories(output,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));canonical(output);Path lockPath=canonical(output.resolve(".suite.lock"));
            try(FileChannel channel=FileChannel.open(lockPath,Set.of(StandardOpenOption.CREATE,StandardOpenOption.WRITE),PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))) {
                FileLock held;try{held=channel.tryLock();}catch(OverlappingFileLockException conflict){throw new EvaluationException("ALREADY_CONSUMED");}require(held!=null,"ALREADY_CONSUMED");
                try(FileLock lock=held){
                    writeJson(output.resolve(".suite-consumed.json"),map("state","FULL_B2_RESERVATION_HELD","suiteId",text(setup.plan().path("suiteId")),"planSha256",hash(planPath),"maximumNewReservedUsd","0.60825600","priorHeldUsd","2.13925888","maximumCumulativeReservedUsd","2.74751488","startedAt",deps.clock().instant().toString()));
                    List<Map<String,Object>> results=new ArrayList<>();boolean stopped=false;String stopReason=null;int counts=0,gens=0,countNetwork=0,genNetwork=0;
                    for(int i=0;i<2;i++) {
                        Case item=setup.cases().get(i);String ordinal=String.format(Locale.ROOT,"%02d",i+1);Map<String,Object> result=notRun(item);
                        if(!stopped){try{result=runCase(item,output,ordinal,deadline,deps);}catch(Exception failure){result.put("outcome","STOP_API_OR_CONTRACT_OR_TIMEOUT");result.put("failureCode",safe(failure,"OBSERVATION_FAILURE").code());}if("STOP_API_OR_CONTRACT_OR_TIMEOUT".equals(result.get("outcome"))){stopped=true;stopReason=(String)result.get("failureCode");}}
                        counts+=((Number)result.get("countCalls")).intValue();gens+=((Number)result.get("generationCalls")).intValue();countNetwork+=((Number)result.get("countObservedNetworkEntries")).intValue();genNetwork+=((Number)result.get("generationObservedNetworkEntries")).intValue();results.add(result);
                        try{writeJson(output.resolve(ordinal+"-result.json"),result);}catch(Exception failure){result.put("outcome","STOP_API_OR_CONTRACT_OR_TIMEOUT");result.put("failureCode","OBSERVATION_FAILURE");stopped=true;stopReason="OBSERVATION_FAILURE";}
                    }
                    long finished=deps.nanoTime().getAsLong();if(finished-deadline>=0){stopped=true;stopReason="TIMEOUT";}
                    Map<String,Object> report=map("status",stopped?"STOPPED":"COMPLETED","stopReason",stopReason,"results",results,"classifierCalls",0,"countCalls",counts,"generationCalls",gens,"countObservedNetworkEntries",countNetwork,"generationObservedNetworkEntries",genNetwork,"maximumNewReservedUsd","0.60825600","maximumCumulativeReservedUsd","2.74751488","startedNanos",started,"cutoffNanos",deadline,"finishedNanos",finished,"elapsedMs",(finished-started)/1000000.0,"clock","JVM_MONOTONIC_NOT_WALL_TIME");writeJson(output.resolve("suite-result.json"),report);return report;
                }
            }
        }catch(Exception failure){throw safe(failure,"CONFIGURATION");}
    }
    private static final class Observation {
        int applications,networks;Integer status;byte[] raw;
    }
    private static Map<String,Object> runCase(Case item,Path output,String ordinal,long suiteDeadline,Dependencies deps)throws Exception {
        long started=deps.nanoTime().getAsLong(),cutoff=started+Math.min(Duration.ofSeconds(15).toNanos(),remaining(suiteDeadline,deps.nanoTime()));Map<String,Object> result=notRun(item);result.put("outcome","STOP_API_OR_CONTRACT_OR_TIMEOUT");result.put("startedAt",deps.clock().instant().toString());
        writeJson(output.resolve(ordinal+"-consumed.json"),map("state","UNKNOWN_HELD","callId",item.id(),"perCaseReservedUsd","0.30412800","startedAt",deps.clock().instant().toString()));
        Observation count=new Observation(),gen=new Observation();
        OkHttpClient.Builder builder=deps.client().newBuilder().retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false);
        builder.eventListener(new okhttp3.EventListener(){@Override public void responseHeadersEnd(Call call,Response response){(call.request().url().encodedPath().equals(COUNT)?count:gen).status=response.code();}});
        builder.interceptors().add(0,chain->{
            Request request=chain.request();approved(request);Observation obs=request.url().encodedPath().equals(COUNT)?count:gen;byte[] expected=obs==count?item.count():item.generation();requireIo(++obs.applications==1,"CONFIGURATION");remainingIo(cutoff,deps.nanoTime());Buffer buffer=new Buffer();Objects.requireNonNull(request.body()).writeTo(buffer);requireIo(Arrays.equals(expected,buffer.readByteArray()),"CONFIGURATION");
            // Send immutable, already admitted bytes even if a future request body is stateful.
            Request frozen=request.newBuilder().post(RequestBody.create(expected,MediaType.get("application/json; charset=utf-8"))).build();
            Response response=chain.proceed(frozen);obs.status=response.code();try{byte[] raw=response.peekBody(LIMIT+1L).bytes();requireIo(raw.length<=LIMIT,"INVALID_RESPONSE");obs.raw=raw;writeNew(output.resolve(ordinal+(obs==count?"-count-response.raw.json":"-generation-response.raw.json")),raw);remainingIo(cutoff,deps.nanoTime());return response;}catch(Exception failure){response.close();throw new IOException("OBSERVATION_FAILURE");}
        });
        // Installed after supplied application interceptors, immediately before connection setup.
        builder.addInterceptor(chain->{approved(chain.request());remainingIo(cutoff,deps.nanoTime());return chain.proceed(chain.request());});
        builder.addNetworkInterceptor(chain->{approved(chain.request());remainingIo(cutoff,deps.nanoTime());Observation obs=chain.request().url().encodedPath().equals(COUNT)?count:gen;byte[] expected=obs==count?item.count():item.generation();requireIo(++obs.networks==1,"CONFIGURATION");Buffer body=new Buffer();Objects.requireNonNull(chain.request().body()).writeTo(body);requireIo(Arrays.equals(expected,body.readByteArray()),"CONFIGURATION");return chain.proceed(chain.request().newBuilder().post(RequestBody.create(expected,MediaType.get("application/json; charset=utf-8"))).build());});
        try {
            PrecheckProviderRequest old=item.request();PrecheckProviderRequest request=new PrecheckProviderRequest(old.getCheckId(),old.getRawText(),old.getReferenceAt(),old.getTimeZone(),old.getLocale(),old.getSearchStartDate(),old.getSearchEndDate(),Duration.ofNanos(remaining(cutoff,deps.nanoTime())),2048,null,null,null);
            var provider=GeminiPreSubmissionCheckProvider.Companion.runtime(new GeminiProperties(deps.environment().get("GEMINI_API_KEY"),MODEL,262144),JSON,new PrecheckProviderRuntimeProperties(true),new PrecheckProviderHttpTransport(builder.build()),null,URI.create("https://"+HOST),null);
            PrecheckProviderResult parsed=provider.check(request);writeJson(output.resolve(ordinal+"-parsed-result.private.json"),parsed);require(gen.raw!=null,"OBSERVATION_FAILURE");var usage=CONTRACT.observeUsage(gen.raw);require(!usage.getInvalid()&&usage.getTokens().get("INPUT")!=null&&usage.getTokens().get("CANDIDATE")!=null&&usage.getTokens().get("THOUGHT")!=null,"INVALID_RESPONSE");remaining(cutoff,deps.nanoTime());result.put("outcome",parsed.getVerdict().name());result.put("verdict",parsed.getVerdict().name());
        }catch(Exception failure){String code=failure instanceof PrecheckProviderException e?e.getFailureCode():safe(failure,"OBSERVATION_FAILURE").code();if(cutoff-deps.nanoTime().getAsLong()<=0)code="TIMEOUT";result.put("failureCode",code);}
        result.put("countCalls",count.applications);result.put("generationCalls",gen.applications);result.put("countObservedNetworkEntries",count.networks);result.put("generationObservedNetworkEntries",gen.networks);result.put("countHttpStatus",count.status);result.put("generationHttpStatus",gen.status);
        if(count.raw!=null){result.put("countResponseSha256",sha(count.raw));result.put("countResponseBytes",count.raw.length);try{result.put("countTokens",CONTRACT.parseCount(count.raw));}catch(Exception ignored){}}
        if(gen.raw!=null){result.put("generationResponseSha256",sha(gen.raw));result.put("generationResponseBytes",gen.raw.length);var usage=CONTRACT.observeUsage(gen.raw);Long in=usage.getTokens().get("INPUT"),candidate=usage.getTokens().get("CANDIDATE"),thought=usage.getTokens().get("THOUGHT");result.put("inputTokens",in);result.put("candidateTokens",candidate);result.put("thoughtTokens",thought);result.put("outputTokens",candidate!=null&&thought!=null&&candidate<=Long.MAX_VALUE-thought?candidate+thought:null);}
        long finish=deps.nanoTime().getAsLong();result.put("startedNanos",started);result.put("cutoffNanos",cutoff);result.put("finishedNanos",finish);result.put("elapsedMs",(finish-started)/1000000.0);result.put("clock","JVM_MONOTONIC_NOT_WALL_TIME");return result;
    }
    private static Map<String,Object> notRun(Case item)throws Exception {
        return map("callId",item.id(),"outcome","NOT_RUN_AFTER_SUITE_STOP","verdict",null,"failureCode",null,"countHttpStatus",null,"generationHttpStatus",null,"countTokens",null,"inputTokens",null,"outputTokens",null,"candidateTokens",null,"thoughtTokens",null,"countCalls",0,"generationCalls",0,"countObservedNetworkEntries",0,"generationObservedNetworkEntries",0,"countRequestSha256",sha(item.count()),"generationRequestSha256",sha(item.generation()),"countRequestBytes",item.count().length,"generationRequestBytes",item.generation().length,"countResponseSha256",null,"generationResponseSha256",null,"countResponseBytes",null,"generationResponseBytes",null);
    }
    public static void main(String[] args) {
        try{Map<String,Object> report;if(args.length==3&&args[0].equals("prepare-index"))report=prepareIndex2(Path.of(args[1]),Path.of(args[2]));else if(args.length==4&&args[0].equals("run-suite"))report=runSuite(Path.of(args[1]),Path.of(args[2]),Path.of(args[3]),new Dependencies(mainClient(Path.of(args[1]),System.getenv()),System::nanoTime,Clock.systemUTC(),System.getenv()));else throw new EvaluationException("CONFIGURATION");System.out.println(JSON.writeValueAsString(report));}catch(Exception failure){System.out.println(JSON.writeValueAsString(map("accepted",false,"failureCode",safe(failure,"CONFIGURATION").code())));System.exit(2);}
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
