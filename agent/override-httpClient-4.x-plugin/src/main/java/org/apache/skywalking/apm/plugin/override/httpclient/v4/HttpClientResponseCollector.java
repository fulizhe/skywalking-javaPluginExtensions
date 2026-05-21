package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.util.StringUtil;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

// SEE HutoolHttpResponseCollector.java
final class HttpClientResponseCollector {
    static final String TAG_KEY_HTTP_RESPONSE_BODY = "http.response.body";

    private static final ILog LOGGER = LogManager.getLogger(HttpClientResponseCollector.class);

    private HttpClientResponseCollector() {
    }

    static String collect(final HttpResponse response) {
        if (response == null || !HttpClientCollectionSwitch.isOverrideCollectEnabled()) {
            return null;
        }

        final HttpEntity entity = response.getEntity();
        if (entity == null || !entity.isRepeatable()) {
        	LOGGER.warn("### Read httpclient response as text failed, entity is null or unRepeatable, {}", entity.isRepeatable());
            return null;
        }

        final String contentType = getContentTypeValue(entity);
        if (!isAllowedContentType(contentType)) {
            return null;
        }

        try {
            final Charset charset = resolveCharset(contentType);
            final String body = EntityUtils.toString(entity, charset);
            if (StringUtil.isEmpty(body)) {
                return null;
            }
            return clip(body);
        } catch (Exception e) {
            LOGGER.warn(e, "### Read httpclient response as text failed, entityClass={}, contentType={}, repeatable={}",
                entity.getClass().getName(), contentType, entity.isRepeatable());
            return null;
        }
    }

    private static String getContentTypeValue(final HttpEntity entity) {
        final Header header = entity.getContentType();
        return header == null ? null : header.getValue();
    }

    private static boolean isAllowedContentType(final String contentType) {
        if (contentType == null) {
            return false;
        }
        final String lowerCase = contentType.toLowerCase();
        return lowerCase.contains(ContentType.APPLICATION_JSON.getMimeType())
            || lowerCase.contains("+json")
            || lowerCase.contains(ContentType.TEXT_PLAIN.getMimeType());
    }

    private static Charset resolveCharset(final String contentType) {
        if (StringUtil.isEmpty(contentType)) {
            return StandardCharsets.UTF_8;
        }
        try {
            final ContentType parsed = ContentType.parse(contentType);
            if (parsed.getCharset() != null) {
                return parsed.getCharset();
            }
        } catch (Exception ignored) {
        }
        return StandardCharsets.UTF_8;
    }

    private static String clip(final String value) {
        final int threshold = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD;
        if (threshold > 0 && value.length() > threshold) {
            return StringUtil.cut(value, threshold);
        }
        return value;
    }
}
