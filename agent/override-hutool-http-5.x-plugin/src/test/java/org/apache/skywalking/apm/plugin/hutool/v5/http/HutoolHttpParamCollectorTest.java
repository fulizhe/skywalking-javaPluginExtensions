package org.apache.skywalking.apm.plugin.hutool.v5.http;

import cn.hutool.core.io.resource.BytesResource;
import cn.hutool.http.HttpRequest;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.plugin.override.httpclient.v4.OverrideHttpClientPluginConfig;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class HutoolHttpParamCollectorTest {
    private final boolean originalCollectSwitch = HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    private final boolean originalOverrideCollectSwitch = OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS;
    private final int originalThreshold = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD;

    @After
    public void tearDown() {
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = originalCollectSwitch;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = originalOverrideCollectSwitch;
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = originalThreshold;
    }

    @Test
    public void shouldCollectQueryAndFormBody() {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = true;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final HttpRequest request = HttpRequest.post("http://127.0.0.1:8080/path?a=1&b=two")
            .form("x", "10")
            .form("y", "hello");

        final HutoolHttpParamCollector.CollectedTags tags = HutoolHttpParamCollector.collect(request);

        assertThat(tags.getParams(), containsString("query=a=1&b=two"));
        assertThat(tags.getParams(), containsString("form=x=10&y=hello"));
        assertThat(tags.getFiles(), is((String) null));
    }

    @Test
    public void shouldCollectMultipartFileSizeAndTextPart() throws Exception {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final File temp = File.createTempFile("hutool-http-", ".txt");
        final FileWriter writer = new FileWriter(temp);
        writer.write("abcdef");
        writer.close();

        final HttpRequest request = HttpRequest.post("http://127.0.0.1:8080/upload")
            .form("remark", "upload-demo")
            .form("file", temp);

        final HutoolHttpParamCollector.CollectedTags tags = HutoolHttpParamCollector.collect(request);

        assertThat(tags.getParams(), containsString("remark=upload-demo"));
        assertThat(tags.getFiles(), containsString("file={filename=" + temp.getName()));
        assertThat(tags.getFiles(), containsString("size=6"));

        temp.delete();
    }

    @Test
    public void shouldClipLargeBodyValues() {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 16;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final HttpRequest request = HttpRequest.post("http://127.0.0.1:8080/path")
            .body("0123456789abcdefghijklmnopqrstuvwxyz");

        final HutoolHttpParamCollector.CollectedTags tags = HutoolHttpParamCollector.collect(request);

        assertThat(tags.getParams().length(), is(16));
    }

    @Test
    public void shouldCollectTextBodyFromResourceStyleBodyField() {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;

        final ResourceStyleHttpRequest request = new ResourceStyleHttpRequest("http://127.0.0.1:8080/path");
        request.header("Content-Type", "application/json");
        request.setResourceBody(new BytesResource("{\"x\":1}".getBytes(), "payload.json"));

        final HutoolHttpParamCollector.CollectedTags tags = HutoolHttpParamCollector.collect(request);

        assertThat(tags.getParams(), containsString("body={\"x\":1}"));
        assertThat(tags.getFiles(), is((String) null));
    }

    @Test
    public void shouldCollectBinaryBodyFromResourceStyleBodyFieldAsFileTag() {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;

        final ResourceStyleHttpRequest request = new ResourceStyleHttpRequest("http://127.0.0.1:8080/path");
        request.header("Content-Type", "application/octet-stream");
        request.setResourceBody(new BytesResource(new byte[] {1, 2, 3, 4}, "payload.bin"));

        final HutoolHttpParamCollector.CollectedTags tags = HutoolHttpParamCollector.collect(request);

        assertThat(tags.getParams(), is((String) null));
        assertThat(tags.getFiles(), containsString("body={filename=payload.bin,size=4"));
    }

    @Test
    public void shouldReportOfficialAndOverrideCollectionSwitches() {
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = true;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = false;

        assertThat(HutoolHttpCollectionSwitch.isOfficialCollectEnabled(), is(true));
        assertThat(HutoolHttpCollectionSwitch.isOverrideCollectEnabled(), is(false));
    }

    @Test
    public void shouldCollectOnlyQueryWhenOnlyOfficialSwitchIsTurnedOn() {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = true;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = false;
        final HttpRequest request = HttpRequest.post("http://127.0.0.1:8080/path?a=1&b=two")
            .form("x", "10")
            .form("y", "hello");

        final HutoolHttpParamCollector.CollectedTags tags = HutoolHttpParamCollector.collect(request);

        assertThat(tags.getParams(), is("query=a=1&b=two"));
        assertThat(tags.getFiles(), is((String) null));
    }

    @Test
    public void shouldCollectOnlyBodyWhenOnlyOverrideSwitchIsTurnedOn() {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = false;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final HttpRequest request = HttpRequest.post("http://127.0.0.1:8080/path?a=1&b=two")
            .form("x", "10")
            .form("y", "hello");

        final HutoolHttpParamCollector.CollectedTags tags = HutoolHttpParamCollector.collect(request);

        assertThat(tags.getParams(), is("form=x=10&y=hello"));
        assertThat(tags.getFiles(), is((String) null));
    }

    private static final class ResourceStyleHttpRequest extends HttpRequest {
        private BytesResource body;

        private ResourceStyleHttpRequest(final String url) {
            super(url);
        }

        private void setResourceBody(final BytesResource body) {
            this.body = body;
        }
    }
}
