import okhttp3.*;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

/** Independent fixed-Google transport oracle; synthetic CA/TLS only, external sockets denied. */
@SuppressWarnings({"unchecked","removal"})
public final class Issue130StandaloneClientTest {
    interface Work { void run() throws Exception; }
    static final Map<String,Work> TESTS=new LinkedHashMap<>();
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String HOST="generativelanguage.googleapis.com";
    static final String COUNT="/v1beta/models/gemini-3.8-flash:countTokens";
    static final String GENERATE="/v1beta/models/gemini-3.8-flash:generateContent";
    static final String KEY="synthetic-B-key-MUST-NOT-LEAK";
    static Issue130StandaloneGoogleTlsFixture ca;
    static void test(String name,Work body){TESTS.put(name,body);}
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void eq(Object expected,Object actual,String message){check(Objects.equals(expected,actual),message);}
    static String sha(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    static String sha(Path p)throws Exception{return sha(Files.readAllBytes(p));}
    static Path root()throws Exception{return Files.createTempDirectory("issue130-b-public-synthetic-",PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));}
    static void write(Path path,Object value)throws Exception{Files.write(path,JSON.writeValueAsBytes(value));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}
    static Map<String,Object> metadata(Issue130StandaloneGoogleTlsFixture f)throws Exception{return new LinkedHashMap<>(Map.of("environmentVariable","NODE_EXTRA_CA_CERTS","pemFileSha256",sha(f.pem),"certificateDerSha256",sha(f.certificate.getEncoded())));}
    static Map<String,String> environment(Issue130StandaloneGoogleTlsFixture f){return new HashMap<>(Map.of("https_proxy","http://127.0.0.1:"+f.port(),"NODE_EXTRA_CA_CERTS",f.pem.toString()));}
    static Path plan(Object meta,boolean present,boolean proxy)throws Exception{Path p=root().resolve("transport.json");Map<String,Object> value=new LinkedHashMap<>();if(present)value.put("environmentCa",meta);if(proxy)value.put("transportMode","ENVIRONMENT_HTTPS_PROXY");write(p,value);return p;}
    static OkHttpClient client(Object meta,Map<String,String> env)throws Exception{return Issue130StandaloneGenerationCli.mainClient(plan(meta,true,true),env);}
    static OkHttpClient valid()throws Exception{return client(metadata(ca),environment(ca));}
    static Set<String> issuers(X509TrustManager tm)throws Exception{Set<String> out=new HashSet<>();for(var certificate:tm.getAcceptedIssuers())out.add(sha(certificate.getEncoded()));return out;}
    static void reject(Work body)throws Exception{try{body.run();throw new AssertionError("INVALID_CLIENT_CONFIGURATION_MUST_REJECT");}catch(Issue130StandaloneGenerationCli.EvaluationException failure){eq("CONFIGURATION",failure.code(),"typed local configuration failure");check(failure.getCause()==null&&!failure.toString().contains(KEY),"safe failure only");}}
    static boolean post(OkHttpClient c,String method,String url)throws Exception{RequestBody body=Set.of("GET","HEAD").contains(method)?null:RequestBody.create("{}",MediaType.get("application/json"));try(Response response=c.newCall(new Request.Builder().url(url).method(method,body).header("x-goog-api-key",KEY).build()).execute()){return response.code()==200;}catch(java.io.IOException failure){return false;}}
    static void blocked(String method,String url)throws Exception{int n=ca.connections.get(),sent=ca.sent.size();check(!post(valid(),method,url),"unapproved request must fail");eq(n,ca.connections.get(),"rejected before socket");eq(sent,ca.sent.size(),"HTTP zero");}
    static {
        test("BT01_valid_CA_union_and_no_retry",()->{OkHttpClient before=new OkHttpClient(),client=valid();Set<String> expected=issuers(before.x509TrustManager());expected.add(sha(ca.certificate.getEncoded()));eq(expected,issuers(client.x509TrustManager()),"default anchors plus exactly one CA");check(client.hostnameVerifier()==before.hostnameVerifier(),"normal hostname verifier");check(!client.retryOnConnectionFailure()&&!client.followRedirects()&&!client.followSslRedirects(),"all retry/redirect switches off");});
        test("BT02_no_global_TLS_or_properties_mutation",()->{SSLContext context=SSLContext.getDefault();var factory=HttpsURLConnection.getDefaultSSLSocketFactory();var verifier=HttpsURLConnection.getDefaultHostnameVerifier();Properties properties=(Properties)System.getProperties().clone();Set<String> expected=issuers(new OkHttpClient().x509TrustManager());valid();check(context==SSLContext.getDefault()&&factory==HttpsURLConnection.getDefaultSSLSocketFactory()&&verifier==HttpsURLConnection.getDefaultHostnameVerifier(),"global TLS objects unchanged");eq(properties,System.getProperties(),"JVM properties unchanged");eq(expected,issuers(new OkHttpClient().x509TrustManager()),"other clients unchanged");});
        test("BT03_factory_never_reads_keys_or_connects",()->{int before=ca.connections.get();Map<String,String> env=new HashMap<>(environment(ca)){@Override public String get(Object key){if(String.valueOf(key).contains("API_KEY"))throw new AssertionError("FACTORY_KEY_READ");return super.get(key);}};client(metadata(ca),env);eq(before,ca.connections.get(),"no factory connection");});
        test("BT04_valid_count_POST_normal_PKIX",()->{int before=ca.sent.size();ca.enqueue(Issue130StandaloneGoogleTlsFixture.Reply.json(200,"{}"));check(post(valid(),"POST","https://"+HOST+COUNT),"count allowed using normal TLS");eq(before+1,ca.sent.size(),"one HTTP");eq(COUNT,ca.sent.get(before).path(),"exact path");});
        test("BT05_valid_generation_POST_normal_PKIX",()->{int before=ca.sent.size();ca.enqueue(Issue130StandaloneGoogleTlsFixture.Reply.json(200,"{}"));check(post(valid(),"POST","https://"+HOST+GENERATE),"generation allowed using normal TLS");eq(before+1,ca.sent.size(),"one HTTP");eq(GENERATE,ca.sent.get(before).path(),"exact path");});
        test("BT06_absence_does_not_infer_environment_CA",()->{OkHttpClient client=Issue130StandaloneGenerationCli.mainClient(plan(null,false,true),environment(ca));check(!issuers(client.x509TrustManager()).contains(sha(ca.certificate.getEncoded())),"no inferred CA");int before=ca.sent.size();check(!post(client,"POST","https://"+HOST+COUNT),"untrusted CA rejected");eq(before,ca.sent.size(),"no HTTP");});
        test("BT07_trusted_CA_wrong_SAN_HTTP_zero",()->{try(var wrong=new Issue130StandaloneGoogleTlsFixture(root(),"synthetic-wrong.invalid","valid")){OkHttpClient client=client(metadata(wrong),environment(wrong));check(!post(client,"POST","https://"+HOST+COUNT),"wrong SAN must fail");eq(0,wrong.sent.size(),"normal hostname rejects before HTTP");}});
        for(String url:List.of("https://api.openai.com"+COUNT,"https://synthetic-other.invalid"+COUNT,"https://"+HOST+":444"+COUNT,"http://"+HOST+COUNT,"https://user:secret@"+HOST+COUNT,"https://"+HOST+COUNT+"?key="+KEY,"https://"+HOST+COUNT+"#ignored","https://"+HOST+COUNT+"?","https://"+HOST+COUNT+"#","https://"+HOST+"/v1beta/models/gemini-3.8-flash%3AcountTokens","https://"+HOST+"/v1beta/models/other:countTokens","https://"+HOST+"/v1beta/models/gemini-3.8-flash:embedContent","https://"+HOST+"/"))test("BT08_forbidden_URL_"+TESTS.size(),()->blocked("POST",url));
        for(String method:List.of("GET","HEAD","PUT","DELETE"))test("BT09_forbidden_method_"+method,()->blocked(method,"https://"+HOST+COUNT));
        for(String field:List.of("environmentVariable","pemFileSha256","certificateDerSha256")){
            test("BT10_missing_metadata_"+field,()->{var m=metadata(ca);m.remove(field);reject(()->client(m,environment(ca)));});
            test("BT11_wrong_type_metadata_"+field,()->{var m=metadata(ca);m.put(field,7);reject(()->client(m,environment(ca)));});
        }
        test("BT12_explicit_null_rejected",()->reject(()->client(null,environment(ca))));
        test("BT13_unknown_metadata_rejected",()->{var m=metadata(ca);m.put("trustAll",true);reject(()->client(m,environment(ca)));});
        test("BT14_CA_without_proxy_mode_rejected",()->reject(()->Issue130StandaloneGenerationCli.mainClient(plan(metadata(ca),true,false),environment(ca))));
        test("BT15_bad_PEM_pin_rejected",()->{var m=metadata(ca);m.put("pemFileSha256","0".repeat(64));reject(()->client(m,environment(ca)));});
        test("BT16_bad_DER_pin_rejected",()->{var m=metadata(ca);m.put("certificateDerSha256","0".repeat(64));reject(()->client(m,environment(ca)));});
        test("BT17_alternate_CA_source_rejected",()->{var m=metadata(ca);m.put("environmentVariable","SYNTHETIC_OTHER_CA");reject(()->client(m,environment(ca)));});
        test("BT18_CA_file_symlink_rejected",()->{Path link=root().resolve("ca.pem");Files.createSymbolicLink(link,ca.pem);var env=environment(ca);env.put("NODE_EXTRA_CA_CERTS",link.toString());reject(()->client(metadata(ca),env));});
        test("BT19_CA_parent_symlink_rejected",()->{Path link=root().resolve("alias");Files.createSymbolicLink(link,ca.pem.getParent());var env=environment(ca);env.put("NODE_EXTRA_CA_CERTS",link.resolve(ca.pem.getFileName()).toString());reject(()->client(metadata(ca),env));});
        for(String kind:List.of("leaf","expired","future","noKeyCertSign"))test("BT20_invalid_crypto_"+kind,()->{try(var wrong=new Issue130StandaloneGoogleTlsFixture(root(),HOST,kind)){reject(()->client(metadata(wrong),environment(wrong)));}});
        test("BT21_redirect_is_not_followed",()->{int before=ca.sent.size();ca.enqueue(Issue130StandaloneGoogleTlsFixture.Reply.json(302,"{}").header("Location","https://"+HOST+GENERATE));check(!post(valid(),"POST","https://"+HOST+COUNT),"302 not success");eq(before+1,ca.sent.size(),"no follow-up request");});
        test("BT22_503_retry_after_zero_single_exchange",()->{int before=ca.sent.size();ca.enqueue(Issue130StandaloneGoogleTlsFixture.Reply.json(503,"{}").header("Retry-After","0"));check(!post(valid(),"POST","https://"+HOST+COUNT),"503 preserved");eq(before+1,ca.sent.size(),"zero hidden retry");});
        test("BT23_Dependencies_environment_is_immutable_snapshot",()->{Map<String,String> env=new HashMap<>(Map.of("SYNTHETIC","before"));var dependencies=new Issue130StandaloneGenerationCli.Dependencies(new OkHttpClient(),System::nanoTime,java.time.Clock.systemUTC(),env);env.put("SYNTHETIC","after");eq("before",dependencies.environment().get("SYNTHETIC"),"defensive copy");try{dependencies.environment().put("x","y");throw new AssertionError("mutable environment");}catch(UnsupportedOperationException expected){}});
    }
    public static void main(String[] args)throws Exception {
        System.setSecurityManager(new Issue130StandaloneNetworkGuard());
        try(var fixture=new Issue130StandaloneGoogleTlsFixture(root(),HOST,"valid")){
            ca=fixture;int pass=0,fail=0,error=0;List<String> names=args.length==0?List.copyOf(TESTS.keySet()):List.of(args);
            for(String name:names){try{TESTS.get(name).run();pass++;System.out.println(JSON.writeValueAsString(Map.of("test",name,"status","PASS")));}catch(AssertionError failure){fail++;System.out.println(JSON.writeValueAsString(Map.of("test",name,"status","ASSERTION_FAILURE","reason",String.valueOf(failure.getMessage()).startsWith("TDD RED:")?failure.getMessage():"B_FIXED_GOOGLE_PUBLIC_CONTRACT")));}catch(Throwable failure){error++;System.out.println(JSON.writeValueAsString(Map.of("test",name,"status","SETUP_OR_UNEXPECTED_ERROR","type",failure.getClass().getSimpleName())));}}
            System.out.println(JSON.writeValueAsString(Map.of("tests",names.size(),"passed",pass,"assertionFailures",fail,"errors",error,"actualProviderCalls",0,"sealedDataRead",false)));if(fail+error!=0)System.exit(1);
        }
    }
}
