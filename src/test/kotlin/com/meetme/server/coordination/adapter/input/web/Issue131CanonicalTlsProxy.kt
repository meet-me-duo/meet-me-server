package com.meetme.server.coordination.adapter.input.web

import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Canonical DNS names retain their exact URL/profile while all sockets terminate at this synthetic loopback CONNECT proxy. */
internal class Issue131CanonicalTlsProxy(
    directory: Path,
) : AutoCloseable {
    data class Request(
        val host: String,
        val method: String,
        val path: String,
        val body: ByteArray,
    )

    private val ssl: SSLContext
    private val trust: X509TrustManager
    private val listener = ServerSocket()
    private val threads = Executors.newCachedThreadPool()
    private val replies = LinkedBlockingQueue<String>()
    val requests = CopyOnWriteArrayList<Request>()
    val failures = CopyOnWriteArrayList<String>()
    val connections = AtomicInteger()

    @Volatile private var closed = false

    init {
        val store = directory.resolve("synthetic-tls.p12")
        val password = "synthetic-fixture-only".toCharArray()
        val keytool =
            ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "synthetic",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=api.openai.com",
                "-ext",
                "SAN=dns:api.openai.com,dns:api.typesafe.ai,dns:generativelanguage.googleapis.com",
                "-storetype",
                "PKCS12",
                "-keystore",
                store.toString(),
                "-storepass",
                String(password),
                "-keypass",
                String(password),
                "-noprompt",
            ).redirectErrorStream(true).start()
        check(keytool.waitFor(20, TimeUnit.SECONDS) && keytool.exitValue() == 0) { "SYNTHETIC_TLS_SETUP_FAILED" }
        Files.setPosixFilePermissions(store, PosixFilePermissions.fromString("rw-------"))
        val keys = KeyStore.getInstance("PKCS12")
        Files.newInputStream(store).use { keys.load(it, password) }
        val km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keys, password) }
        val tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        trust = tm.trustManagers.filterIsInstance<X509TrustManager>().single()
        ssl = SSLContext.getInstance("TLS").apply { init(km.keyManagers, arrayOf<TrustManager>(trust), null) }
        listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        threads.submit {
            while (!closed) {
                try {
                    val socket = listener.accept()
                    connections.incrementAndGet()
                    threads.submit { serve(socket) }
                } catch (_: Exception) {
                    if (!closed) failures += "ACCEPT_FAILURE"
                }
            }
        }
    }

    fun enqueue(body: String) {
        replies.add(body)
    }

    fun clear() {
        replies.clear()
        requests.clear()
        failures.clear()
        connections.set(0)
    }

    fun client(): OkHttpClient =
        OkHttpClient
            .Builder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", listener.localPort)))
            .sslSocketFactory(ssl.socketFactory, trust)
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .build()

    private fun serve(socket: Socket) {
        try {
            socket.use {
                it.soTimeout = 5000
                val first = readHeaders(it.getInputStream()).lineSequence().first()
                val host =
                    when (first) {
                        "CONNECT api.openai.com:443 HTTP/1.1" -> "api.openai.com"
                        "CONNECT api.typesafe.ai:443 HTTP/1.1" -> "api.typesafe.ai"
                        "CONNECT generativelanguage.googleapis.com:443 HTTP/1.1" -> "generativelanguage.googleapis.com"
                        else -> {
                            failures += "UNEXPECTED_CANONICAL_HOST"
                            return
                        }
                    }
                it.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                it.getOutputStream().flush()
                (ssl.socketFactory.createSocket(it, host, 443, true) as SSLSocket).use { tls ->
                    tls.useClientMode = false
                    tls.startHandshake()
                    val lines = readHeaders(tls.inputStream).split("\r\n")
                    val requestLine = lines.first().split(" ")
                    val length =
                        lines
                            .drop(1)
                            .first { line -> line.startsWith("Content-Length:", true) }
                            .substringAfter(':')
                            .trim()
                            .toInt()
                    check(length in 1..16384)
                    val bytes = tls.inputStream.readNBytes(length)
                    check(bytes.size == length)
                    requests += Request(host, requestLine[0], requestLine[1], bytes)
                    val body = replies.poll() ?: error("UNEXPECTED_EXTRA_REQUEST")
                    val encoded = body.toByteArray(Charsets.UTF_8)
                    tls.outputStream.write(
                        (
                            "HTTP/1.1 200 Synthetic\r\nContent-Type: application/json\r\n" +
                                "Connection: close\r\nContent-Length: ${encoded.size}\r\n\r\n"
                        ).toByteArray(),
                    )
                    tls.outputStream.write(encoded)
                    tls.outputStream.flush()
                }
            }
        } catch (_: Exception) {
            if (!closed) failures += "SYNTHETIC_EXCHANGE_FAILURE"
        }
    }

    private fun readHeaders(input: InputStream): String {
        val output = ByteArrayOutputStream()
        var tail = 0
        while (output.size() < 32768) {
            val value = input.read()
            if (value < 0) throw EOFException()
            output.write(value)
            tail = (tail shl 8) or value
            if (tail == 0x0d0a0d0a) return output.toString(Charsets.ISO_8859_1)
        }
        error("SYNTHETIC_HEADER_LIMIT")
    }

    override fun close() {
        closed = true
        listener.close()
        threads.shutdownNow()
        check(threads.awaitTermination(5, TimeUnit.SECONDS))
    }
}
