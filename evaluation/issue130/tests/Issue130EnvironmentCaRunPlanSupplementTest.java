import java.nio.file.*;
import java.util.*;

/** Supplemental independent crypto entry-point oracle; no real certificate or provider calls. */
@SuppressWarnings("removal")
public final class Issue130EnvironmentCaRunPlanSupplementTest {
  interface Work { void run() throws Exception; }
  static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
  static void verify(String kind,boolean wrongDer)throws Exception{
    try(var ca=new Issue130EnvironmentCaTlsFixture(Issue130SyntheticContractTest.privateRoot(),"api.openai.com",kind);
        var f=new Issue130EnvironmentProxyContractTest.Fixture()){
      var metadata=new LinkedHashMap<String,Object>();metadata.put("environmentVariable","NODE_EXTRA_CA_CERTS");
      metadata.put("pemFileSha256",Issue130SyntheticContractTest.sha(ca.pem));
      metadata.put("certificateDerSha256",wrongDer?"0".repeat(64):Issue130SyntheticContractTest.sha(ca.certificate.getEncoded()));
      f.base.config.put("environmentCa",metadata);f.base.seal();f.base.replies(Issue130SyntheticContractTest.CLEAR);
      Map<String,String> environment=new HashMap<>(f.base.environment());environment.put("NODE_EXTRA_CA_CERTS",ca.pem.toString());
      boolean denied=false;
      try{f.base.run(environment);}catch(Issue130RepresentativeDecisionsCli.EvaluationException expected){
        check("CONFIGURATION".equals(expected.code()),"bounded configuration error");check(expected.getCause()==null,"no certificate/path exception data");denied=true;
      }
      check(denied,"INJECTED_CLIENT_MUST_NOT_BYPASS_CRYPTO_VALIDATION");
      check(f.base.network().sent.size()==0,"invalid cryptographic CA metadata sends zero physical HTTP");
      check(f.base.network().connections.get()==0,"injected client sees no connection");
      check(!Files.exists(f.base.output.resolve(".suite-consumed.json")),"invalid cryptographic CA metadata consumes no suite");
    }
  }
  public static void main(String[] args)throws Exception{
    System.setSecurityManager(new Issue130SyntheticNetworkGuard());
    Map<String,Work> tests=new LinkedHashMap<>();
    tests.put("CS01_exact_PEM_pin_wrong_DER_injected_client_denied",()->verify("valid",true));
    tests.put("CS02_exact_PEM_DER_expired_CA_injected_client_denied",()->verify("expired",false));
    tests.put("CS03_exact_PEM_DER_nonCA_injected_client_denied",()->verify("leaf",false));
    int pass=0,fail=0,error=0;
    for(var item:tests.entrySet()){
      try{item.getValue().run();pass++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",item.getKey(),"status","PASS")));}
      catch(AssertionError expected){fail++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",item.getKey(),"status","ASSERTION_FAILURE","reason","CA_RUNPLAN_CRYPTO_BOUNDARY")));}
      catch(Throwable unexpected){error++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",item.getKey(),"status","SETUP_OR_UNEXPECTED_ERROR","type",unexpected.getClass().getSimpleName())));}
    }
    System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("tests",3,"passed",pass,"assertionFailures",fail,"errors",error,"paidCalls",0,"sealedDataRead",false)));
    if(fail+error!=0)System.exit(1);
  }
}
