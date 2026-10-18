import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** External preregistered A24 contract. Only synthetic fixtures; no evaluation gold or model calls. */
@SuppressWarnings({"unchecked","removal"})
public final class Issue130CandidateA24ContractTest {
  interface Work {void run() throws Exception;}
  static final Map<String,Work> TESTS=new LinkedHashMap<>();
  static final String PROFILE="CANDIDATE_A24_USD3_V1";
  static Issue130EnvironmentCaTlsFixture ca;
  static void test(String id,Work work){TESTS.put(id,work);}
  static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
  static void eq(Object a,Object b,String why){check(Objects.equals(a,b),why);}
  static Map<String,Object> map(Object o){return Issue130SyntheticContractTest.map(o);}
  static long number(Object o){return Issue130SyntheticContractTest.number(o);}
  static <T>T valid(java.util.concurrent.Callable<T> work)throws Exception{try{return work.call();}catch(Issue130RepresentativeDecisionsCli.EvaluationException e){throw new AssertionError("APPROVED_A24_CONTRACT_MUST_SUCCEED");}}
  static void rejected(Work w)throws Exception{Issue130SyntheticContractTest.rejected(w::run);}
  static Map<String,Object> previous(){return new LinkedHashMap<>(Map.of("status","FULL_SUITE_RESERVED_BEFORE_EXECUTION","previousRetainedReservationUsd","1.56254208","newReservationUsd","0.23068672","cumulativeReservationUsd","1.79322880","approvedAggregateUsd","2.00","refunds",0,"retainedUnknownsRefunded",false));}
  static final class Fixture implements AutoCloseable {
    final Issue130EnvironmentProxyContractTest.Fixture parent=new Issue130EnvironmentProxyContractTest.Fixture();
    final Issue130SyntheticContractTest.Fixture base=parent.base;
    Fixture()throws Exception{
      for(int i=16;i<24;i++){Path p=base.root.resolve("synthetic-extra-"+i+".json");byte[] wire=Issue130SyntheticContractTest.productionWire("SYNTHETIC_PRIVATE_RAW_A24_"+i+" 가가 😀");Files.write(p,wire);Files.setPosixFilePermissions(p,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));base.records.add(new LinkedHashMap<>(Map.of("opaque_call_id","synthetic-opaque-"+i,"request_path",p.toString(),"request_sha256",Issue130SyntheticContractTest.sha(p),"request_bytes",wire.length)));}
      index();base.config.put("evaluationProfile",PROFILE);base.config.put("caseCount",24);base.config.put("maximumHttpAttempts",24);base.config.put("suiteSeconds",420);
      map(base.config.get("costProfile")).putAll(Map.of("priorHeldUsd","1.79322880","maximumNewReservedUsd","0.34603008","maximumCumulativeReservedUsd","2.13925888","approvedAggregateUsd","3.00"));
      base.config.put("environmentCa",Map.of("environmentVariable","NODE_EXTRA_CA_CERTS","pemFileSha256",Issue130SyntheticContractTest.sha(ca.pem),"certificateDerSha256",Issue130SyntheticContractTest.sha(ca.certificate.getEncoded())));
      parent.evidence(previous());
    }
    void index()throws Exception{Issue130SyntheticContractTest.write(base.index,Map.of("status","WIRES_PREPARED_NOT_EXECUTION","records",base.records));base.config.put("preparedIndexSha256",Issue130SyntheticContractTest.sha(base.index));}
    Map<String,String> env()throws Exception{Map<String,String> e=new HashMap<>(base.environment());e.put("ISSUE130_SUITE_REMAINING_MILLIS","420000");e.put("NODE_EXTRA_CA_CERTS",ca.pem.toString());e.put("https_proxy","http://127.0.0.1:"+ca.port());return e;}
    Map<String,Object> run()throws Exception{return base.run(env());}
    void deny()throws Exception{rejected(this::run);eq(0,base.network().sent.size(),"invalid A24 emits0HTTP");check(!Files.exists(base.output.resolve(".suite-consumed.json")),"invalid profile consumes0");}
    void replies(String body)throws Exception{for(int i=0;i<24;i++)base.network().enqueue(Issue130SyntheticTlsProxy.Reply.json(200,body));}
    public void close()throws Exception{parent.close();}
  }
  static Map<String,Object> prepare(int count,boolean duplicate,boolean existing)throws Exception{
    Path root=Issue130SyntheticContractTest.privateRoot(),input=root.resolve("inputs.json"),output=root.resolve("prepared.json");List<Map<String,Object>> rows=new ArrayList<>();
    for(int i=0;i<count;i++){Path p=root.resolve("input-"+i+".json"),wire=root.resolve("wire-"+i+".json");Issue130SyntheticContractTest.write(p,Issue130SyntheticContractTest.input("SYNTHETIC_PRIVATE_RAW_PREP_"+i+" 가가 😀"));rows.add(Map.of("opaque_call_id",duplicate&&i==count-1?"synthetic-0":"synthetic-"+i,"canonical_input_sha256",Issue130SyntheticContractTest.sha(p),"canonical_input_bytes",Files.size(p),"private_input_path",p.toString(),"private_wire_output_path",wire.toString()));}
    Issue130SyntheticContractTest.write(input,Map.of("status","INPUTS_FROZEN_WIRE_PREP_NOT_EXECUTION","input_contract",List.of("raw_text","reference_at","time_zone","locale","search_start_date","search_end_date"),"key_network_access_permitted",false,"records",rows));
    if(existing)Files.writeString(output,"SYNTHETIC_PREEXISTING_DO_NOT_REPLACE");
    if(count!=24||duplicate||existing){rejected(()->Issue130RepresentativeDecisionsCli.prepareIndex24(input,output));check(existing?Files.readString(output).equals("SYNTHETIC_PREEXISTING_DO_NOT_REPLACE"):!Files.exists(output),"no overwrite/partial execution evidence");return Map.of();}
    Map<String,Object> r=valid(()->Issue130RepresentativeDecisionsCli.prepareIndex24(input,output));eq(true,r.get("prepared"),"prepared only");eq(24L,number(r.get("caseCount")),"exact24");eq(Set.of("prepared","caseCount","indexSha256"),r.keySet(),"safe prep stdout schema");Map<String,Object> prepared=map(Issue130SyntheticContractTest.JSON.readValue(Files.readAllBytes(output),Map.class));List<Map<String,Object>> actual=(List<Map<String,Object>>)prepared.get("records");eq(24,actual.size(),"all24");eq("WIRES_PREPARED_NOT_EXECUTION",prepared.get("status"),"not execution");
    for(int i=0;i<24;i++){Map<String,Object> row=actual.get(i);eq("synthetic-"+i,row.get("opaque_call_id"),"order");eq(Set.of("opaque_call_id","request_path","request_sha256","request_bytes"),row.keySet(),"no gold/inputpath");Path wire=Path.of((String)row.get("request_path"));eq(Issue130SyntheticContractTest.sha(wire),row.get("request_sha256"),"wire pinned");check(Arrays.equals(Issue130SyntheticContractTest.productionWire("SYNTHETIC_PRIVATE_RAW_PREP_"+i+" 가가 😀"),Files.readAllBytes(wire)),"production canonical bytes exact");Issue130SyntheticContractTest.assertPrivate(wire);}
    Issue130SyntheticContractTest.assertPrivate(output);Issue130SyntheticContractTest.noLeaks(r);return r;
  }
  static {
    test("A24P01_prepare24_exact_order_canonical_private",()->prepare(24,false,false));
    test("A24P02_prepare23_rejected",()->prepare(23,false,false));test("A24P03_prepare25_rejected",()->prepare(25,false,false));test("A24P04_duplicate_rejected",()->prepare(24,true,false));test("A24P05_CREATE_NEW_preserves_existing",()->prepare(24,false,true));
    test("A24R01_actual24_serial_routes_fullhold_replay0",()->{try(Fixture f=new Fixture()){for(int i=0;i<24;i++)f.base.network().enqueue(Issue130SyntheticTlsProxy.Reply.json(200,Issue130SyntheticContractTest.body(i%2==0?"CLEAR":"NEEDS_REVIEW","0.9",true,137,7)));var r=valid(f::run);eq("COMPLETED",r.get("status"),"24 completed");eq(24,Issue130SyntheticContractTest.results(r).size(),"all denominators");eq(24L,number(r.get("physicalDecisionsCalls")),"24 application calls");eq(0L,number(r.get("physicalGenerationCalls")),"B0");eq(0L,number(r.get("countCalls")),"Count0");eq("0.34603008",r.get("maximumNewReservedUsd"),"new hold");eq("2.13925888",r.get("maximumCumulativeReservedUsd"),"cumulative hold");for(int i=0;i<24;i++)eq(i%2==0?"CLEAR_STOP":"B_REQUIRED_NOT_RUN",Issue130SyntheticContractTest.results(r).get(i).get("outcome"),"gold-independent ordered routing");Map<String,Object> held=map(Issue130SyntheticContractTest.JSON.readValue(Files.readAllBytes(f.base.output.resolve(".suite-consumed.json")),Map.class));eq("1.79322880",held.get("priorHeldUsd"),"retained unknown not refunded");eq("0.34603008",held.get("maximumNewReservedUsd"),"marker new hold");eq("2.13925888",held.get("maximumCumulativeReservedUsd"),"marker total");eq(24,f.base.proxy.sent.size(),"24 physical POST");eq(1,f.base.proxy.maximumActive.get(),"serial1");rejected(f::run);eq(24,f.base.proxy.sent.size(),"replay0");Issue130SyntheticContractTest.noLeaks(r);}});
    test("A24R02_technical_first_failure_UNRUN23_no_refund",()->{try(Fixture f=new Fixture()){f.base.network().enqueue(Issue130SyntheticTlsProxy.Reply.json(503,"{}"));var r=valid(f::run);eq("STOPPED",r.get("status"),"whole suite stop");eq(24,Issue130SyntheticContractTest.results(r).size(),"all24 denominators");eq(23L,Issue130SyntheticContractTest.results(r).stream().filter(x->"NOT_RUN_AFTER_SUITE_STOP".equals(x.get("outcome"))).count(),"23UNRUN");eq(1,f.base.proxy.sent.size(),"no retry/next");eq("2.13925888",r.get("maximumCumulativeReservedUsd"),"full unknown hold retained");}});
    test("A24R03_420_startup_consumed_not_fresh_deadline",()->{try(Fixture f=new Fixture()){AtomicInteger n=new AtomicInteger();f.base.nano=()->n.getAndIncrement()==0?0L:Duration.ofSeconds(421).toNanos();try{var r=f.run();eq("STOPPED",r.get("status"),"expired stops");}catch(Issue130RepresentativeDecisionsCli.EvaluationException e){eq("TIMEOUT",e.code(),"valid profile expiry is timeout");}eq(0,f.base.network().sent.size(),"startup includes pre-emission");}});
    test("A24R04_allow_remaining350000_within420",()->{try(Fixture f=new Fixture()){f.replies(Issue130SyntheticContractTest.CLEAR);var e=f.env();e.put("ISSUE130_SUITE_REMAINING_MILLIS","350000");eq("COMPLETED",valid(()->f.base.run(e)).get("status"),"new window above old300 valid");}});
    for(String field:List.of("caseCount","maximumHttpAttempts","suiteSeconds"))test("A24D_fixed_"+field,()->{try(Fixture f=new Fixture()){f.base.config.put(field,field.equals("suiteSeconds")?300:16);f.base.seal();f.deny();}});
    for(String field:List.of("priorHeldUsd","maximumNewReservedUsd","maximumCumulativeReservedUsd","approvedAggregateUsd"))test("A24D_cost_"+field,()->{try(Fixture f=new Fixture()){map(f.base.config.get("costProfile")).put(field,field.equals("approvedAggregateUsd")?"2.00":"0.00");f.base.seal();f.deny();}});
    test("A24D_missing_CA",()->{try(Fixture f=new Fixture()){f.base.config.remove("environmentCa");f.base.seal();f.deny();}});
    test("A24D_missing_ENV_mode",()->{try(Fixture f=new Fixture()){f.base.config.remove("transportMode");f.base.seal();f.deny();}});
    for(Object value:Arrays.asList(null,24,"", "CANDIDATE_A24_USD3_V2"))test("A24D_unknown_profile_"+TESTS.size(),()->{try(Fixture f=new Fixture()){f.base.config.put("evaluationProfile",value);f.base.seal();f.deny();}});
    test("A24D_prior_historical_cap_not_rewritten",()->{try(Fixture f=new Fixture()){var p=previous();p.put("approvedAggregateUsd","3.00");f.parent.evidence(p);f.deny();}});
    test("A24D_prior_hash_corrupt",()->{try(Fixture f=new Fixture()){Files.writeString(f.parent.evidence,"{}");f.deny();}});
    test("A24D_remaining420001_denied",()->{try(Fixture f=new Fixture()){var e=f.env();e.put("ISSUE130_SUITE_REMAINING_MILLIS","420001");rejected(()->f.base.run(e));eq(0,f.base.network().sent.size(),"unbounded420 extension0");}});
    test("A24D_profile_absence_not_allow24",()->{try(Fixture f=new Fixture()){f.base.config.remove("evaluationProfile");f.base.seal();f.deny();}});
  }
  public static void main(String[] args)throws Exception{
    System.setSecurityManager(new Issue130SyntheticNetworkGuard());try(var fixture=new Issue130EnvironmentCaTlsFixture(Issue130SyntheticContractTest.privateRoot(),"api.openai.com","valid")){ca=fixture;
      if(args.length==2&&args[0].equals("--export-a24-fixture")){try(Fixture f=new Fixture()){Issue130SyntheticContractTest.write(Path.of(args[1]),Map.of("plan",f.base.plan.toString(),"index",f.base.index.toString(),"output",f.base.output.toString(),"pem",ca.pem.toString(),"classpathFile",map(f.base.config.get("runtimeFiles")).keySet().stream().filter(x->x.endsWith("/runtime.classpath")).findFirst().orElseThrow()));}return;}
      int pass=0,fail=0,error=0;for(String name:args.length==0?List.copyOf(TESTS.keySet()):List.of(args)){try{TESTS.get(name).run();pass++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",name,"status","PASS")));}catch(AssertionError e){fail++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",name,"status","ASSERTION_FAILURE","reason",String.valueOf(e.getMessage()).startsWith("TDD RED:")?e.getMessage():"A24_PUBLIC_CONTRACT_ASSERTION")));}catch(Throwable e){error++;System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("test",name,"status","SETUP_OR_UNEXPECTED_ERROR","type",e.getClass().getSimpleName())));}}
      System.out.println(Issue130SyntheticContractTest.JSON.writeValueAsString(Map.of("tests",args.length==0?TESTS.size():args.length,"passed",pass,"assertionFailures",fail,"errors",error,"paidCalls",0,"freshHoldoutRead",false)));if(fail+error!=0)System.exit(1);
    }
  }
}
