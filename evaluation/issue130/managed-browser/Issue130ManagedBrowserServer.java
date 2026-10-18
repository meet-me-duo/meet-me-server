package com.meetme.integration.managed;

import com.meetme.server.MeetMeServerApplication;
import com.meetme.server.account.adapter.input.web.AuthEndToEndPostgresTest;
import com.meetme.server.coordination.adapter.input.web.Issue131CanonicalTlsProxy;
import com.meetme.server.config.ManagedAiRuntimeSelection;
import com.meetme.server.precheck.adapter.output.provider.PrecheckProviderHttpTransport;
import com.meetme.server.precheck.application.port.output.PreSubmissionCheckProvider;
import com.meetme.server.precheck.application.port.output.AmbiguityClassificationPort;
import com.meetme.server.precheck.application.service.PreSubmissionCheckWorker;
import com.meetme.server.shared.application.port.output.DurableAiBudgetPort;
import com.meetme.server.shared.application.port.output.DurableAiBudgetRepository;
import com.meetme.server.shared.application.port.output.AiBudgetExecutionFencePort;
import com.meetme.server.shared.adapter.output.aihttp.AiHttpDispatchGate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.ImagePullPolicy;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Separate test executable: production graph, synthetic physical transport and synthetic local OIDC only. */
@SuppressWarnings({"removal", "unchecked"})
public final class Issue130ManagedBrowserServer {
  private static final String FIXTURE = "issue130-managed-browser-v1";
  private static final String WEB = "https://localhost:19443", API = "https://localhost:19444";
  private static final ObjectMapper JSON = JsonMapper.builder().build();
  private static Issue131CanonicalTlsProxy proxy;
  private static Map<String,Object> plan;

  @TestConfiguration(proxyBeanMethods = false)
  public static class Transport {
    @Bean @Primary public PrecheckProviderHttpTransport managedSyntheticTlsTransport() {
      return new PrecheckProviderHttpTransport(proxy.client());
    }
  }
  /** Even missing images must fail locally, never ask the Docker daemon to pull. */
  public static final class LocalOnlyPullPolicy implements ImagePullPolicy {
    @Override public boolean shouldPull(DockerImageName name) { return false; }
  }

  public static void main(String[] args) {
    try { run(args); }
    catch (Throwable ignored) { System.err.println("MANAGED_BROWSER_STARTUP_REJECTED"); System.exit(2); }
  }

