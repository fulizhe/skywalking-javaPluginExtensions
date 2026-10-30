package org.apache.skywalking.apm.plugin.override.httpclient.v5;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class HttpClientResponseCollectorTest {
    private final boolean originalOverrideCollectSwitch = OverrideHttpClient5PluginConfig.Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS;
    private final int originalThreshold = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD;

    @After
    public void tearDown() {
        OverrideHttpClient5PluginConfig.Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS = originalOverrideCollectSwitch;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = originalThreshold;
    }

    @Test
    public void shouldCollectRepeatableJsonResponseBody() {
        OverrideHttpClient5PluginConfig.Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS = true;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        final ClassicHttpResponse response = responseOf(
            new StringEntity("{\"code\":500,\"msg\":\"system error\"}", ContentType.APPLICATION_JSON));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is("{\"code\":500,\"msg\":\"system error\"}"));
    }

    @Test
    public void shouldSkipWhenOverrideSwitchDisabled() {
        OverrideHttpClient5PluginConfig.Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS = false;
        final ClassicHttpResponse response = responseOf(new StringEntity("blocked", ContentType.TEXT_PLAIN));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is((String) null));
    }

    @Test
    public void shouldSkipBinaryResponseBody() {
        OverrideHttpClient5PluginConfig.Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS = true;
        final ClassicHttpResponse response = responseOf(new ByteArrayEntity(new byte[] {1, 2, 3, 4},
            ContentType.APPLICATION_OCTET_STREAM));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is((String) null));
    }

    @Test
    public void shouldClipCollectedResponseBody() {
        OverrideHttpClient5PluginConfig.Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS = true;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 10;
        final ClassicHttpResponse response = responseOf(new StringEntity("0123456789abcdefghijklmnopqrstuvwxyz",
            ContentType.TEXT_PLAIN));

        final String collected = HttpClientResponseCollector.collect(response);

        assertThat(collected, is("0123456789"));
    }

    @Test
    public void shouldSkipNonRepeatableResponseAndLeaveBusinessReadAvailable() throws Exception {
        OverrideHttpClient5PluginConfig.Plugin.OverrideHttpClient5.COLLECT_HTTP_PARAMS = true;
        final byte[] bytes = "stream-body".getBytes(StandardCharsets.UTF_8);
        final HttpEntity entity = new InputStreamEntity(new ByteArrayInputStream(bytes), bytes.length,
            ContentType.TEXT_PLAIN);
        final ClassicHttpResponse response = responseOf(entity);

        final String collected = HttpClientResponseCollector.collect(response);
        final String businessRead = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);

        assertThat(collected, is((String) null));
        assertThat(businessRead, is("stream-body"));
    }

    private ClassicHttpResponse responseOf(final HttpEntity entity) {
        final BasicClassicHttpResponse response = new BasicClassicHttpResponse(200, "OK");
        response.setEntity(entity);
        return response;
    }
}
