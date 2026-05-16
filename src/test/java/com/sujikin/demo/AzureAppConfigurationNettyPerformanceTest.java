package com.sujikin.demo;

import com.azure.core.http.HttpClient;
import com.azure.core.http.netty.NettyAsyncHttpClientBuilder;
import com.azure.data.appconfiguration.ConfigurationClient;
import com.azure.data.appconfiguration.ConfigurationClientBuilder;
import com.azure.data.appconfiguration.models.ConfigurationSetting;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AzureAppConfigurationNettyPerformanceTest {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String CONFIGURATION_KEY = "demo-message";
    private static final String CONFIGURATION_VALUE = "hello-from-local-app-configuration";
    private static final String RESPONSE_BODY = """
            {
              "etag": "\\"test-etag\\"",
              "key": "demo-message",
              "label": null,
              "content_type": "text/plain",
              "value": "hello-from-local-app-configuration",
              "last_modified": "2026-01-01T00:00:00Z",
              "locked": false,
              "tags": {}
            }
            """;

    @Test
    void benchmarksAzureAppConfigurationClientOverLocalTls() throws Exception {
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
                    .handle((request, response) -> response
                            .header("content-type", "application/vnd.microsoft.appconfig.kv+json; charset=utf-8")
                            .header("etag", "\"test-etag\"")
                            .sendString(Mono.just(RESPONSE_BODY)))
                    .bindNow();

            keepAliveProvider = ConnectionProvider.create("azure-app-config-benchmark-keepalive", 1);
            keepAliveClientSslContext = clientSslContext(provider);
            ConfigurationClient keepAliveClient = appConfigurationClient(server.port(),
                    azureClient(keepAliveProvider, keepAliveClientSslContext, true));

            newConnectionProvider = ConnectionProvider.newConnection();
            newConnectionClientSslContext = clientSslContext(provider);
            ConfigurationClient newConnectionClient = appConfigurationClient(server.port(),
                    azureClient(newConnectionProvider, newConnectionClientSslContext, false));

            Map<String, Object> results = new LinkedHashMap<>();
            results.put("benchmarkScenario", System.getProperty("demo.benchmark.scenario"));
            results.put("dockerImage", System.getProperty("demo.docker.image"));
            results.put("javaVersion", System.getProperty("java.version"));
            results.put("javaVendor", System.getProperty("java.vendor"));
            results.put("osName", System.getProperty("os.name"));
            results.put("osArch", System.getProperty("os.arch"));
            results.put("azureSdkClient", ConfigurationClient.class.getName());
            results.put("nettyCommonVersion", artifactVersion("netty-common"));
            results.put("nettyTcnativeVersion", implementationVersion("io.netty.internal.tcnative.SSL"));
            results.put("nettyOpenSslAvailable", OpenSsl.isAvailable());
            results.put("nettySslProvider", provider.name());
            results.put("nettyOpenSslVersion", OpenSsl.isAvailable() ? OpenSsl.versionString() : null);
            results.put("appConfigurationHandshakeHeavy", benchmark(newConnectionClient,
                    intProperty("demo.benchmark.handshakeWarmupRequests", 10),
                    intProperty("demo.benchmark.handshakeRequests", 100)));
            results.put("appConfigurationKeepAlive", benchmark(keepAliveClient,
                    intProperty("demo.benchmark.keepAliveWarmupRequests", 50),
                    intProperty("demo.benchmark.keepAliveRequests", 300)));

            String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results);
            System.out.println("azure-app-configuration-benchmark-result");
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

    private static ConfigurationClient appConfigurationClient(int port, HttpClient httpClient) {
        String secret = Base64.getEncoder().encodeToString("local-test-secret".getBytes(StandardCharsets.UTF_8));
        String connectionString = "Endpoint=https://localhost:" + port + ";Id=local-test-id;Secret=" + secret;

        return new ConfigurationClientBuilder()
                .connectionString(connectionString)
                .httpClient(httpClient)
                .buildClient();
    }

    private static Map<String, Object> benchmark(ConfigurationClient client, int warmupRequests, int measuredRequests) {
        for (int i = 0; i < warmupRequests; i++) {
            getSetting(client);
        }

        List<Long> latencies = new ArrayList<>(measuredRequests);
        long totalStarted = System.nanoTime();
        for (int i = 0; i < measuredRequests; i++) {
            long requestStarted = System.nanoTime();
            getSetting(client);
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

    private static void getSetting(ConfigurationClient client) {
        ConfigurationSetting setting = client.getConfigurationSetting(CONFIGURATION_KEY, null);
        assertThat(setting.getKey()).isEqualTo(CONFIGURATION_KEY);
        assertThat(setting.getValue()).isEqualTo(CONFIGURATION_VALUE);
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
                    AzureAppConfigurationNettyPerformanceTest.class.getClassLoader()).getPackage();
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
