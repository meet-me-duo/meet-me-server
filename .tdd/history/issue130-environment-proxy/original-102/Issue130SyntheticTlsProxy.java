import okhttp3.OkHttpClient;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Independent synthetic transport: canonical host is tunneled only to this loopback socket. */
final class Issue130SyntheticTlsProxy implements AutoCloseable {
    record Reply(int status, byte[] body, Map<String,String> headers, long bodyDelayMillis, boolean disconnect) {
        static Reply json(int status, String body) {
            return new Reply(status, body.getBytes(StandardCharsets.UTF_8), Map.of(), 0, false);
        }
        Reply header(String key, String value) {
            Map<String,String> next = new LinkedHashMap<>(headers); next.put(key,value);
            return new Reply(status,body,Map.copyOf(next),bodyDelayMillis,disconnect);
        }
        Reply slow(long millis) { return new Reply(status,body,headers,millis,disconnect); }
    }
    record Sent(String method, String path, Map<String,String> headers, byte[] body) {}
    private static final char[] PASSWORD = "synthetic-only-passphrase".toCharArray();
    private final ServerSocket listener;
    private final SSLContext ssl;
    private final X509TrustManager trust;
    private final ExecutorService threads=Executors.newCachedThreadPool();
    private final BlockingQueue<Reply> replies=new LinkedBlockingQueue<>();
    final List<Sent> sent=new CopyOnWriteArrayList<>();
    final AtomicInteger connections=new AtomicInteger();
    final AtomicInteger active=new AtomicInteger();
    final AtomicInteger maximumActive=new AtomicInteger();
    final List<String> fixtureFailures=new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    volatile Runnable afterRequest=()->{};
    Issue130SyntheticTlsProxy(Path privateRoot) throws Exception {
        Path store=privateRoot.resolve("synthetic-proxy.p12");
        Process keytool=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","keytool").toString(),
            "-genkeypair","-alias","synthetic","-keyalg","RSA","-keysize","2048",
            "-validity","2","-dname","CN=api.openai.com","-ext","SAN=dns:api.openai.com",
            "-storetype","PKCS12","-keystore",store.toString(),"-storepass",new String(PASSWORD),
            "-keypass",new String(PASSWORD),"-noprompt").redirectErrorStream(true).start();
        byte[] ignored=keytool.getInputStream().readAllBytes();
        if (!keytool.waitFor(20,TimeUnit.SECONDS) || keytool.exitValue()!=0)
            throw new AssertionError("SYNTHETIC_TLS_FIXTURE_SETUP_FAILED");
        Files.setPosixFilePermissions(store,PosixFilePermissions.fromString("rw-------"));
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(InputStream input=Files.newInputStream(store)){keys.load(input,PASSWORD);}
        KeyManagerFactory km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys,PASSWORD);
        TrustManagerFactory tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(keys);
        trust=Arrays.stream(tm.getTrustManagers()).filter(t->t instanceof X509TrustManager)
            .map(t->(X509TrustManager)t).findFirst().orElseThrow();
        ssl=SSLContext.getInstance("TLS");ssl.init(km.getKeyManagers(),new TrustManager[]{trust},null);
        listener=new ServerSocket(); listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(),0));
        threads.submit(()->{while(!closed){try{Socket socket=listener.accept();connections.incrementAndGet();threads.submit(()->serve(socket));}
            catch(IOException e){if(!closed)fixtureFailures.add("ACCEPT_FAILURE");}}});
    }
    void enqueue(Reply reply){replies.add(reply);}
    OkHttpClient client(){return new OkHttpClient.Builder()
        .proxy(new Proxy(Proxy.Type.HTTP,new InetSocketAddress("127.0.0.1",listener.getLocalPort())))
        .sslSocketFactory(ssl.getSocketFactory(),trust)
        .connectTimeout(Duration.ofSeconds(2)).build();}
    private void serve(Socket socket){
        try(socket){
            socket.setSoTimeout(5000);
            byte[] tunnel=readHeaders(socket.getInputStream());
            String first=new String(tunnel,StandardCharsets.ISO_8859_1).split("\\r\\n",2)[0];
            if(!first.equals("CONNECT api.openai.com:443 HTTP/1.1")){fixtureFailures.add("NONCANONICAL_TUNNEL");return;}
            socket.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            try(SSLSocket tls=(SSLSocket)ssl.getSocketFactory().createSocket(socket,"api.openai.com",443,true)){
                tls.setUseClientMode(false);tls.startHandshake();
                String headersText=new String(readHeaders(tls.getInputStream()),StandardCharsets.ISO_8859_1);
                String[] lines=headersText.split("\\r\\n");String[] line=lines[0].split(" ");
                Map<String,String> headers=new LinkedHashMap<>();
                for(int i=1;i<lines.length;i++){int colon=lines[i].indexOf(':');if(colon>0)headers.put(lines[i].substring(0,colon).toLowerCase(Locale.ROOT),lines[i].substring(colon+1).trim());}
                int length=Integer.parseInt(headers.getOrDefault("content-length","0"));
                if(length<0||length>16384){fixtureFailures.add("UNBOUNDED_REQUEST");return;}
                byte[] bytes=tls.getInputStream().readNBytes(length);
                if(bytes.length!=length){fixtureFailures.add("SHORT_REQUEST");return;}
                sent.add(new Sent(line[0],line[1],Map.copyOf(headers),bytes));
                afterRequest.run();
                int concurrency=active.incrementAndGet();maximumActive.accumulateAndGet(concurrency,Math::max);
                try{
                    Reply reply=replies.poll();if(reply==null){fixtureFailures.add("UNEXPECTED_REQUEST");reply=Reply.json(500,"{}");}
                    if(reply.disconnect)return;
                    StringBuilder head=new StringBuilder("HTTP/1.1 ").append(reply.status).append(" Synthetic\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ").append(reply.body.length).append("\r\n");
                    reply.headers.forEach((key,value)->head.append(key).append(": ").append(value).append("\r\n"));head.append("\r\n");
                    tls.getOutputStream().write(head.toString().getBytes(StandardCharsets.US_ASCII));tls.getOutputStream().flush();
                    if(reply.bodyDelayMillis>0){if(reply.body.length>0){tls.getOutputStream().write(reply.body,0,1);tls.getOutputStream().flush();}Thread.sleep(reply.bodyDelayMillis);if(reply.body.length>1)tls.getOutputStream().write(reply.body,1,reply.body.length-1);}
                    else tls.getOutputStream().write(reply.body);
                    tls.getOutputStream().flush();
                }finally{active.decrementAndGet();}
            }
        }catch(EOFException ignored){}catch(Exception e){if(!closed && !(e instanceof SocketException) && !(e instanceof SSLException))fixtureFailures.add(e.getClass().getSimpleName());}
    }
    private static byte[] readHeaders(InputStream input)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();int tail=0;
        while(bytes.size()<32768){int value=input.read();if(value<0)throw new EOFException();bytes.write(value);tail=(tail<<8)|value;if(tail==0x0d0a0d0a)return bytes.toByteArray();}
        throw new IOException("SYNTHETIC_HEADER_LIMIT");
    }
    public void close()throws Exception{closed=true;listener.close();threads.shutdownNow();threads.awaitTermination(3,TimeUnit.SECONDS);}
}
