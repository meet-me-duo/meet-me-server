package com.meetme.integration.managed;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.security.Permission;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-only network guard for this trusted test launcher; never installed globally. */
@SuppressWarnings("removal")
public final class ManagedBrowserLoopbackGuard extends SecurityManager {
  private static final AtomicInteger denied = new AtomicInteger();

  private final Path selectedKeytool;

  public ManagedBrowserLoopbackGuard() {
    try { selectedKeytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toRealPath(); }
    catch (IOException | IllegalArgumentException ignored) { throw new IllegalStateException("JDK_KEYTOOL_REQUIRED"); }
  }

  private static boolean loopback(String host) {
    if (host == null) return false;
    if (host.equals("localhost") || host.equals("::1") || host.equals("[::1]") || host.equals("0:0:0:0:0:0:0:1")) return true;
    String[] pieces = host.split("\\.", -1);
    if (pieces.length != 4 || !pieces[0].equals("127")) return false;
    for (String piece : pieces) {
      if (!piece.matches("[0-9]{1,3}") || Integer.parseInt(piece) > 255) return false;
    }
    return true;
  }

  private static void reject() { denied.incrementAndGet(); throw new SecurityException("LOOPBACK_FIXTURE_REQUIRED"); }

  @Override public void checkConnect(String host, int port) { if (!loopback(host)) reject(); }
  @Override public void checkConnect(String host, int port, Object context) { checkConnect(host, port); }
  @Override public void checkAccept(String host, int port) { if (!loopback(host)) reject(); }
  @Override public void checkMulticast(InetAddress address) { reject(); }
  @Override public void checkExec(String command) {
    try {
      Path supplied = Path.of(command);
      if (!supplied.isAbsolute() || !supplied.toRealPath().equals(selectedKeytool)) reject();
    } catch (IOException | IllegalArgumentException ignored) { reject(); }
  }
  @Override public void checkPermission(Permission permission) {
    if (permission instanceof RuntimePermission && permission.getName().equals("setSecurityManager")) reject();
  }
  @Override public void checkPermission(Permission permission, Object context) { checkPermission(permission); }

  public static int deniedAttempts() { return denied.get(); }
}
