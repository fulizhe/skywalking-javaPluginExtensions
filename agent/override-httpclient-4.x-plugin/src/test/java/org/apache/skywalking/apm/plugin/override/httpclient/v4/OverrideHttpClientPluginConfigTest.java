package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.util.ConfigInitializer;
import org.junit.After;
import org.junit.Test;

import java.util.Properties;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class OverrideHttpClientPluginConfigTest {
    private static final String KEY_OFFICIAL = "plugin.httpclient.collect_http_params";
    private static final String KEY_OVERRIDE = "plugin.overridehttpclient.collect_http_params";

    private final boolean originalOfficial = HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    private final boolean originalOverride = OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS;

    @After
    public void tearDown() {
        HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS = originalOfficial;
        OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS = originalOverride;
    }

    @Test
    public void shouldLoadOverrideSwitchFromOverrideConfigKeyOnly() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty(KEY_OVERRIDE, "true");
        properties.setProperty(KEY_OFFICIAL, "false");

        ConfigInitializer.initialize(properties, OverrideHttpClientPluginConfig.class);

        assertThat(OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS, is(true));
        assertThat(HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS, is(originalOfficial));
    }

    @Test
    public void shouldLoadOfficialSwitchFromOfficialConfigKeyOnly() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty(KEY_OFFICIAL, "true");
        properties.setProperty(KEY_OVERRIDE, "false");

        ConfigInitializer.initialize(properties, HttpClientPluginConfig.class);

        assertThat(HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS, is(true));
        assertThat(OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS, is(originalOverride));
    }

    @Test
    public void shouldAllowIndependentOfficialAndOverrideSwitchesInAgentConfig() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty(KEY_OFFICIAL, "true");
        properties.setProperty(KEY_OVERRIDE, "true");

        ConfigInitializer.initialize(properties, HttpClientPluginConfig.class);
        ConfigInitializer.initialize(properties, OverrideHttpClientPluginConfig.class);

        assertThat(HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS, is(true));
        assertThat(OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS, is(true));
        assertThat(HttpClientCollectionSwitch.isOfficialCollectEnabled(), is(true));
        assertThat(HttpClientCollectionSwitch.isOverrideCollectEnabled(), is(true));
    }
}
