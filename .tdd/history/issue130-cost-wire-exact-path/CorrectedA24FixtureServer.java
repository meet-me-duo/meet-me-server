import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.time.Duration;
/** Synthetic public fixtures only. No real plan, inputs, CA or credential. */
@SuppressWarnings({"unchecked","removal"})
public final class CorrectedA24FixtureServer {
 public static void main(String[] args)throws Exception {
  System.setSecurityManager(new Issue130SyntheticNetworkGuard());
  Path ready=Path.of(args[0]);
  try(var ca=new Issue130EnvironmentCaTlsFixture(Issue130SyntheticContractTest.privateRoot(),"api.openai.com","valid")) {
   Issue130CandidateA24ContractTest.ca=ca;
   try(var f=new Issue130CandidateA24ContractTest.Fixture()) {
    for(int i=0;i<24;i++)ca.enqueue(Issue130EnvironmentCaTlsFixture.Reply.json(200,Issue130SyntheticContractTest.body(i%2==0?"CLEAR":"NEEDS_REVIEW","0.9",true,137,7)));
    var env=new HashMap<>(f.env());env.put("OPENAI_API_KEY","DUMMY_OFFLINE_CONTEXT_A24");env.put("GEMINI_API_KEY","");
    String cp=Issue130CandidateA24ContractTest.map(f.base.config.get("runtimeFiles")).keySet().stream().filter(p->p.endsWith("/runtime.classpath")).findFirst().orElseThrow();
    Issue130SyntheticContractTest.write(ready,Map.of("plan",f.base.plan.toString(),"index",f.base.index.toString(),"output",f.base.output.toString(),"classpathFile",cp,"environment",env));
    long cutoff=System.nanoTime()+Duration.ofSeconds(150).toNanos();while(!Files.exists(Path.of(ready+".stop"))&&System.nanoTime()<cutoff)Thread.sleep(25);
    int exact=0;for(int i=0;i<ca.sent.size()&&i<24;i++){var record=f.base.records.get(i);byte[] expected=Files.readAllBytes(Path.of((String)record.get("request_path")));var sent=ca.sent.get(i);if(sent.method().equals("POST")&&sent.path().equals("/v1/decisions")&&Arrays.equals(expected,sent.body()))exact++;}
    Issue130SyntheticContractTest.write(Path.of(ready+".stats"),Map.of("physicalSyntheticPosts",ca.sent.size(),"exactFrozenWires",exact,"maximumConcurrency",ca.maximumActive.get(),"fixtureFailures",ca.fixtureFailures,"actualExternalCalls",0));
   }
  }
 }
}
