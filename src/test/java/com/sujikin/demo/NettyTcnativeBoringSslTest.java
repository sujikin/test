package com.sujikin.demo;

import com.azure.core.http.HttpClient;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.util.ReferenceCounted;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class NettyTcnativeBoringSslTest {

    @Test
    void reportsNativeBoringSslAvailabilityForAzureNettyTransport() throws Exception {
        HttpClient azureNettyClient = HttpClient.createDefault();

        boolean nativeBoringSslAvailable = OpenSsl.isAvailable();
        String diagnostics = diagnostics(azureNettyClient, nativeBoringSslAvailable);
        System.out.println(diagnostics);

        if (nativeBoringSslAvailable) {
            SslContext sslContext = SslContextBuilder.forClient()
                    .sslProvider(SslProvider.OPENSSL)
                    .build();
            if (sslContext instanceof ReferenceCounted referenceCounted) {
                referenceCounted.release();
            }
        }

        String expected = System.getProperty("demo.expectedOpenSslAvailable");
        if (expected != null) {
            assertThat(nativeBoringSslAvailable)
                    .as(diagnostics)
                    .isEqualTo(Boolean.parseBoolean(expected));
        }
    }

    private static String diagnostics(HttpClient azureNettyClient, boolean nativeBoringSslAvailable) {
        StringBuilder builder = new StringBuilder();
        builder.append("java.version=").append(System.getProperty("java.version")).append(System.lineSeparator());
        builder.append("java.vendor=").append(System.getProperty("java.vendor")).append(System.lineSeparator());
        builder.append("os.name=").append(System.getProperty("os.name")).append(System.lineSeparator());
        builder.append("os.arch=").append(System.getProperty("os.arch")).append(System.lineSeparator());
        builder.append("azure.http.client=").append(azureNettyClient.getClass().getName()).append(System.lineSeparator());
        builder.append("netty.openssl.available=").append(nativeBoringSslAvailable).append(System.lineSeparator());

        if (nativeBoringSslAvailable) {
            builder.append("netty.openssl.version=").append(OpenSsl.versionString()).append(System.lineSeparator());
            builder.append("netty.openssl.alpn=").append(OpenSsl.isAlpnSupported()).append(System.lineSeparator());
        } else {
            Throwable cause = OpenSsl.unavailabilityCause();
            builder.append("netty.openssl.unavailabilityCause=").append(cause).append(System.lineSeparator());
            if (cause != null) {
                StringWriter writer = new StringWriter();
                cause.printStackTrace(new PrintWriter(writer));
                builder.append(writer);
            }
        }

        return builder.toString();
    }
}
