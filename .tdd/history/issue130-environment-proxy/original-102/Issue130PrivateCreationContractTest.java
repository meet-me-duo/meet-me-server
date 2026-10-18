import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Permission;
import java.util.*;

/** Independent deterministic creation-mode oracle, explicitly launched with umask022. */
@SuppressWarnings("removal")
public final class Issue130PrivateCreationContractTest {
    static Path watched;
    static final class CreationObserver extends SecurityManager {
        @Override public void checkPermission(Permission ignored) {}
        @Override public void checkConnect(String host,int port) {throw new SecurityException("SYNTHETIC_NETWORK_FORBIDDEN");}
        @Override public void checkWrite(String file) {
            Path target=watched;
            if(target!=null&&Path.of(file).toAbsolutePath().equals(target)&&Files.exists(target,LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if(!Files.getPosixFilePermissions(target).equals(PosixFilePermissions.fromString("rw-------")))
                        throw new AssertionError("PRIVATE_FILE_MUST_BE_0600_FROM_CREATION_BEFORE_CHMOD");
                }catch(java.io.IOException error){throw new AssertionError("SYNTHETIC_MODE_OBSERVER_FAILED");}
            }
        }
    }
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("issue130-private-creation-synthetic-");
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));
        Path input=root.resolve("input.json"),output=root.resolve("wire.json");
        JsonMapper json=JsonMapper.builder().build();
        Files.write(input,json.writeValueAsBytes(Map.of("raw_text","synthetic private creation input","reference_at","2026-10-18T03:00:00Z","time_zone","Asia/Seoul","locale","ko-KR","search_start_date","2026-10-18","search_end_date","2026-10-31")));
        Files.setPosixFilePermissions(input,PosixFilePermissions.fromString("rw-------"));
        watched=output.toAbsolutePath();System.setSecurityManager(new CreationObserver());
        try {
            Issue130RepresentativeDecisionsCli.prepareOne(input,output);
            if(!Files.getPosixFilePermissions(output).equals(PosixFilePermissions.fromString("rw-------")))
                throw new AssertionError("FINAL_PRIVATE_MODE_MUST_BE_0600");
            System.out.println("{\"tests\":1,\"passed\":1,\"assertionFailures\":0,\"errors\":0,\"actualProviderCalls\":0}");
        }catch(AssertionError failure){
            System.out.println(json.writeValueAsString(Map.of("tests",1,"passed",0,"assertionFailures",1,"errors",0,"failure",String.valueOf(failure.getMessage()),"actualProviderCalls",0)));System.exit(1);
        }catch(Exception error){
            System.out.println(json.writeValueAsString(Map.of("tests",1,"passed",0,"assertionFailures",0,"errors",1,"failure",error.getClass().getSimpleName(),"actualProviderCalls",0)));System.exit(2);
        }finally{watched=null;}
    }
}
