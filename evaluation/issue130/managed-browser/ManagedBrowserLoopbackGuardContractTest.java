package com.meetme.integration.managed;

import java.io.FilePermission;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Protocol-only guard decisions; no DNS, socket, subprocess or application starts. */
@SuppressWarnings("removal")
public final class ManagedBrowserLoopbackGuardContractTest {
  private static void denied(Runnable action) {
    try { action.run(); }
    catch (SecurityException expected) { return; }
    throw new AssertionError("NON_LOOPBACK_OR_UNSAFE_OPERATION_MUST_BE_DENIED");
  }
  public static void main(String[] args) throws Exception {
    List<String> failed = new ArrayList<>();
    List<Runnable> tests = List.of(
      () -> {
        ManagedBrowserLoopbackGuard guard = new ManagedBrowserLoopbackGuard();
        for (String host : List.of("127.0.0.1", "::1", "localhost")) {
          guard.checkConnect(host, -1);
          guard.checkConnect(host, 19440);
          guard.checkAccept(host, 19442);
        }
      },
      () -> {
        ManagedBrowserLoopbackGuard guard = new ManagedBrowserLoopbackGuard();
        for (String host : List.of("203.0.113.1", "api.openai.com", "generativelanguage.googleapis.com", "169.254.169.254")) {
          denied(() -> guard.checkConnect(host, -1));
          denied(() -> guard.checkConnect(host, 443));
          denied(() -> guard.checkAccept(host, 19440));
        }
      },
      () -> {
        ManagedBrowserLoopbackGuard guard = new ManagedBrowserLoopbackGuard();
        try { denied(() -> {
          try { guard.checkMulticast(InetAddress.getByAddress(new byte[] {(byte)224,0,0,1})); }
          catch (java.net.UnknownHostException impossible) { throw new AssertionError(impossible); }
        }); }
        catch (SecurityException wrongLocation) { throw new AssertionError(wrongLocation); }
      },
      () -> {
        ManagedBrowserLoopbackGuard guard = new ManagedBrowserLoopbackGuard();
        denied(() -> guard.checkExec("/bin/sh"));
        denied(() -> guard.checkExec("/tmp/synthetic/keytool"));
      },
      () -> {
        ManagedBrowserLoopbackGuard guard = new ManagedBrowserLoopbackGuard();
        try { guard.checkExec(Path.of(System.getProperty("java.home"), "bin", "keytool").toRealPath().toString()); }
        catch (java.io.IOException setup) { throw new AssertionError("JDK_KEYTOOL_FIXTURE_REQUIRED", setup); }
      },
      () -> {
        ManagedBrowserLoopbackGuard guard = new ManagedBrowserLoopbackGuard();
        denied(() -> guard.checkPermission(new RuntimePermission("setSecurityManager")));
        guard.checkPermission(new FilePermission("synthetic-public-resource", "read"));
      });
    for (int i=0; i<tests.size(); i++) {
      try { tests.get(i).run(); }
      catch (Throwable failure) { failed.add("MLG0" + (i+1)); }
    }
    String labels = String.join(",", failed.stream().map(s -> "\"" + s + "\"").toList());
    System.out.println("{\"tests\":6,\"failures\":" + failed.size() + ",\"errors\":0,\"failedTests\":[" + labels + "],\"physicalNetworkAttempts\":0,\"subprocessesUnderTest\":0}");
    if (!failed.isEmpty()) throw new AssertionError("MLB_GUARD_PUBLIC_REQUIREMENTS_MISSING");
  }
}
