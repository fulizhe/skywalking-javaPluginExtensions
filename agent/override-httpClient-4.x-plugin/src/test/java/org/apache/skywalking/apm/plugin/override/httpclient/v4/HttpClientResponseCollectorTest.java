package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;
import org.apache.http.util.EntityUtils;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class HttpClientResponseCollectorTest {
    private final boolean originalOverrideCollectSwitch = OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS;
    private final int originalThreshold = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD;

    @After
    public void tearDown() {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = originalOverrideCollectSwitch;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = originalThreshold;
    }

    @Test
    public void shouldCollectRepeatableJsonResponseBody() {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        final HttpResponse response = responseOf(
            new StringEntity("{\"code\":500,\"msg\":\"system error\"}", ContentType.APPLICATION_JSON));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is("{\"code\":500,\"msg\":\"system error\"}"));
    }

    @Test
    public void shouldSkipWhenOverrideSwitchDisabled() {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = false;
        final HttpResponse response = responseOf(new StringEntity("blocked", ContentType.TEXT_PLAIN));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is((String) null));
    }

    @Test
    public void shouldSkipBinaryResponseBody() {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final HttpResponse response = responseOf(new ByteArrayEntity(new byte[] {1, 2, 3, 4},
            ContentType.APPLICATION_OCTET_STREAM));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is((String) null));
    }

    @Test
    public void shouldClipCollectedResponseBody() {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 10;
        final HttpResponse response = responseOf(new StringEntity("0123456789abcdefghijklmnopqrstuvwxyz",
            ContentType.TEXT_PLAIN));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is("0123456789"));
    }

    @Test
    public void shouldSkipNonRepeatableResponseAndLeaveBusinessReadAvailable() throws Exception {
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final byte[] bytes = "stream-body".getBytes(StandardCharsets.UTF_8);
        final HttpEntity entity = new InputStreamEntity(new ByteArrayInputStream(bytes), bytes.length,
            ContentType.TEXT_PLAIN);
        final HttpResponse response = responseOf(entity);

        final String collected = HttpClientResponseCollector.collect(response);
        final String businessRead = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

        assertThat(collected, is((String) null));
        assertThat(businessRead, is("stream-body"));
    }

    private HttpResponse responseOf(final HttpEntity entity) {
        final BasicHttpResponse response = new BasicHttpResponse(
            new BasicStatusLine(org.apache.http.HttpVersion.HTTP_1_1, 200, "OK"));
        response.setEntity(entity);
        return response;
    }
}
