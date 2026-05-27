package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.skywalking.apm.agent.core.reporter.logfile.LogFileReporterPluginConfig;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class WebhookUrlResolverTest {

    @Before
    @After
    public void reset() {
        WebhookUrlResolver.resetEnvProviderForTest();
        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_URL = "";
        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PATH = "";
        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_HOST = "127.0.0.1";
        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_ENV = "WebPort";
        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PORT_DEFAULT = 9600;
    }

    @Test
    public void resolveTemplateUsesDefaultWhenEnvMissing() {
        WebhookUrlResolver.setEnvForTest(Collections.<String, String>emptyMap());

        assertEquals("http://127.0.0.1:9600/internal/trace-alert",
                WebhookUrlResolver.resolveTemplate("http://127.0.0.1:${WebPort:9600}/internal/trace-alert"));
    }

    @Test
    public void resolveTemplateUsesCustomWebPortFromEnv() {
        final Map<String, String> env = new HashMap<String, String>();
        env.put("WebPort", "9810");
        WebhookUrlResolver.setEnvForTest(env);

        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_URL =
                "http://127.0.0.1:${WebPort:9600}/internal/trace-alert";

        assertEquals("http://127.0.0.1:9810/internal/trace-alert",
                WebhookUrlResolver.resolveConfiguredUrl());
    }

    @Test
    public void buildFromPathOnlyTemplateWithCustomWebPort() {
        final Map<String, String> env = new HashMap<String, String>();
        env.put("WebPort", "9701");
        WebhookUrlResolver.setEnvForTest(env);

        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PATH = "/internal/trace-alert";

        assertEquals("http://127.0.0.1:${WebPort:9600}/internal/trace-alert",
                WebhookUrlResolver.getWebhookUrlTemplate());
        assertEquals("http://127.0.0.1:9701/internal/trace-alert",
                WebhookUrlResolver.resolveConfiguredUrl());
    }

    @Test
    public void buildFromPathOnlyTemplateUsesDefaultWhenEnvMissing() {
        WebhookUrlResolver.setEnvForTest(Collections.<String, String>emptyMap());

        LogFileReporterPluginConfig.Plugin.LogFileReporter.Alert.WEBHOOK_PATH = "/internal/trace-alert";
        assertEquals("http://127.0.0.1:9600/internal/trace-alert",
                WebhookUrlResolver.resolveConfiguredUrl());
    }

    @Test
    public void returnsNullWhenNotConfigured() {
        assertNull(WebhookUrlResolver.getWebhookUrlTemplate());
    }
}
