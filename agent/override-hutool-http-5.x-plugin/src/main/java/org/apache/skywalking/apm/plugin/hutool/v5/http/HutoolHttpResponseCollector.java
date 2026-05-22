package org.apache.skywalking.apm.plugin.hutool.v5.http;

import cn.hutool.http.Header;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;

import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

// SEE HttpClientResponseCollector.java
final class HutoolHttpResponseCollector {
    static final String TAG_KEY_HTTP_RESPONSE_BODY = "http.response.body";

    private static final ILog LOGGER = LogManager.getLogger(HutoolHttpResponseCollector.class);

    private HutoolHttpResponseCollector() {
    }

    static String collect(final HttpRequest request, final HttpResponse response) {
        if (request == null || response == null) {
            return null;
        }
        if (!HutoolHttpCollectionSwitch.isResponseCollectEnabled()) {
            return null;
        }

        final String contentType = response.header(Header.CONTENT_TYPE);
        if (!isAllowedContentType(contentType)) {
            return null;
        }
        if (isAsyncResponse(response)) {
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("### Skip hutool http response body collection for async response, url={}",
                    request.getUrl());
            }
            return null;
        }

        final byte[] bodyBytes = response.bodyBytes();
        if (bodyBytes == null || bodyBytes.length == 0) {
            return null;
        }

        final int threshold = Math.max(0, HutoolHttpCollectionSwitch.responseThreshold());
        final int length = threshold > 0 ? Math.min(bodyBytes.length, threshold) : bodyBytes.length;
        return new String(bodyBytes, 0, length, resolveCharset(response));
    }

    private static boolean isAllowedContentType(final String contentType) {
        if (contentType == null) {
            return false;
        }
        final String lowerCase = contentType.toLowerCase();
        return lowerCase.contains("application/json")
            || lowerCase.contains("+json")
            || lowerCase.contains("text/plain");
    }

    private static boolean isAsyncResponse(final HttpResponse response) {
        try {
            final Field isAsyncField = response.getClass().getDeclaredField("isAsync");
            isAsyncField.setAccessible(true);
            final Object value = isAsyncField.get(response);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Charset resolveCharset(final HttpResponse response) {
        final String charset = response.charset();
        if (charset == null || charset.trim().isEmpty()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charset);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }
}
