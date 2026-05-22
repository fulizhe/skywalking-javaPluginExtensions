package org.apache.skywalking.apm.plugin.hutool.v5.http;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.plugin.override.httpclient.v4.OverrideHttpClientPluginConfig;
import org.junit.After;
import org.junit.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;

public class HutoolHttpResponseCollectorTest {
    private final boolean originalOverrideCollectSwitch =
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS;
    private final int originalThreshold = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD;

    @After
    public void tearDown() {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = originalOverrideCollectSwitch;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = originalThreshold;
    }

    @Test
    public void shouldCollectJsonResponseBodyWhenOverrideSwitchEnabled() throws Exception {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;

        final String body = "{\"code\":500,\"msg\":\"system error\"}";
        final String collected = withServer("/err", 500, "application/json", body, new ResponseCallback<String>() {
            @Override
            public String apply(final HttpRequest request, final HttpResponse response) {
                return HutoolHttpResponseCollector.collect(request, response);
            }
        });

        assertThat(collected, containsString("\"code\":500"));
    }

    @Test
    public void shouldSkipResponseBodyWhenOverrideSwitchDisabled() throws Exception {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = false;

        final String collected = withServer("/ok", 200, "application/json", "{\"code\":500}", new ResponseCallback<String>() {
            @Override
            public String apply(final HttpRequest request, final HttpResponse response) {
                return HutoolHttpResponseCollector.collect(request, response);
            }
        });

        assertThat(collected, is((String) null));
    }

    @Test
    public void shouldCollectPlainTextResponseBodyWhenOverrideSwitchEnabled() throws Exception {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;

        final String collected = withServer("/plain", 200, "text/plain", "mock-business-error", new ResponseCallback<String>() {
            @Override
            public String apply(final HttpRequest request, final HttpResponse response) {
                return HutoolHttpResponseCollector.collect(request, response);
            }
        });

        assertThat(collected, is("mock-business-error"));
    }

    @Test
    public void shouldSkipBinaryResponseBody() throws Exception {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;

        final String collected = withServer("/bin", 500, "application/octet-stream", "abcd", new ResponseCallback<String>() {
            @Override
            public String apply(final HttpRequest request, final HttpResponse response) {
                return HutoolHttpResponseCollector.collect(request, response);
            }
        });

        assertThat(collected, is((String) null));
    }

    @Test
    public void shouldClipResponseBodyToConfiguredThreshold() throws Exception {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 10;

        final String collected = withServer("/clip", 500, "text/plain", "0123456789abcdefghijklmnopqrstuvwxyz",
            new ResponseCallback<String>() {
                @Override
                public String apply(final HttpRequest request, final HttpResponse response) {
                    return HutoolHttpResponseCollector.collect(request, response);
                }
            });

        assertThat(collected, is("0123456789"));
    }

    private <T> T withServer(final String path,
                             final int status,
                             final String contentType,
                             final String body,
                             final ResponseCallback<T> callback) throws Exception {
        final HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(path, new FixedResponseHandler(status, contentType, body));
        server.start();
        try {
            final String url = "http://127.0.0.1:" + server.getAddress().getPort() + path;
            final HttpRequest request = HttpRequest.get(url);
            final HttpResponse response = request.execute();
            try {
                return callback.apply(request, response);
            } finally {
                response.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private interface ResponseCallback<T> {
        T apply(HttpRequest request, HttpResponse response);
    }

    private static final class FixedResponseHandler implements HttpHandler {
        private final int status;
        private final String contentType;
        private final byte[] bodyBytes;

        private FixedResponseHandler(final int status, final String contentType, final String body) {
            this.status = status;
            this.contentType = contentType;
            this.bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void handle(final HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bodyBytes.length);
            final OutputStream outputStream = exchange.getResponseBody();
            try {
                outputStream.write(bodyBytes);
            } finally {
                outputStream.close();
            }
        }
    }
}
