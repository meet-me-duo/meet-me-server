import okhttp3.*;
import javax.net.ssl.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.net.*;
import java.util.*;
import java.time.Clock;
import java.util.concurrent.TimeUnit;

/** Independent pre-RED CA contract. Synthetic data only; external sockets forbidden. */
@SuppressWarnings({"unchecked","removal"})
public final class Issue130EnvironmentCaContractTest {
  interface Work { void run() throws Exception; }
  static final Map<String,Work> TESTS=new LinkedHashMap<>();
  static Issue130EnvironmentCaTlsFixture ca;
  static void test(String id,Work w){TESTS.put(id,w);}
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
  static void eq(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message);}
  static Map<String,Object> meta()throws Exception{return new LinkedHashMap<>(Map.of("environmentVariable","NODE_EXTRA_CA_CERTS","pemFileSha256",Issue130SyntheticContractTest.sha(ca.pem),"certificateDerSha256",Issue130SyntheticContractTest.sha(ca.certificate.getEncoded())));}
  static Map<String,String> env(Path path){Map<String,String> e=new HashMap<>();e.put("https_proxy","http://127.0.0.1:"+ca.port());if(path!=null)e.put("NODE_EXTRA_CA_CERTS",path.toString());return e;}
  static Path plan(Object value,boolean present,boolean proxy)throws Exception{Path p=Issue130SyntheticContractTest.privateRoot().resolve("transport.json");Map<String,Object> m=new LinkedHashMap<>();if(proxy)m.put("transportMode","ENVIRONMENT_HTTPS_PROXY");if(present)m.put("environmentCa",value);Issue130SyntheticContractTest.write(p,m);return p;}
  static OkHttpClient client(Object value,Map<String,String> e)throws Exception{return Issue130RepresentativeDecisionsCli.mainClient(plan(value,true,true),e);}
  static OkHttpClient success(java.util.concurrent.Callable<OkHttpClient> w)throws Exception{try{return w.call();}catch(Exception e){throw new AssertionError("VALID_SYNTHETIC_CA_MUST_BE_ACCEPTED");}}
  static void reject(Work w)throws Exception{try{w.run();throw new AssertionError("INVALID_CA_MUST_BE_REJECTED_BEFORE_HTTP");}catch(Issue130RepresentativeDecisionsCli.EvaluationException expected){eq("CONFIGURATION",expected.code(),"bounded typed configuration error");check(expected.getCause()==null,"no raw path or certificate cause");}}
  static Path file(byte[] bytes)throws Exception{Path p=Issue130SyntheticContractTest.privateRoot().resolve("ca.pem");Files.write(p,bytes);return p;}
  static void badFile(Path p)throws Exception{Map<String,Object> m=meta();if(Files.isRegularFile(p))m.put("pemFileSha256",Issue130SyntheticContractTest.sha(p));reject(()->client(m,env(p)));}
  static Set<String> issuers(X509TrustManager trust)throws Exception{Set<String> s=new HashSet<>();for(var c:trust.getAcceptedIssuers())s.add(Issue130SyntheticContractTest.sha(c.getEncoded()));return s;}
  static boolean request(OkHttpClient c,String method,String url)throws Exception{try(Response r=c.newCall(new Request.Builder().url(url).method(method,null).build()).execute()){return r.code()==200;}catch(java.io.IOException expected){return false;}}
  static void fullSuite(boolean invalid)throws Exception{
    try(Issue130EnvironmentProxyContractTest.Fixture f=new Issue130EnvironmentProxyContractTest.Fixture()){
      Map<String,Object> m=meta();if(invalid)m.put("pemFileSha256","0".repeat(64));f.base.config.put("environmentCa",m);f.base.seal();
      f.base.replies(Issue130SyntheticContractTest.CLEAR);Map<String,String> e=new HashMap<>(f.base.environment());e.putAll(env(ca.pem));
      if(invalid){reject(()->f.base.run(e));eq(0,f.base.network().sent.size(),"injected transport cannot bypass CA plan validation");check(!Files.exists(f.base.output.resolve(".suite-consumed.json")),"invalid CA consumes no suite");}
      else {Map<String,Object> result;try{result=f.base.run(e);}catch(Exception ex){throw new AssertionError("VALID_CA_RUN_PLAN_MUST_SUCCEED");}eq("COMPLETED",result.get("status"),"valid plan retains injected synthetic transport");eq(16,f.base.network().sent.size(),"exact16 no transport replacement");}
    }
  }
  static {
    test("CA01_valid_client_adds_one_CA_retains_JDK_anchors",()->{OkHttpClient before=new OkHttpClient(),c=success(()->client(meta(),env(ca.pem)));Set<String> expected=issuers(before.x509TrustManager());expected.add(Issue130SyntheticContractTest.sha(ca.certificate.getEncoded()));eq(expected,issuers(c.x509TrustManager()),"JDK trust retained plus exactly one CA");check(!c.retryOnConnectionFailure()&&!c.followRedirects()&&!c.followSslRedirects(),"retry and redirects remain zero");check(c.hostnameVerifier()==before.hostnameVerifier(),"default hostname verifier preserved");});
    test("CA02_client_only_no_global_SSL_or_properties_change",()->{SSLContext ssl=SSLContext.getDefault();var factory=HttpsURLConnection.getDefaultSSLSocketFactory();var verifier=HttpsURLConnection.getDefaultHostnameVerifier();Properties before=(Properties)System.getProperties().clone();Set<String> issuers=issuers(new OkHttpClient().x509TrustManager());success(()->client(meta(),env(ca.pem)));check(ssl==SSLContext.getDefault()&&factory==HttpsURLConnection.getDefaultSSLSocketFactory()&&verifier==HttpsURLConnection.getDefaultHostnameVerifier(),"global TLS unchanged");eq(before,System.getProperties(),"no JVM security property mutation");eq(issuers,issuers(new OkHttpClient().x509TrustManager()),"other client trust unchanged");});
    test("CA03_absence_unchanged_even_CA_env_present",()->{OkHttpClient c=Issue130RepresentativeDecisionsCli.mainClient(plan(null,false,true),env(ca.pem));check(!issuers(c.x509TrustManager()).contains(Issue130SyntheticContractTest.sha(ca.certificate.getEncoded())),"no inferred CA activation");});
    test("CA04_no_keys_or_network_during_factory",()->{Map<String,String> e=new HashMap<>(env(ca.pem)){@Override public String get(Object key){if(String.valueOf(key).contains("API_KEY"))throw new AssertionError("FACTORY_KEY_READ_FORBIDDEN");return super.get(key);}};int before=ca.connections.get();success(()->client(meta(),e));eq(before,ca.connections.get(),"factory network0");});
    for(String field:List.of("environmentVariable","pemFileSha256","certificateDerSha256")){
      test("CA05_missing_"+field,()->{Map<String,Object> m=meta();m.remove(field);reject(()->client(m,env(ca.pem)));});
      test("CA06_wrong_type_"+field,()->{Map<String,Object> m=meta();m.put(field,7);reject(()->client(m,env(ca.pem)));});
    }
    test("CA07_null_optin_reject",()->reject(()->client(null,env(ca.pem))));
    test("CA08_unknown_field_reject",()->{var m=meta();m.put("skipVerification",true);reject(()->client(m,env(ca.pem)));});
    test("CA09_alternate_env_variable_reject",()->{var m=meta();m.put("environmentVariable","SYNTHETIC_OTHER_CA");reject(()->client(m,env(ca.pem)));});
    test("CA10_optin_without_ENV_proxy_reject",()->reject(()->Issue130RepresentativeDecisionsCli.mainClient(plan(meta(),true,false),env(ca.pem))));
    test("CA11_env_file_missing_reject",()->reject(()->client(meta(),env(null))));
    for(String value:List.of("", " ", "relative.pem"))test("CA12_bad_path_"+TESTS.size(),()->reject(()->{var e=env(ca.pem);e.put("NODE_EXTRA_CA_CERTS",value);client(meta(),e);}));
    test("CA13_pem_pin_mismatch",()->{var m=meta();m.put("pemFileSha256","0".repeat(64));reject(()->client(m,env(ca.pem)));});
    test("CA14_DER_pin_mismatch",()->{var m=meta();m.put("certificateDerSha256","0".repeat(64));reject(()->client(m,env(ca.pem)));});
    test("CA15_uppercase_hash_reject",()->{var m=meta();m.put("pemFileSha256",((String)m.get("pemFileSha256")).toUpperCase(Locale.ROOT));reject(()->client(m,env(ca.pem)));});
    test("CA16_leaf_symlink_reject",()->{Path p=Issue130SyntheticContractTest.privateRoot().resolve("link.pem");Files.createSymbolicLink(p,ca.pem);reject(()->client(meta(),env(p)));});
    test("CA17_parent_symlink_reject",()->{Path p=Issue130SyntheticContractTest.privateRoot().resolve("alias");Files.createSymbolicLink(p,ca.pem.getParent());reject(()->client(meta(),env(p.resolve(ca.pem.getFileName()))));});
    test("CA18_directory_reject",()->badFile(Issue130SyntheticContractTest.privateRoot()));
    for(String bytes:List.of("", "not a certificate", "-----BEGIN CERTIFICATE-----\n!invalid!\n-----END CERTIFICATE-----\n"))test("CA19_malformed_"+TESTS.size(),()->badFile(file(bytes.getBytes(StandardCharsets.US_ASCII))));
    test("CA20_concatenated_two_certificates_reject",()->{byte[] b=Files.readAllBytes(ca.pem);badFile(file((new String(b,StandardCharsets.US_ASCII)+new String(b,StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII)));});
    test("CA21_nonwhitespace_suffix_reject",()->badFile(file((Files.readString(ca.pem)+"unexpected").getBytes(StandardCharsets.US_ASCII))));
    test("CA22_binary_DER_reject",()->badFile(file(ca.certificate.getEncoded())));
    test("CA23_oversize16385_reject",()->badFile(file(new byte[16385])));
    test("CA24_outside_ASCII_whitespace_valid",()->{Path p=file((" \t\r\n"+Files.readString(ca.pem)+" \t\r\n").getBytes(StandardCharsets.US_ASCII));var m=meta();m.put("pemFileSha256",Issue130SyntheticContractTest.sha(p));success(()->client(m,env(p)));});
    for(String kind:List.of("leaf","expired","future","noKeyCertSign"))test("CA25_invalid_certificate_"+kind,()->{try(var invalid=new Issue130EnvironmentCaTlsFixture(Issue130SyntheticContractTest.privateRoot(),"api.openai.com",kind)){var m=meta();m.put("pemFileSha256",Issue130SyntheticContractTest.sha(invalid.pem));m.put("certificateDerSha256",Issue130SyntheticContractTest.sha(invalid.certificate.getEncoded()));reject(()->client(m,env(invalid.pem)));}});
    test("CA26_invalid_self_signature_reject",()->{byte[] der=ca.certificate.getEncoded();der[der.length-1]^=1;Path p=file(("-----BEGIN CERTIFICATE-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(der)+"\n-----END CERTIFICATE-----\n").getBytes(StandardCharsets.US_ASCII));var m=meta();m.put("pemFileSha256",Issue130SyntheticContractTest.sha(p));m.put("certificateDerSha256",Issue130SyntheticContractTest.sha(der));reject(()->client(m,env(p)));});
    test("CA27_valid_HEAD_canonical_root_no_auth",()->{int n=ca.sent.size();ca.enqueue(Issue130EnvironmentCaTlsFixture.Reply.json(200,""));boolean ok=request(success(()->client(meta(),env(ca.pem))),"HEAD","https://api.openai.com/");check(ok,"normal trust canonical HEAD must succeed");eq(n+1,ca.sent.size(),"one synthetic HEAD");var sent=ca.sent.get(n);eq("HEAD",sent.method(),"HEAD only");eq("/",sent.path(),"root only");check(!sent.headers().containsKey("authorization")&&sent.body().length==0,"no auth or request body");});
    test("CA28_same_CA_wrong_hostname_reject",()->{try(var wrong=new Issue130EnvironmentCaTlsFixture(Issue130SyntheticContractTest.privateRoot(),"synthetic-wrong.invalid","valid")){var m=meta();m.put("pemFileSha256",Issue130SyntheticContractTest.sha(wrong.pem));m.put("certificateDerSha256",Issue130SyntheticContractTest.sha(wrong.certificate.getEncoded()));var e=env(wrong.pem);e.put("https_proxy","http://127.0.0.1:"+wrong.port());check(!request(success(()->client(m,e)),"HEAD","https://api.openai.com/"),"trusted wrong SAN rejected");eq(0,wrong.sent.size(),"wrong SAN sends no HTTP");}});
    test("CA29_absent_optin_untrusted_CA_reject",()->{int n=ca.sent.size();OkHttpClient c=Issue130RepresentativeDecisionsCli.mainClient(plan(null,false,true),env(ca.pem));check(!request(c,"HEAD","https://api.openai.com/"),"untrusted self-signed CA rejected");eq(n,ca.sent.size(),"no HTTP on untrusted TLS");});
    for(String url:List.of("https://synthetic-other.invalid/","https://api.openai.com:444/","http://api.openai.com/"))test("CA30_origin_confinement_"+TESTS.size(),()->{int before=ca.connections.get();OkHttpClient c=success(()->client(meta(),env(ca.pem)));check(!request(c,"HEAD",url),"noncanonical origin rejected");eq(before,ca.connections.get(),"foreign origin blocked before network");});
    test("CA31_runplan_injected_transport_cannot_bypass_invalid_CA",()->fullSuite(true));
    test("CA32_runplan_valid_CA_preserves_injected_transport",()->fullSuite(false));
  }
  public static void main(String[] args)throws Exception {
    System.setSecurityManager(new Issue130SyntheticNetworkGuard());
    try(var fixture=new Issue130EnvironmentCaTlsFixture(Issue130SyntheticContractTest.privateRoot(),"api.openai.com","valid")){
      ca=fixture;
      if(args.length==2&&args[0].equals("--export-ca-fixture")){try(var f=new Issue130EnvironmentProxyContractTest.Fixture()){f.base.config.put("environmentCa",meta());f.base.seal();Issue130SyntheticContractTest.write(Path.of(args[1]),Map.of("plan",f.base.plan.toString(),"index",f.base.index.toString(),"output",f.base.output.toString(),"pem",ca.pem.toString(),"metadata",meta()));}return;}
      int pass=0,fail=0,error=0;List<String> names=args.length==0?List.copyOf(TESTS.keySet()):List.of(args);
      for(String name:names){try{TESTS.get(name).run();pass++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",name,"status","PASS")));}catch(AssertionError e){fail++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",name,"status","ASSERTION_FAILURE","reason","ENVIRONMENT_CA_PUBLIC_CONTRACT")));}catch(Throwable e){error++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",name,"status","SETUP_OR_UNEXPECTED_ERROR","type",e.getClass().getSimpleName())));}}
      System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("tests",names.size(),"passed",pass,"assertionFailures",fail,"errors",error,"paidCalls",0,"sealedDataRead",false)));if(fail+error!=0)System.exit(1);
    }
  }
}