  private static void run(String[] args) throws Exception {
    if (args.length != 2 || !(System.getSecurityManager() instanceof ManagedBrowserLoopbackGuard)) reject();
    Path manifestPath = Path.of(args[0]).toAbsolutePath().normalize();
    Path work = Path.of(args[1]).toAbsolutePath().normalize();
    if (!Files.isDirectory(work) || Files.isSymbolicLink(work) || Files.exists(work.resolve("stop")) || Files.exists(work.resolve("runtime-health.json"))) reject();
    for (String key : List.of("OPENAI_API_KEY","GEMINI_API_KEY","AWS_ACCESS_KEY_ID","AWS_SECRET_ACCESS_KEY","AWS_SESSION_TOKEN","GOOGLE_CLIENT_SECRET","KAKAO_CLIENT_SECRET")) {
      if (!System.getenv().getOrDefault(key, "").isEmpty()) reject();
    }
    // Deliberately direct guard decision: no DNS lookup or external socket is attempted.
    boolean negative = false;
    try { System.getSecurityManager().checkConnect("203.0.113.1", 443); }
    catch (SecurityException expected) { negative = true; }
    if (!negative) reject();
    Map<String,Object> pins = verifyFiles(manifestPath);
    Path privateDir = work.resolve("private");
    Files.createDirectory(privateDir); Files.setPosixFilePermissions(privateDir, PosixFilePermissions.fromString("rwx------"));
    System.setProperty("meetme.auth.test.redirect-base", API);
    // Testcontainers' default policy comes from its isolated HOME properties prepared by Python.
    var docker = DockerClientFactory.instance().client();
    for (String image : List.of("postgres:18-alpine", "redis:8-alpine")) docker.inspectImageCmd(image).exec();
    if (!(org.testcontainers.images.PullPolicy.defaultPolicy() instanceof LocalOnlyPullPolicy)) reject();
    AtomicBoolean closed = new AtomicBoolean();
    ConfigurableApplicationContext[] app = {null}; HttpServer[] control = {null};
    var executor = Executors.newSingleThreadExecutor();
    Runnable cleanup = () -> {
      if (!closed.compareAndSet(false, true)) return;
      if (control[0] != null) control[0].stop(0);
      executor.shutdownNow();
      if (app[0] != null) try { app[0].close(); } catch (Throwable ignored) { }
      if (proxy != null) try { proxy.close(); } catch (Throwable ignored) { }
      try { AuthEndToEndPostgresTest.stop(); } catch (Throwable ignored) { }
      // This JVM's immutable Testcontainers session label covers partial static fixture startup too.
      try {
        for (var container : docker.listContainersCmd().withShowAll(true).withLabelFilter(Map.of(
            DockerClientFactory.TESTCONTAINERS_SESSION_ID_LABEL, DockerClientFactory.SESSION_ID)).exec()) {
          docker.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
        }
      } catch (Throwable ignored) { }
      try { removeOwnedPrivate(privateDir); } catch (IOException ignored) { }
    };
    Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "managed-browser-owned-cleanup"));
    try {
      proxy = new Issue131CanonicalTlsProxy(privateDir);
      Path policy = privateDir.resolve("policy.json");
      Path pinnedPolicy = manifestPath.getParent().resolve("runtime/resources/managed-browser-synthetic-policy.json");
      Files.copy(pinnedPolicy, policy); Files.setPosixFilePermissions(policy, PosixFilePermissions.fromString("rw-------"));
      Map<String,String> settings = new LinkedHashMap<>();
      AuthEndToEndPostgresTest.properties((name, supplier) -> settings.put(name, supplier.get().toString()));
      settings.putAll(Map.ofEntries(
          Map.entry("server.address","127.0.0.1"), Map.entry("server.port","19440"),
          Map.entry("meetme.anonymous.allowed-origins",WEB), Map.entry("meetme.anonymous.cookie-secure","true"),
          Map.entry("meetme.auth.web-callback-uri",WEB+"/auth/callback"),
          Map.entry("meetme.reliability.enabled","false"), Map.entry("meetme.rate-limit.enabled","false"),
          Map.entry("meetme.data-retention.enabled","false"), Map.entry("meetme.operations.cleanup-enabled","false"),
          Map.entry("meetme.ai.budget.enabled","true"), Map.entry("meetme.precheck.provider.enabled","true"),
          Map.entry("meetme.precheck.decision-routing.enabled","true"), Map.entry("meetme.precheck.classifier.enabled","true"),
          Map.entry("meetme.precheck.classifier.minimum-confidence","0.73"),
          Map.entry("meetme.ai.budget.policy-path",policy.toString()), Map.entry("meetme.ai.budget.policy-sha256",sha(policy)),
          Map.entry("meetme.gemini.api-key","synthetic-not-a-real-key"), Map.entry("meetme.openai.api-key","synthetic-not-a-real-key"),
          Map.entry("spring.main.banner-mode","off")));
      settings.forEach(System::setProperty);
      app[0] = new SpringApplicationBuilder(MeetMeServerApplication.class, AuthEndToEndPostgresTest.IsolatedAuthProviders.class, Transport.class).run();
      var context = app[0];
      ManagedAiRuntimeSelection selection = context.getBean(ManagedAiRuntimeSelection.class);
      if (!selection.getEnabled() || selection.getPolicy() == null) reject();
      Map<String,String> graph = new LinkedHashMap<>();
      graph.put("provider", target(context.getBean(PreSubmissionCheckProvider.class)));
      graph.put("classifier", target(context.getBean(AmbiguityClassificationPort.class)));
      graph.put("budget", target(context.getBean(DurableAiBudgetPort.class)));
      graph.put("repository", target(context.getBean(DurableAiBudgetRepository.class)));
      graph.put("gate", target(context.getBean(AiHttpDispatchGate.class)));
      graph.put("worker", target(context.getBean(PreSubmissionCheckWorker.class)));
      graph.put("fence", target(context.getBean(AiBudgetExecutionFencePort.class)));
      if (!graph.equals(pins.get("graph"))) reject();
      for (String key : List.of("meetme.ai.budget.enabled","meetme.precheck.provider.enabled","meetme.precheck.decision-routing.enabled","meetme.precheck.classifier.enabled")) {
        if (!"true".equals(context.getEnvironment().getProperty(key))) reject();
      }
      var pg = (PostgreSQLContainer) fixtureField("access$getPostgres$cp");
      var redis = (GenericContainer<?>) fixtureField("access$getRedis$cp");
      String issuer = fixtureField("access$getProviderOrigin$cp").toString();
      var listenerField = Issue131CanonicalTlsProxy.class.getDeclaredField("listener"); listenerField.setAccessible(true);
      int tlsPort = ((ServerSocket) listenerField.get(proxy)).getLocalPort();
      Map<String,Object> owned = Map.of("schemaVersion",1,"containerIds",List.of(pg.getContainerId(),redis.getContainerId()),
          "listenerPorts",List.of(19440,19442,java.net.URI.create(issuer).getPort(),tlsPort),"privateMaterialPaths",List.of(privateDir.toString(),work.resolve("home").toString(),work.resolve("tmp").toString()));
      writePrivate(work.resolve("owned-resources.json"), owned);
      Map<String,Object> readiness = new LinkedHashMap<>();
      readiness.putAll(Map.of("fixture",FIXTURE,"production",false,"managed",true,"primaryProviderFake",false,
          "managedEnabled",true,"providerEnabled",true,"routingEnabled",true,"classifierEnabled",true,"graph",graph));
      for (String key : List.of("manifestSha256","sourceSha256","runtimeSha256","classpathSha256")) readiness.put(key,pins.get(key));
      readiness.put("outbound",Map.of("mode","LOOPBACK_DENY_ALL_OTHER","installedBeforeApplication",true,"negativeProbeDenied",true,"nonLoopbackConnections",0));
      readiness.put("credentials",Map.of("syntheticOnly",true,"realEnvironmentKeysInherited",false));
      Map<String,Object> health = Map.ofEntries(Map.entry("fixture","issue123-host-current-v1"),Map.entry("implementation",FIXTURE),
          Map.entry("production",false),Map.entry("managed",true),Map.entry("primary_provider_fake",false),Map.entry("actual_ai_calls",0),
          Map.entry("actual_oauth_provider_calls",0),Map.entry("web_origin",WEB),Map.entry("api_origin",API),Map.entry("oidc_origin",issuer),
          Map.entry("db_container",pg.getContainerId()),Map.entry("db_user",pg.getUsername()),Map.entry("db_name",pg.getDatabaseName()));
      var worker = context.getBean(PreSubmissionCheckWorker.class); var jdbc = context.getBean(JdbcTemplate.class);
      control[0] = HttpServer.create(new InetSocketAddress("127.0.0.1",19442),0); control[0].setExecutor(executor);
      control[0].createContext("/", exchange -> {
        try {
          if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()) reject();
          if (exchange.getRequestHeaders().containsKey("Origin")) { reply(exchange,403,Map.of("error","CONTROL_ORIGIN_FORBIDDEN")); return; }
          String route = exchange.getRequestMethod()+" "+exchange.getRequestURI().getPath(); Object result;
          switch (route) {
            case "GET /health": result=health; break;
            case "GET /managed/readiness": result=readiness; break;
            case "GET /state": result=state(jdbc,exchange.getRequestURI().getRawQuery()); break;
            case "POST /synthetic-plan": plan=readPlan(exchange,false); result=Map.of("configured",true); break;
            case "POST /provider": plan=readPlan(exchange,true); result=Map.of("configured",true); break;
            case "POST /worker/run-one":
              if (plan==null) reject(); queueReplies(plan); result=Map.of("ran",worker.runOne()); break;
            default: reply(exchange,404,Map.of("error","UNKNOWN_FIXTURE_CONTROL")); return;
          }
          reply(exchange,200,result);
        } catch (Throwable ignored) { reply(exchange,409,Map.of("error","FIXTURE_CONTROL_REJECTED")); }
      });
      control[0].start(); writePrivate(work.resolve("runtime-health.json"),health); writePrivate(work.resolve("managed-readiness.json"),readiness);
      System.out.println("MANAGED_BROWSER_FIXTURE_READY");
      while (!Files.exists(work.resolve("stop"))) Thread.sleep(200);
    } finally { cleanup.run(); }
  }

  private static String target(Object bean) { return AopProxyUtils.ultimateTargetClass(bean).getName(); }
  private static Object fixtureField(String name) throws Exception { return AuthEndToEndPostgresTest.class.getDeclaredMethod(name).invoke(null); }
  private static void reject() { throw new IllegalArgumentException("MANAGED_FIXTURE_REJECTED"); }
  private static String sha(Path path) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))); }
  private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }
  private static void writePrivate(Path path,Object value) throws Exception {
    Files.write(path,JSON.writeValueAsBytes(value)); Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));
  }
  private static void removeOwnedPrivate(Path directory) throws IOException {
    if (!Files.exists(directory)) return;
    try (var paths=Files.walk(directory)) { for (Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); }
  }
  private static Map<String,Object> verifyFiles(Path manifest) throws Exception {
    if (!sha(manifest).equals(System.getProperty("meetme.fixture.manifest-sha256", ""))) reject();
    Map<String,Object> value=JSON.readValue(Files.readAllBytes(manifest),Map.class);
    if (!FIXTURE.equals(value.get("fixture")) || !"6046baf66e49e9382248cd3fb8adb0a8879a7e40".equals(value.get("serverHead"))) reject();
    for (String kind:List.of("source","runtime")) {
      Map<String,String> files=(Map<String,String>)value.get(kind+"Files"); Path root=manifest.getParent().resolve(kind);
      Map<String,String> actual=new TreeMap<>();
      try (var paths=Files.walk(root)) {
        for(Path p:paths.toList()) {
          if(Files.isSymbolicLink(p)) reject();
          if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)) actual.put(root.relativize(p).toString().replace('\\','/'),sha(p));
        }
      }
      if(!files.equals(actual)) reject();
    }
    for(var entry:((Map<String,String>)value.get("overlays")).entrySet()) {
      if(!entry.getValue().equals(((Map<String,String>)value.get("sourceFiles")).get(entry.getKey()))) reject();
    }
    value.put("manifestSha256",sha(manifest));
    for(String kind:List.of("source","runtime")) value.put(kind+"Sha256",hash(JSON.writeValueAsBytes(new TreeMap<>((Map<String,String>)value.get(kind+"Files")))));
    value.put("classpathSha256",hash(JSON.writeValueAsBytes(value.get("classpath")))); return value;
  }
  private static Map<String,Object> readPlan(HttpExchange exchange,boolean alias) throws Exception {
    byte[] body=exchange.getRequestBody().readNBytes(16385); if(body.length>16384) reject();
    Map<String,Object> raw=JSON.readValue(body,Map.class);
    if(alias) { if(!raw.equals(Map.of("verdict","CLEAR"))) reject(); return Map.of("stageA","CLEAR","stageB","CLEAR"); }
    if(!raw.keySet().equals(java.util.Set.of("stageA","stageB")) && !raw.keySet().equals(java.util.Set.of("stageA","stageB","question"))) reject();
    if(!List.of("CLEAR","NEEDS_REVIEW").contains(raw.get("stageA")) || !List.of("CLEAR","NEEDS_CHOICE","NEEDS_EDIT").contains(raw.get("stageB"))) reject();
    if("NEEDS_CHOICE".equals(raw.get("stageB"))) {
      if(!(raw.get("question") instanceof Map)) reject();
      Map<String,Object> q=(Map<String,Object>)raw.get("question");
      if(!q.keySet().equals(java.util.Set.of("text","source_excerpt","options")) || !(q.get("text") instanceof String) || !(q.get("source_excerpt") instanceof String) || !(q.get("options") instanceof List)) reject();
      List<?> options=(List<?>)q.get("options"); if(options.size()!=2) reject();
      for(int i=0;i<2;i++) { if(!(options.get(i) instanceof Map)) reject(); Map<?,?> o=(Map<?,?>)options.get(i);
        if(!o.keySet().equals(java.util.Set.of("id","text","proposed_text")) || !List.of("A","B").get(i).equals(o.get("id")) || !(o.get("text") instanceof String) || !(o.get("proposed_text") instanceof String)) reject(); }
    } else if(raw.containsKey("question")) reject();
    return raw;
  }
  private static void queueReplies(Map<String,Object> configured) throws Exception {
    // Only the test transport's pending replies reset; captured request evidence is retained.
    var field=Issue131CanonicalTlsProxy.class.getDeclaredField("replies"); field.setAccessible(true); ((Queue<?>)field.get(proxy)).clear();
    String choice=configured.get("stageA").toString();
    List<Map<String,Object>> probabilities=List.of(Map.of("value","CLEAR","probability",choice.equals("CLEAR")?.9:.05),
        Map.of("value","NEEDS_REVIEW","probability",choice.equals("NEEDS_REVIEW")?.9:.05),Map.of("value","INDETERMINATE","probability",.05));
    proxy.enqueue(JSON.writeValueAsString(Map.of("model","gpt-6-luna","answers",List.of(Map.of("type","choice","name","material_ambiguity","choice",choice,"confidence",.9,"probabilities",probabilities)),
        "usage",Map.of("input_tokens",100,"input_tokens_details",Map.of("cached_tokens",0,"cache_write_tokens",0),"output_tokens",0,"output_tokens_details",Map.of("reasoning_tokens",0),"total_tokens",100))));
    if(choice.equals("CLEAR")) return;
    proxy.enqueue("{\"totalTokens\":256}");
    Map<String,Object> payload=new LinkedHashMap<>(); payload.put("verdict",configured.get("stageB"));
    if("NEEDS_CHOICE".equals(configured.get("stageB"))) {
      Map<String,Object> q=(Map<String,Object>)configured.get("question"); payload.put("prompt",q.get("text")); payload.put("sourceExcerpt",q.get("source_excerpt"));
      List<Map<String,Object>> choices=new ArrayList<>();
      for(var raw:(List<Map<String,Object>>)q.get("options")) choices.add(Map.of("id",raw.get("id"),"label",raw.get("text"),"proposedText",raw.get("proposed_text")));
      payload.put("choices",choices);
    }
    proxy.enqueue(JSON.writeValueAsString(Map.of("candidates",List.of(Map.of("content",Map.of("parts",List.of(Map.of("text",JSON.writeValueAsString(payload)))),"finishReason","STOP")),
        "usageMetadata",Map.of("promptTokenCount",256,"candidatesTokenCount",12,"thoughtsTokenCount",20))));
  }
  private static Map<String,Object> state(JdbcTemplate jdbc,String query) throws Exception {
    Object[] scope={}; String filter="";
    if(query!=null) {
      if(!query.matches("roomCode=[A-Za-z0-9_-]{1,32}")) reject();
      var ids=jdbc.queryForList("SELECT id FROM meeting_rooms WHERE invite_code=?",java.util.UUID.class,query.substring(9));
      if(ids.size()!=1) reject(); scope=new Object[]{ids.get(0)}; filter=" WHERE room_id=?";
    }
    Map<String,Long> counts=new LinkedHashMap<>();
    for(String op:List.of("PRECHECK_CLASSIFICATION","PRECHECK_COUNT","PRECHECK_GENERATION","FINAL_PRIMARY","FINAL_FALLBACK")) {
      List<Object> args=new ArrayList<>(List.of(scope)); args.add(op);
      counts.put(op,jdbc.queryForObject("SELECT count(*) FROM ai_call_reservations"+filter+(filter.isEmpty()?" WHERE ":" AND ")+"operation=?",Long.class,args.toArray()));
    }
    long reservations=number(jdbc,"SELECT count(*) FROM ai_call_reservations"+filter,scope);
    long claims=number(jdbc,"SELECT count(*) FROM ai_call_reservations"+filter+(filter.isEmpty()?" WHERE ":" AND ")+"dispatch_token IS NOT NULL",scope);
    String ofilter=filter.replace("room_id","r.room_id");
    long observations=number(jdbc,"SELECT count(*) FROM ai_call_observations o JOIN ai_call_reservations r ON r.id=o.reservation_id"+ofilter,scope);
    Map<String,Long> reserved=new TreeMap<>(), seen=new TreeMap<>();
    for(String h:jdbc.queryForList("SELECT operation || ':' || whole_payload_fingerprint FROM ai_call_reservations WHERE dispatch_token IS NOT NULL",String.class)) reserved.merge(h,1L,Long::sum);
    for(var request:proxy.getRequests()) {
      String operation;
      if(request.getHost().equals("api.openai.com") && request.getPath().equals("/v1/decisions")) operation="PRECHECK_CLASSIFICATION";
      else if(request.getHost().equals("generativelanguage.googleapis.com") && request.getPath().endsWith(":countTokens")) operation="PRECHECK_COUNT";
      else if(request.getHost().equals("generativelanguage.googleapis.com") && request.getPath().endsWith(":generateContent")) operation="PRECHECK_GENERATION";
      else operation="UNEXPECTED_OPERATION";
      seen.merge(operation+":"+hash(request.getBody()),1L,Long::sum);
    }
    long mismatch=0; var keys=new java.util.HashSet<>(reserved.keySet()); keys.addAll(seen.keySet());
    for(String key:keys) mismatch+=Math.abs(reserved.getOrDefault(key,0L)-seen.getOrDefault(key,0L));
    mismatch+=number(jdbc,"SELECT count(*) FROM ai_call_observations o LEFT JOIN ai_call_reservations r ON r.id=o.reservation_id WHERE r.id IS NULL OR r.dispatch_token IS NULL",new Object[]{});
    mismatch+=number(jdbc,"SELECT count(*) FROM ai_call_reservations r LEFT JOIN ai_room_budgets b ON b.room_id=r.room_id " +
        "WHERE r.dispatch_token IS NOT NULL AND (b.room_id IS NULL OR r.epoch_id IS DISTINCT FROM b.epoch_id OR r.policy_fingerprint IS DISTINCT FROM b.policy_fingerprint)",new Object[]{});
    mismatch+=number(jdbc,"SELECT count(*) FROM ai_call_reservations r LEFT JOIN precheck_checks c ON c.id=r.execution_id " +
        "LEFT JOIN precheck_candidates p ON p.id=c.candidate_id LEFT JOIN precheck_executions e ON e.check_id=c.id AND e.generation=r.generation " +
        "WHERE r.execution_kind='PRECHECK' AND r.dispatch_token IS NOT NULL AND (p.room_id IS DISTINCT FROM r.room_id OR e.started_at IS NULL OR e.owner_token IS DISTINCT FROM r.owner_token)",new Object[]{});
    Map<String,Long> states=new TreeMap<>();
    for(var row:jdbc.queryForList("SELECT state,count(*) n FROM ai_room_budgets"+filter+" GROUP BY state",scope)) states.put(row.get("state").toString(),((Number)row.get("n")).longValue());
    Map<String,Object> budget=Map.of("states",states,"epochs",number(jdbc,"SELECT count(DISTINCT epoch_id) FROM ai_room_budgets"+filter,scope),
        "held",jdbc.queryForObject("SELECT coalesce(sum(held),0)::text FROM ai_room_budgets"+filter,String.class,scope),
        "settled",jdbc.queryForObject("SELECT coalesce(sum(settled),0)::text FROM ai_room_budgets"+filter,String.class,scope));
    Map<String,Object> product=Map.of("candidates",number(jdbc,"SELECT count(*) FROM precheck_candidates"+filter,scope),
        "acceptedHeads",number(jdbc,"SELECT count(*) FROM submission_heads"+filter,scope),
        "acceptedVersions",number(jdbc,"SELECT count(*) FROM submission_versions v JOIN submission_heads h ON h.id=v.submission_id"+filter.replace("room_id","h.room_id"),scope));
    return Map.of("ledger",Map.of("reservations",reservations,"dispatchClaims",claims,"observations",observations,"operationCounts",counts,"missingOrMismatchedDispatches",mismatch),
        "network",Map.of("localSyntheticCalls",proxy.getRequests().size(),"remoteCalls",0),"product",product,"budget",budget);
  }
  private static long number(JdbcTemplate jdbc,String sql,Object[] args) { return jdbc.queryForObject(sql,Long.class,args); }
  private static void reply(HttpExchange exchange,int status,Object body) throws IOException {
    byte[] bytes=JSON.writeValueAsBytes(body); exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.getResponseHeaders().set("Cache-Control","no-store");
    exchange.sendResponseHeaders(status,bytes.length); try(var out=exchange.getResponseBody()){out.write(bytes);} exchange.close();
  }
}
