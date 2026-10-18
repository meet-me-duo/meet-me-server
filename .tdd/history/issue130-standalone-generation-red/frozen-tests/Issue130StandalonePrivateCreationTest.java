import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Permission;
import java.util.*;

/** Deterministic first-mode oracle, launched with umask022; synthetic prep only, all sockets forbidden. */
@SuppressWarnings("removal")
public final class Issue130StandalonePrivateCreationTest {
    static Set<Path> watched=Set.of();
    static final class Observer extends SecurityManager {
        @Override public void checkPermission(Permission ignored) {}
        @Override public void checkConnect(String host,int port){throw new SecurityException("SYNTHETIC_EXTERNAL_NETWORK_FORBIDDEN");}
        @Override public void checkWrite(String name){Path file=Path.of(name).toAbsolutePath();if(watched.contains(file)&&Files.exists(file,LinkOption.NOFOLLOW_LINKS))try{if(!Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")))throw new AssertionError("B_PRIVATE_FILES_0600_AT_CREATION_BEFORE_CHMOD");}catch(java.io.IOException failure){throw new AssertionError("SYNTHETIC_CREATION_OBSERVER_SETUP_FAILURE");}}
    }
    public static void main(String[] args)throws Exception {
        Path[] paths=Issue130StandaloneRuntimeTest.prepInput(2,Issue130StandaloneRuntimeTest.RAW);
        Map<String,Object> index=Issue130StandaloneRuntimeTest.JSON.readValue(Files.readAllBytes(paths[0]),Map.class);
        var records=(List<Map<String,Object>>)index.get("records");Set<Path> selected=new HashSet<>();selected.add(paths[1]);for(var r:records){Path gen=Path.of((String)r.get("private_wire_output_path"));selected.add(gen);selected.add(Path.of(gen+".count.json"));}watched=Set.copyOf(selected);
        System.setSecurityManager(new Observer());
        try{Issue130StandaloneGenerationCli.prepareIndex2(paths[0],paths[1]);for(Path file:watched)Issue130StandaloneRuntimeTest.privateFile(file);System.out.println("{\"tests\":1,\"passed\":1,\"assertionFailures\":0,\"errors\":0,\"actualProviderCalls\":0,\"sealedDataRead\":false}");}
        catch(AssertionError failure){System.out.println(Issue130StandaloneRuntimeTest.JSON.writeValueAsString(Map.of("tests",1,"passed",0,"assertionFailures",1,"errors",0,"actualProviderCalls",0,"reason",String.valueOf(failure.getMessage()).startsWith("TDD RED:")?failure.getMessage():"B_PRIVATE_CREATION_PUBLIC_CONTRACT")));System.exit(1);}
        catch(Exception failure){System.out.println(Issue130StandaloneRuntimeTest.JSON.writeValueAsString(Map.of("tests",1,"passed",0,"assertionFailures",0,"errors",1,"actualProviderCalls",0,"type",failure.getClass().getSimpleName())));System.exit(2);}
        finally{watched=Set.of();}
    }
}
