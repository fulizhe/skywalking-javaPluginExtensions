package org.apache.skywalking.apm.plugin.override.httpclient.v5;

import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.util.StringUtil;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

final class HttpClientResponseCollector {
    static final String TAG_KEY_HTTP_RESPONSE_BODY = "http.response.body";

    private static final ILog LOGGER = LogManager.getLogger(HttpClientResponseCollector.class);

    private HttpClientResponseCollector() {
    }

    static String collect(final ClassicHttpResponse response) {
        if (response == null || !HttpClientCollectionSwitch.isOverrideCollectEnabled()) {
            return null;
        }

        final HttpEntity entity = response.getEntity();
        // 5.x 中被解压(gzip)或被代理包装的响应实体通常不可重复; 跳过而不消费业务响应流。
        if (entity == null || !entity.isRepeatable()) {
            if (LOGGER.isDebugEnable()) {
                LOGGER.debug("### Skip httpclient5 response body collection, entityClass={}, repeatable={}, contentType={}",
                    entity == null ? null : entity.getClass().getName(),
                    entity != null && entity.isRepeatable(),
                    getContentTypeValue(entity));
            }
            return null;
        }

        if (LOGGER.isDebugEnable()) {
            LOGGER.debug("### Inspect httpclient5 response body collection, entityClass={}, repeatable={}, contentType={}",
                entity.getClass().getName(), entity.isRepeatable(), getContentTypeValue(entity));
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
            LOGGER.warn(e, "### Read httpclient5 response as text failed, entityClass={}, contentType={}, repeatable={}",
                entity.getClass().getName(), contentType, entity.isRepeatable());
            return null;
        }
    }

    private static String getContentTypeValue(final HttpEntity entity) {
        return entity.getContentType();
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
