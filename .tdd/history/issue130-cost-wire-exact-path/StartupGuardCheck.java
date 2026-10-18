public final class StartupGuardCheck {
 @SuppressWarnings("removal") public static void main(String[] args) {
  if(System.getSecurityManager()==null)throw new AssertionError("STARTUP_GUARD_REQUIRED");
  try{System.getSecurityManager().checkConnect("api.openai.com",-1);throw new AssertionError("EXTERNAL_DNS_MUST_BE_DENIED");}catch(SecurityException expected){}
  try{System.getSecurityManager().checkConnect("198.51.100.1",443);throw new AssertionError("EXTERNAL_CONNECT_MUST_BE_DENIED");}catch(SecurityException expected){}
  System.out.println("{\"startupGuard\":true,\"externalDnsDenied\":true,\"externalConnectDenied\":true}");
 }
}
