package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpTraceAnomalyListenerTest {

    @Before
    public void setUp() {
        WebhookUrlResolver.resetEnvProviderForTest();
    }

    @After
    public void tearDown() {
        WebhookUrlResolver.resetEnvProviderForTest();
    }

    @Test
    public void cachesUrlAfterFirstFullyResolvedParse() throws Exception {
        final Map<String, String> env = new HashMap<String, String>();
        env.put("WebPort", "9810");
        WebhookUrlResolver.setEnvForTest(env);

        final HttpTraceAnomalyListener listener = new HttpTraceAnomalyListener(
                "http://127.0.0.1:${WebPort:9600}/inner/sw/trace-alert");
        final Method resolve = HttpTraceAnomalyListener.class.getDeclaredMethod("resolveWebhookUrl");
        resolve.setAccessible(true);

        assertEquals("http://127.0.0.1:9810/inner/sw/trace-alert", resolve.invoke(listener));

        env.put("WebPort", "9999");
        WebhookUrlResolver.setEnvForTest(env);
        assertEquals("http://127.0.0.1:9810/inner/sw/trace-alert", resolve.invoke(listener));
    }

    @Test
    public void retriesUntilPlaceholderExpanded() throws Exception {
        WebhookUrlResolver.setEnvForTest(Collections.<String, String>emptyMap());

        final HttpTraceAnomalyListener listener = new HttpTraceAnomalyListener(
                "http://127.0.0.1:${WebPort:9600}/alert");
        final Method resolve = HttpTraceAnomalyListener.class.getDeclaredMethod("resolveWebhookUrl");
        resolve.setAccessible(true);

        assertEquals("http://127.0.0.1:9600/alert", resolve.invoke(listener));
        assertEquals("http://127.0.0.1:9600/alert", resolve.invoke(listener));
    }
}
