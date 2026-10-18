import okhttp3.OkHttpClient;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Additive public ENVIRONMENT_HTTPS_PROXY contracts, independent synthetic inputs only. */
@SuppressWarnings({"unchecked","removal"})
public final class Issue130EnvironmentProxyContractTest {
  static final JsonMapper JSON=Issue130SyntheticContractTest.JSON;
  static final Path REPO=Issue130SyntheticContractTest.REPO;
  interface Work{void run()throws Exception;}
  static final Map<String,Work> TESTS=new LinkedHashMap<>();
  static void test(String name,Work work){TESTS.put(name,work);}
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
  static void eq(Object a,Object b,String message){check(Objects.equals(a,b),message);}
  static Map<String,Object> map(Object v){return (Map<String,Object>)v;}
  static void reject(Work w)throws Exception{try{w.run();throw new AssertionError("NEW_MODE_INVALID_INPUT_MUST_BE_REJECTED");}catch(Issue130RepresentativeDecisionsCli.EvaluationException expected){check(expected.code().matches("[A-Z_]{1,64}"),"bounded failure code");check(expected.getCause()==null,"no raw proxy exception cause");}}
  static <T> T success(java.util.concurrent.Callable<T> w)throws Exception{try{return w.call();}catch(Exception e){throw new AssertionError("NEW_MODE_VALID_CONTRACT_MUST_SUCCEED");}}
  static Path plan(Object mode)throws Exception{Path root=Issue130SyntheticContractTest.privateRoot(),p=root.resolve("transport.json");Map<String,Object> m=new LinkedHashMap<>();if(mode!=ABSENT)m.put("transportMode",mode);Issue130SyntheticContractTest.write(p,m);return p;}
  static final Object ABSENT=new Object();
  static OkHttpClient client(Object mode,Map<String,String> env)throws Exception{return Issue130RepresentativeDecisionsCli.mainClient(plan(mode),env);}
  static Map<String,Object> previous(){return new LinkedHashMap<>(Map.of("status","FULL_SUITE_RESERVED_BEFORE_EXECUTION","previousRetainedReservationUsd","1.33185536","newReservationUsd","0.23068672","cumulativeReservationUsd","1.56254208","refunds",0,"approvedAggregateUsd","2.00","safeMetadata","SYNTHETIC_PREVIOUS_UNKNOWN_HOLD"));}
  static final class Fixture implements AutoCloseable{
    final Issue130SyntheticContractTest.Fixture base=new Issue130SyntheticContractTest.Fixture();
    final Path evidence=base.root.resolve("synthetic-previous-reservation.json");
    Fixture()throws Exception{base.config.put("transportMode","ENVIRONMENT_HTTPS_PROXY");Map<String,Object> cost=map(base.config.get("costProfile"));cost.put("priorHeldUsd","1.56254208");cost.put("maximumCumulativeReservedUsd","1.79322880");evidence(previous());}
    void evidence(Map<String,Object> value)throws Exception{Issue130SyntheticContractTest.write(evidence,value);base.config.put("priorReservation",Map.of("path",evidence.toString(),"sha256",Issue130SyntheticContractTest.sha(evidence)));base.seal();}
    Map<String,Object> run()throws Exception{return base.run();}
    void deny()throws Exception{reject(()->run());eq(0,base.network().sent.size(),"invalid mode budget emits0HTTP");}
    public void close()throws Exception{base.close();}
  }
  static void selected(String lower,String upper,int port,String host)throws Exception{
    Map<String,String> env=new HashMap<>();if(lower!=null)env.put("https_proxy",lower);if(upper!=null)env.put("HTTPS_PROXY",upper);
    OkHttpClient c=success(()->client("ENVIRONMENT_HTTPS_PROXY",env));check(c.proxy()!=null&&c.proxy().type()==Proxy.Type.HTTP,"explicit fixed HTTP proxy");InetSocketAddress a=(InetSocketAddress)c.proxy().address();eq(port,a.getPort(),"selected proxy port");eq(host,a.getHostString(),"selected proxy host");check(a.isUnresolved(),"factory performs zero DNS");check(!c.retryOnConnectionFailure()&&!c.followRedirects()&&!c.followSslRedirects(),"proxy mode retry/redirect0");
  }
  static {
    test("PX01_lowercase_selected_unresolved",()->selected("http://synthetic-lower.invalid:3128","http://synthetic-upper.invalid:3129",3128,"synthetic-lower.invalid"));
    test("PX02_uppercase_only_selected",()->selected(null,"http://synthetic-upper.invalid:3129",3129,"synthetic-upper.invalid"));
    test("PX03_absent_port_defaults80",()->selected("http://synthetic-proxy.invalid",null,80,"synthetic-proxy.invalid"));
    test("PX04_root_path_allowed",()->selected("http://synthetic-proxy.invalid:8080/",null,8080,"synthetic-proxy.invalid"));
    test("PX05_NO_PROXY_never_selects_DIRECT",()->{OkHttpClient c=success(()->client("ENVIRONMENT_HTTPS_PROXY",Map.of("https_proxy","http://synthetic-proxy.invalid:8080","NO_PROXY","*","no_proxy","*")));check(c.proxy()!=null&&c.proxy().type()==Proxy.Type.HTTP,"forced selected proxy has no DIRECT bypass");});
    test("PX06_legacy_does_not_infer_env_mode",()->{OkHttpClient c=success(()->client(ABSENT,Map.of("https_proxy","malformed synthetic value")));check(c.proxy()==null,"legacy fixed-proxy behavior unchanged");});
    test("PX07_factory_never_reads_API_keys",()->{Map<String,String> env=new HashMap<>(){@Override public String get(Object key){if(String.valueOf(key).contains("API_KEY"))throw new AssertionError("FACTORY_MUST_NOT_READ_KEYS");return super.get(key);}};env.put("https_proxy","http://synthetic-proxy.invalid:80");success(()->client("ENVIRONMENT_HTTPS_PROXY",env));});
    test("PX08_missing_proxy_rejects_no_DIRECT",()->reject(()->client("ENVIRONMENT_HTTPS_PROXY",Map.of())));
    test("PX09_invalid_lower_never_falls_back_upper",()->reject(()->client("ENVIRONMENT_HTTPS_PROXY",Map.of("https_proxy","","HTTPS_PROXY","http://synthetic-valid.invalid:80"))));
    for(String invalid:List.of(" http://synthetic.invalid:80","http://synthetic.invalid:80 ","https://synthetic.invalid:443","socks5://synthetic.invalid:1080","synthetic.invalid:80","http://synthetic.invalid:0","http://synthetic.invalid:65536","http://synthetic.invalid:-1","http://synthetic.invalid:80/private","http://synthetic.invalid:80?x=private","http://synthetic.invalid:80#private","http://synthetic-user:synthetic-password@synthetic.invalid:80","http://synthetic-user@synthetic.invalid:80","http://synthetic.invalid:80\n","http://synthetic.invalid:80\u0000")){
      String bad=invalid;int ordinal=TESTS.size();test("PX_BAD_URI_"+ordinal,()->reject(()->client("ENVIRONMENT_HTTPS_PROXY",Map.of("https_proxy",bad,"HTTPS_PROXY","http://synthetic-fallback.invalid:80"))));
    }
    for(Object mode:Arrays.asList("",null,"DIRECT","ENVIRONMENT_HTTPS_PROXY ",7)){Object bad=mode;int ordinal=TESTS.size();test("PX_BAD_MODE_"+ordinal,()->reject(()->client(bad,Map.of("https_proxy","http://synthetic.invalid:80"))));}
    test("PB01_new_mode_valid_profile_and_hold_persisted",()->{try(Fixture f=new Fixture()){f.base.replies(Issue130SyntheticContractTest.CLEAR);Map<String,Object> r=success(f::run);eq("COMPLETED",r.get("status"),"new current hold profile accepted");eq("1.79322880",r.get("maximumCumulativeReservedUsd"),"new cumulative hold in result");Map<String,Object> held=map(JSON.readValue(Files.readAllBytes(f.base.output.resolve(".suite-consumed.json")),Map.class));eq("1.56254208",held.get("priorHeldUsd"),"previous UNKNOWN fullhold retained");eq("1.79322880",held.get("maximumCumulativeReservedUsd"),"selected new total persisted");eq(16,f.base.proxy.sent.size(),"exact16 synthetic calls");}});
    test("PB02_new_mode_rejects_old_hold_rollback",()->{try(Fixture f=new Fixture()){Map<String,Object> c=map(f.base.config.get("costProfile"));c.put("priorHeldUsd","1.33185536");c.put("maximumCumulativeReservedUsd","1.56254208");f.base.seal();f.deny();}});
    test("PB03_new_mode_requires_previous_evidence",()->{try(Fixture f=new Fixture()){f.base.config.remove("priorReservation");f.base.seal();f.deny();}});
    test("PB04_previous_evidence_hash_mismatch",()->{try(Fixture f=new Fixture()){Files.writeString(f.evidence,"{}");f.deny();}});
    test("PB05_previous_evidence_amount_mismatch",()->{try(Fixture f=new Fixture()){Map<String,Object> p=previous();p.put("cumulativeReservationUsd","1.33185536");f.evidence(p);f.deny();}});
    test("PB06_previous_status_mismatch",()->{try(Fixture f=new Fixture()){Map<String,Object> p=previous();p.put("status","RELEASED");f.evidence(p);f.deny();}});
    for(Object refund:List.of(1,"0",0.0,0.1,true)){Object bad=refund;int ordinal=TESTS.size();test("PB_REFUNDS_invalid_"+ordinal,()->{try(Fixture f=new Fixture()){Map<String,Object> p=previous();p.put("refunds",bad);f.evidence(p);f.deny();}});}
    test("PB07_unknown_mode_not_legacy_profile",()->{try(Issue130SyntheticContractTest.Fixture f=new Issue130SyntheticContractTest.Fixture()){f.config.put("transportMode","UNREVIEWED_MODE");f.seal();f.failClosed();}});
    test("PB08_new_profile_connection_failure_holds_total",()->{try(Fixture f=new Fixture()){f.base.network().enqueue(new Issue130SyntheticTlsProxy.Reply(200,new byte[0],Map.of(),0,true));Map<String,Object> r=success(f::run);eq("STOPPED",r.get("status"),"failed A stops");eq("1.79322880",r.get("maximumCumulativeReservedUsd"),"unknown whole new hold retained");check(Files.readString(f.base.output.resolve(".suite-consumed.json")).contains("1.79322880"),"no refund on connection failure");eq(1,f.base.proxy.sent.size(),"no retry or next case");}});
    test("PN01_parameterized_TLS_valid_SAN_production_classifier_control",()->tlsControl("api.openai.com",true));
    test("PN02_parameterized_TLS_trusted_wrong_SAN_control",()->tlsControl("synthetic-other.invalid",false));
    test("PC01_actual_Python_CLI_ENV_proxy_TLS16_and_replay0",()->cli(true,"api.openai.com",200,true));
    test("PC02_untrusted_CA_never_emits_POST_or_DIRECT",()->cli(false,"api.openai.com",200,false));
    test("PC03_wrong_hostname_even_trusted_CA_rejected",()->cli(true,"synthetic-other.invalid",200,false));
    test("PC04_503_retryafter0_one_POST",()->cli(true,"api.openai.com",503,false));
    test("PC05_redirect_one_POST_no_follow",()->cli(true,"api.openai.com",302,false));
  }
  static void tlsControl(String host,boolean valid)throws Exception{
    Path root=Issue130SyntheticContractTest.privateRoot();
    try(Issue130EnvironmentTlsProxy proxy=new Issue130EnvironmentTlsProxy(root,host)){
      proxy.enqueue(Issue130EnvironmentTlsProxy.Reply.json(200,Issue130SyntheticContractTest.CLEAR));
      var classifier=new com.meetme.server.precheck.adapter.output.classification.OpenAiAmbiguityClassifier(Issue130SyntheticContractTest.KEY,JSON,new com.meetme.server.precheck.adapter.output.classification.AmbiguityClassifierOptions(URI.create("https://api.openai.com/v1/decisions"),true,0.8),proxy.client(),null,null);
      var request=new com.meetme.server.precheck.application.port.output.AmbiguityClassificationRequest("synthetic TLS fixture control",java.time.Instant.parse("2026-10-18T03:00:00Z"),java.time.ZoneId.of("Asia/Seoul"),"ko-KR",java.time.LocalDate.of(2026,10,18),java.time.LocalDate.of(2026,10,31),java.time.Duration.ofSeconds(2),null);
      if(valid){var result=classifier.classify(request);eq("CLEAR",result.getAssessment().name(),"valid SAN actual classifier control");eq(137L,result.getInputTokens(),"known usage control");eq(1,proxy.sent.size(),"valid SAN physical POST1");check(proxy.fixtureFailures.isEmpty(),"valid TLS fixture exercised");}
      else{try{classifier.classify(request);throw new AssertionError("TRUSTED_WRONG_SAN_MUST_FAIL");}catch(com.meetme.server.precheck.application.port.output.AmbiguityClassificationException expected){}eq(1,proxy.connections.get(),"wrong SAN CONNECT1");eq(0,proxy.sent.size(),"wrong SAN POST0");}
    }
  }
  static void cli(boolean trusted,String certificateHost,int status,boolean complete)throws Exception{
    try(Fixture f=new Fixture();Issue130EnvironmentTlsProxy proxy=new Issue130EnvironmentTlsProxy(f.base.root,certificateHost)){
      for(int i=0;i<16;i++){var reply=Issue130EnvironmentTlsProxy.Reply.json(status,status==200?Issue130SyntheticContractTest.CLEAR:"{}");if(status==503)reply=reply.header("Retry-After","0");if(status==302)reply=reply.header("Location","https://api.openai.com/v1/decisions");proxy.enqueue(reply);}
      Path boot=Files.createDirectory(f.base.root.resolve("boot-guard"));try(InputStream in=Issue130SyntheticNetworkGuard.class.getResourceAsStream("/Issue130SyntheticNetworkGuard.class")){Files.copy(in,boot.resolve("Issue130SyntheticNetworkGuard.class"));}
      Map<String,String> env=new HashMap<>(f.base.environment());env.put("https_proxy","http://127.0.0.1:"+proxy.port());env.put("HTTPS_PROXY","http://synthetic-unused.invalid:80");
      String options="-Xbootclasspath/a:"+boot+" -Djava.security.manager=Issue130SyntheticNetworkGuard";
      if(trusted)options+=" -Djavax.net.ssl.trustStore="+f.base.root.resolve("synthetic-proxy.p12")+" -Djavax.net.ssl.trustStorePassword=synthetic-only-passphrase";
      env.put("JAVA_TOOL_OPTIONS",options);
      Path cp=map(f.base.config.get("runtimeFiles")).keySet().stream().filter(s->s.endsWith("/runtime.classpath")).map(Path::of).findFirst().orElseThrow();
      List<String> command=complete?List.of("python3",REPO.resolve("evaluation/issue130/run_representative_decisions.py").toString(),"--plan",f.base.plan.toString(),"--index",f.base.index.toString(),"--output",f.base.output.toString(),"--classpath-file",cp.toString(),"--execute-frozen"):List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",System.getProperty("java.class.path"),"Issue130RepresentativeDecisionsCli","run-suite",f.base.plan.toString(),f.base.index.toString(),f.base.output.toString());
      int exit=child(command,env,complete);if(complete){eq(0,exit,"whole Python to Java ENV transport succeeds");eq(16,proxy.sent.size(),"16 canonical TLS proxy POSTs");int replay=child(command,env,false);check(replay!=0,"same plan replay rejected");eq(16,proxy.sent.size(),"replay adds0 POST");eq(1,proxy.maximumActive.get(),"serial1");}
      else{check(exit!=0||"STOPPED".equals(lastReport.get("status")),"TLS/status failure must not be success");eq(1,proxy.connections.get(),"one CONNECT no fallback/retry");eq(trusted&&certificateHost.equals("api.openai.com")?1:0,proxy.sent.size(),"TLS verification before POST or status one POST");}
    }
  }
  static Map<String,Object> lastReport=Map.of();
  static int child(List<String> command,Map<String,String> selected,boolean expectSuccess)throws Exception{
    ProcessBuilder b=new ProcessBuilder(command);b.environment().remove("https_proxy");b.environment().remove("HTTPS_PROXY");b.environment().putAll(selected);b.environment().put("GEMINI_API_KEY","");Process process=b.start();
    if(!process.waitFor(30,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("SYNTHETIC_CHILD_TIMEOUT");}
    String stdout=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8),stderr=new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8);
    check(!stdout.contains("SYNTHETIC_PRIVATE_RAW_")&&!stdout.contains(Issue130SyntheticContractTest.KEY)&&!stdout.contains("http://127.0.0.1:"),"safe CLI stdout excludes proxy/raw/key");
    try{lastReport=map(JSON.readValue(stdout,Map.class));}catch(Exception ignored){lastReport=Map.of();}
    check(!stderr.contains(Issue130SyntheticContractTest.KEY)&&!stderr.contains(selected.getOrDefault("https_proxy","FORBIDDEN_PROXY_SENTINEL")),"safe stderr excludes proxy/key");
    if(expectSuccess)check(process.exitValue()==0&&stdout.contains("COMPLETED"),"NEW_MODE_WHOLE_CLI_MUST_SUCCEED");return process.exitValue();
  }
  public static void main(String[] args)throws Exception{
    System.setSecurityManager(new Issue130SyntheticNetworkGuard());
    if(args.length==2&&args[0].equals("--export-environment-fixture")){try(Fixture f=new Fixture()){Issue130SyntheticContractTest.write(Path.of(args[1]),Map.of("plan",f.base.plan.toString(),"index",f.base.index.toString(),"output",f.base.output.toString(),"root",f.base.root.toString(),"config",f.base.config));}return;}
    List<String> names=args.length==0?List.copyOf(TESTS.keySet()):List.of(args);int pass=0,fail=0,error=0;
    for(String name:names){try{TESTS.get(name).run();pass++;System.out.println(JSON.writeValueAsString(Map.of("test",name,"status","PASS")));}catch(AssertionError e){fail++;System.out.println(JSON.writeValueAsString(Map.of("test",name,"status","ASSERTION_FAILURE","reason",String.valueOf(e.getMessage()).startsWith("TDD RED:")?e.getMessage():"PUBLIC_PROXY_CONTRACT_ASSERTION")));}catch(Throwable e){error++;System.out.println(JSON.writeValueAsString(Map.of("test",name,"status","SETUP_OR_UNEXPECTED_ERROR","type",e.getClass().getSimpleName())));}}
    System.out.println(JSON.writeValueAsString(Map.of("tests",names.size(),"passed",pass,"assertionFailures",fail,"errors",error,"paidCalls",0,"sealedDataRead",false)));if(fail+error!=0)System.exit(1);
  }
}
