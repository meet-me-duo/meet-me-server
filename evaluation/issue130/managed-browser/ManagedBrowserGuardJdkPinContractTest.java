package com.meetme.integration.managed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Public checkExec decisions only: no executable or subprocess is actually started. */
public final class ManagedBrowserGuardJdkPinContractTest {
  public static void main(String[] args) throws Exception {
    String originalHome = System.getProperty("java.home");
    Path selected = Path.of(originalHome, "bin", "keytool").toRealPath();
    List<String> failed = new ArrayList<>();
    try {
      new ManagedBrowserLoopbackGuard().checkExec(selected.toString());
    } catch (Throwable failure) { failed.add("MJP01"); }
    Path fakeHome = Files.createTempDirectory("managed-synthetic-jdk-");
    try {
      Path fakeExecutable = Files.createDirectories(fakeHome.resolve("bin")).resolve("keytool");
      Files.writeString(fakeExecutable, "SYNTHETIC_NOT_EXECUTED");
      ManagedBrowserLoopbackGuard guard = new ManagedBrowserLoopbackGuard();
      try {
        System.setProperty("java.home", fakeHome.toString());
        boolean denied = false;
        try { guard.checkExec(fakeExecutable.toString()); }
        catch (SecurityException expected) { denied = true; }
        if (!denied) throw new AssertionError("MUTABLE_JAVA_HOME_MUST_NOT_SELECT_EXECUTABLE");
        guard.checkExec(selected.toString());
      } catch (Throwable failure) { failed.add("MJP02"); }
      finally { System.setProperty("java.home", originalHome); }
    } finally {
      Files.deleteIfExists(fakeHome.resolve("bin/keytool"));
      Files.deleteIfExists(fakeHome.resolve("bin"));
      Files.deleteIfExists(fakeHome);
    }
    String labels = String.join(",", failed.stream().map(s -> "\"" + s + "\"").toList());
    System.out.println("{\"tests\":2,\"failures\":" + failed.size() + ",\"errors\":0,\"failedTests\":[" + labels + "],\"actualExecCalls\":0}");
    if (!failed.isEmpty()) throw new AssertionError("IMMUTABLE_JDK_SELECTION_REQUIRED");
  }
}
