import java.nio.file.*;
import java.util.*;
import java.net.ServerSocket;
import tools.jackson.databind.json.JsonMapper;
public final class Issue130CliProxyControl {
  public static void main(String[] args)throws Exception{
    Path root=Path.of(args[0]),ready=root.resolve("proxy-ready.json"),stop=root.resolve("stop");
    try(Issue130SyntheticTlsProxy proxy=new Issue130SyntheticTlsProxy(root)){
      for(int i=0;i<16;i++)proxy.enqueue(Issue130SyntheticTlsProxy.Reply.json(200,Issue130SyntheticContractTest.CLEAR));
      var field=Issue130SyntheticTlsProxy.class.getDeclaredField("listener");field.setAccessible(true);int port=((ServerSocket)field.get(proxy)).getLocalPort();
      JsonMapper json=JsonMapper.builder().build();Files.write(ready,json.writeValueAsBytes(Map.of("port",port,"trustStore",root.resolve("synthetic-proxy.p12").toString())));
      while(!Files.exists(stop))Thread.sleep(50);
      Files.write(root.resolve("proxy-result.json"),json.writeValueAsBytes(Map.of("requests",proxy.sent.size(),"connections",proxy.connections.get(),"maximumActive",proxy.maximumActive.get(),"fixtureFailures",proxy.fixtureFailures)));
    }
  }
}
