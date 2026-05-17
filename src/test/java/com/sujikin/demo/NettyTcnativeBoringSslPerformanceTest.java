package com.sujikin.demo;

import com.azure.core.http.HttpClient;
import com.azure.core.http.HttpMethod;
import com.azure.core.http.HttpRequest;
import com.azure.core.http.HttpResponse;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCounted;
import io.netty.util.Version;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NettyTcnativeBoringSslPerformanceTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String RESPONSE_BODY = """
            {"value":[{"id":1,"name":"Ada Lovelace","email":"ada@example.test","city":"London"}]}
            """;

    @Test
    void benchmarksAzureNettyClientOverLocalTls() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("demo.benchmark.enabled"),
                "set -Ddemo.benchmark.enabled=true to run the benchmark");

        SelfSignedCertificate certificate = new SelfSignedCertificate("localhost");
        SslContext serverSslContext = null;
        SslContext keepAliveClientSslContext = null;
        SslContext newConnectionClientSslContext = null;
        DisposableServer server = null;
        ConnectionProvider keepAliveProvider = null;
        ConnectionProvider newConnectionProvider = null;

        try {
            SslProvider provider = OpenSsl.isAvailable() ? SslProvider.OPENSSL : SslProvider.JDK;
            serverSslContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
                    .sslProvider(provider)
                    .build();
            SslContext configuredServerSslContext = serverSslContext;

            server = HttpServer.create()
                    .host("127.0.0.1")
                    .port(0)
                    .secure(spec -> spec.sslContext(configuredServerSslContext))
                    .route(routes -> routes.get("/people", (request, response) -> response
                            .header("content-type", "application/json")
                            .sendString(Mono.just(RESPONSE_BODY))))
                    .bindNow();

            URL url = new URL("https://localhost:" + server.port() + "/people");

            keepAliveProvider = ConnectionProvider.create("azure-netty-benchmark-keepalive", 1);
            keepAliveClientSslContext = clientSslContext(provider);
            HttpClient keepAliveClient = azureClient(keepAliveProvider, keepAliveClientSslContext, true);

            newConnectionProvider = ConnectionProvider.newConnection();
            newConnectionClientSslContext = clientSslContext(provider);
            HttpClient newConnectionClient = azureClient(newConnectionProvider, newConnectionClientSslContext, false);

            Map<String, Object> results = new LinkedHashMap<>();
            results.put("benchmarkScenario", System.getProperty("demo.benchmark.scenario"));
            results.put("dockerImage", System.getProperty("demo.docker.image"));
            results.put("javaVersion", System.getProperty("java.version"));
            results.put("javaVendor", System.getProperty("java.vendor"));
            results.put("osName", System.getProperty("os.name"));
            results.put("osArch", System.getProperty("os.arch"));
            results.put("azureHttpClient", keepAliveClient.getClass().getName());
            results.put("nettyCommonVersion", artifactVersion("netty-common"));
            results.put("nettyTcnativeVersion", implementationVersion("io.netty.internal.tcnative.SSL"));
            results.put("nettyNoOpenSsl", Boolean.getBoolean("io.netty.handler.ssl.noOpenSsl"));
            results.put("nettyOpenSslAvailable", OpenSsl.isAvailable());
            results.put("nettySslProvider", provider.name());
            results.put("nettyOpenSslVersion", OpenSsl.isAvailable() ? OpenSsl.versionString() : null);
            results.put("handshakeHeavy", benchmark(newConnectionClient, url,
                    intProperty("demo.benchmark.handshakeWarmupRequests", 25),
                    intProperty("demo.benchmark.handshakeRequests", 200)));
            results.put("keepAlive", benchmark(keepAliveClient, url,
                    intProperty("demo.benchmark.keepAliveWarmupRequests", 100),
                    intProperty("demo.benchmark.keepAliveRequests", 1000)));

            String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results);
            System.out.println("azure-netty-benchmark-result");
            System.out.println(json);

            String output = System.getProperty("demo.benchmark.output");
            if (output != null && !output.isBlank()) {
                Path path = Path.of(output);
                Files.createDirectories(path.toAbsolutePath().getParent());
                Files.writeString(path, json);
            }
        } finally {
            dispose(newConnectionProvider);
            dispose(keepAliveProvider);
            if (server != null) {
                server.disposeNow();
            }
            release(newConnectionClientSslContext);
            release(keepAliveClientSslContext);
            release(serverSslContext);
            certificate.delete();
        }
    }

    private static SslContext clientSslContext(SslProvider provider) throws Exception {
        return SslContextBuilder.forClient()
                .sslProvider(provider)
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();
    }

    private static HttpClient azureClient(ConnectionProvider connectionProvider, SslContext sslContext, boolean keepAlive) {
        reactor.netty.http.client.HttpClient reactorClient = reactor.netty.http.client.HttpClient
                .create(connectionProvider)
                .secure(spec -> spec.sslContext(sslContext))
                .keepAlive(keepAlive)
                .compress(false)
                .responseTimeout(REQUEST_TIMEOUT);

        return new NettyAsyncHttpClientBuilder(reactorClient).build();
    }

    private static Map<String, Object> benchmark(HttpClient client, URL url, int warmupRequests, int measuredRequests) {
        for (int i = 0; i < warmupRequests; i++) {
            send(client, url);
        }

        List<Long> latencies = new ArrayList<>(measuredRequests);
        long totalStarted = System.nanoTime();
        for (int i = 0; i < measuredRequests; i++) {
            long requestStarted = System.nanoTime();
            send(client, url);
            latencies.add(System.nanoTime() - requestStarted);
        }
        long elapsed = System.nanoTime() - totalStarted;
        Collections.sort(latencies);

        double elapsedSeconds = elapsed / 1_000_000_000.0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", measuredRequests);
        result.put("elapsedMs", nanosToMillis(elapsed));
        result.put("requestsPerSecond", measuredRequests / elapsedSeconds);
        result.put("averageMs", nanosToMillis(elapsed / measuredRequests));
        result.put("p50Ms", percentileMillis(latencies, 50));
        result.put("p95Ms", percentileMillis(latencies, 95));
        result.put("p99Ms", percentileMillis(latencies, 99));
        return result;
    }

    private static void send(HttpClient client, URL url) {
        HttpResponse response = client.send(new HttpRequest(HttpMethod.GET, url))
                .block(REQUEST_TIMEOUT);

        assertThat(response).isNotNull();
        try {
            assertThat(response.getStatusCode()).isEqualTo(200);
            assertThat(response.getBodyAsByteArray().block(REQUEST_TIMEOUT)).isNotEmpty();
        } finally {
            response.close();
        }
    }

    private static int intProperty(String name, int defaultValue) {
        return Integer.parseInt(System.getProperty(name, Integer.toString(defaultValue)));
    }

    private static String artifactVersion(String artifactId) {
        Version version = Version.identify().get(artifactId);
        return version == null ? null : version.artifactVersion();
    }

    private static String implementationVersion(String className) {
        try {
            Package packageInfo = Class.forName(className, false,
                    NettyTcnativeBoringSslPerformanceTest.class.getClassLoader()).getPackage();
            return packageInfo == null ? null : packageInfo.getImplementationVersion();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static double percentileMillis(List<Long> sortedLatencies, int percentile) {
        int index = (int) Math.ceil((percentile / 100.0) * sortedLatencies.size()) - 1;
        return nanosToMillis(sortedLatencies.get(Math.max(0, Math.min(index, sortedLatencies.size() - 1))));
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static void dispose(ConnectionProvider connectionProvider) {
        if (connectionProvider != null) {
            connectionProvider.disposeLater().block(Duration.ofSeconds(10));
        }
    }

    private static void release(SslContext sslContext) {
        if (sslContext instanceof ReferenceCounted referenceCounted) {
            referenceCounted.release();
        }
    }
}
