import java.security.Permission;
/** Test-JVM-only external-network tripwire; never installed by the evaluation helper. */
@SuppressWarnings("removal")
public final class Issue130StandaloneNetworkGuard extends SecurityManager {
    static volatile boolean denyKeyReads;
    public Issue130StandaloneNetworkGuard() {}
    @Override public void checkPermission(Permission permission) {
        if (denyKeyReads && permission.getName().startsWith("getenv.") &&
            (permission.getName().contains("OPENAI_API_KEY") || permission.getName().contains("GEMINI_API_KEY") || permission.getName().equals("getenv.*")))
            throw new AssertionError("PREP_MUST_NOT_READ_KEYS");
    }
    @Override public void checkConnect(String host,int port) {
        if (!(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1") || host.equals("[::1]")))
            throw new SecurityException("SYNTHETIC_EXTERNAL_NETWORK_FORBIDDEN");
    }
}
