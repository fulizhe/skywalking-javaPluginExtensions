package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.io.FileWriter;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class HttpClientParamCollectorTest {
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
    public void shouldCollectQueryAndFormBody() throws Exception {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = true;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final HttpPost request = new HttpPost("http://127.0.0.1:8080/path?a=1&b=two");
        request.setEntity(new StringEntity("x=10&y=hello", ContentType.APPLICATION_FORM_URLENCODED));

        final HttpClientParamCollector.CollectedTags tags = HttpClientParamCollector.collect(request);

        assertThat(tags.getParams(), containsString("query=a=1&b=two"));
        assertThat(tags.getParams(), containsString("form=x=10&y=hello"));
        assertThat(tags.getFiles(), is((String) null));
    }

    @Test
    public void shouldCollectMultipartFileSizeAndTextPart() throws Exception {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final File temp = File.createTempFile("httpclient4-", ".txt");
        final FileWriter writer = new FileWriter(temp);
        writer.write("abcdef");
        writer.close();

        final HttpEntity multipartEntity = MultipartEntityBuilder.create()
            .addTextBody("remark", "upload-demo", ContentType.TEXT_PLAIN)
            .addBinaryBody("file", temp, ContentType.APPLICATION_OCTET_STREAM, temp.getName())
            .build();
        final HttpPost request = new HttpPost("http://127.0.0.1:8080/upload");
        request.setEntity(multipartEntity);

        final HttpClientParamCollector.CollectedTags tags = HttpClientParamCollector.collect(request);

        assertThat(tags.getParams(), containsString("remark=upload-demo"));
        assertThat(tags.getFiles(), containsString("file={filename=" + temp.getName()));
        assertThat(tags.getFiles(), containsString("size=6"));

        temp.delete();
    }

    @Test
    public void shouldClipLargeBodyValues() throws Exception {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 16;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final HttpPost request = new HttpPost("http://127.0.0.1:8080/path");
        request.setEntity(new StringEntity("0123456789abcdefghijklmnopqrstuvwxyz", ContentType.TEXT_PLAIN));

        final HttpClientParamCollector.CollectedTags tags = HttpClientParamCollector.collect(request);

        assertThat(tags.getParams().length(), is(16));
    }

    @Test
    public void shouldReportOfficialCollectionSwitchWhenOfficialSwitchIsTurnedOn() {
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = true;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = false;

        assertThat(HttpClientCollectionSwitch.isOfficialCollectEnabled(), is(true));
        assertThat(HttpClientCollectionSwitch.isOverrideCollectEnabled(), is(false));
    }

    @Test
    public void shouldEnableOnlyOverrideCollectionWhenOnlyRuntimeOverrideSwitchIsTurnedOn() {
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = false;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = false;

        HttpClientCollectionSwitch.toggleRuntimeCollect(true);

        assertThat(HttpClientCollectionSwitch.isOfficialCollectEnabled(), is(false));
        assertThat(HttpClientCollectionSwitch.isOverrideCollectEnabled(), is(true));
        assertThat(OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS, is(true));
    }

    @Test
    public void shouldCollectOnlyQueryWhenOnlyOfficialSwitchIsTurnedOn() throws Exception {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = true;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = false;
        final HttpPost request = new HttpPost("http://127.0.0.1:8080/path?a=1&b=two");
        request.setEntity(new StringEntity("x=10&y=hello", ContentType.APPLICATION_FORM_URLENCODED));

        final HttpClientParamCollector.CollectedTags tags = HttpClientParamCollector.collect(request);

        assertThat(tags.getParams(), is("query=a=1&b=two"));
        assertThat(tags.getFiles(), is((String) null));
    }

    @Test
    public void shouldCollectOnlyBodyWhenOnlyOverrideSwitchIsTurnedOn() throws Exception {
        HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD = 1024;
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = false;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = true;
        final HttpPost request = new HttpPost("http://127.0.0.1:8080/path?a=1&b=two");
        request.setEntity(new StringEntity("x=10&y=hello", ContentType.APPLICATION_FORM_URLENCODED));

        final HttpClientParamCollector.CollectedTags tags = HttpClientParamCollector.collect(request);

        assertThat(tags.getParams(), is("form=x=10&y=hello"));
        assertThat(tags.getFiles(), is((String) null));
    }
}
