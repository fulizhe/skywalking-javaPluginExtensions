package org.apache.skywalking.apm.plugin.override.httpclient.v4;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpRequest;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.FileEntity;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.entity.SerializableEntity;
import org.apache.http.entity.StringEntity;
import org.apache.http.util.EntityUtils;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.util.StringUtil;

import java.io.File;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class HttpClientParamCollector {
    static final String TAG_KEY_HTTP_PARAMS = "http.request.params";
    static final String TAG_KEY_HTTP_FILES = "http.request.files";

    private static final int READ_BUFFER_SIZE = 256;
    private static final ILog LOGGER = LogManager.getLogger(HttpClientParamCollector.class);

    private HttpClientParamCollector() {
    }

    static CollectedTags collect(final HttpRequest request) {
        final List<String> paramEntries = new ArrayList<String>();
        final List<String> fileEntries = new ArrayList<String>();
        final boolean officialCollectEnabled = HttpClientCollectionSwitch.isOfficialCollectEnabled();
        final boolean overrideCollectEnabled = HttpClientCollectionSwitch.isOverrideCollectEnabled();

        if (officialCollectEnabled) {
            collectQueryString(request, paramEntries);
        }
        if (overrideCollectEnabled) {
            collectEntity(request, paramEntries, fileEntries);
        }

        if (LOGGER.isDebugEnable()) {
            LOGGER.debug("### Httpclient param collection finished, requestType={}, officialEnabled={}, overrideEnabled={}, paramEntryCount={}, fileEntryCount={}",
                request == null ? null : request.getClass().getName(), officialCollectEnabled,
                overrideCollectEnabled, paramEntries.size(), fileEntries.size());
        }
        return new CollectedTags(joinEntries(paramEntries), joinEntries(fileEntries));
    }

    private static void collectQueryString(final HttpRequest request, final List<String> paramEntries) {
        if (!(request instanceof HttpUriRequest)) {
            return;
        }
        final URI uri = ((HttpUriRequest) request).getURI();
        if (uri == null || StringUtil.isEmpty(uri.getQuery())) {
            return;
        }
        paramEntries.add(clip("query=" + uri.getQuery()));
    }

    private static void collectEntity(final HttpRequest request,
                                      final List<String> paramEntries,
                                      final List<String> fileEntries) {
        if (!(request instanceof HttpEntityEnclosingRequest)) {
            return;
        }
        final HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
        if (entity == null) {
            return;
        }

        if (collectMultipart(entity, paramEntries, fileEntries)) {
            return;
        }

        if (collectFormEntity(entity, paramEntries)) {
            return;
        }

        if (collectKnownBinaryEntity(entity, fileEntries)) {
            return;
        }

        collectGenericEntity(entity, paramEntries);
    }

    private static boolean collectFormEntity(final HttpEntity entity, final List<String> paramEntries) {
        final String contentType = getContentTypeValue(entity);
        if (!containsIgnoreCase(contentType, ContentType.APPLICATION_FORM_URLENCODED.getMimeType())) {
            return false;
        }
        final String text = safeReadEntity(entity);
        if (StringUtil.isNotEmpty(text)) {
            paramEntries.add(clip("form=" + text));
            return true;
        }
        return false;
    }

    private static boolean collectKnownBinaryEntity(final HttpEntity entity, final List<String> fileEntries) {
        if (entity instanceof FileEntity) {
            final File file = extractFile((FileEntity) entity);
            fileEntries.add(clipFileEntry("body", file == null ? null : file.getName(),
                file == null ? entity.getContentLength() : file.length(),
                getContentTypeValue(entity)));
            return true;
        }
        if (entity instanceof ByteArrayEntity || entity instanceof InputStreamEntity || entity instanceof SerializableEntity) {
            fileEntries.add(clipFileEntry("body", null, entity.getContentLength(), getContentTypeValue(entity)));
            return true;
        }
        return false;
    }

    private static void collectGenericEntity(final HttpEntity entity, final List<String> paramEntries) {
        final String text;
        if (entity instanceof StringEntity) {
            text = safeReadEntity(entity);
        } else if (entity.isRepeatable()) {
            text = safeReadEntity(entity);
        } else {
            text = null;
        }

        if (StringUtil.isNotEmpty(text)) {
            paramEntries.add(clip("body=" + text));
            return;
        }

        paramEntries.add(clip("body_meta={contentType=" + nullSafe(getContentTypeValue(entity))
            + ",contentLength=" + entity.getContentLength()
            + ",repeatable=" + entity.isRepeatable() + "}"));
    }

    private static boolean collectMultipart(final HttpEntity entity,
                                            final List<String> paramEntries,
                                            final List<String> fileEntries) {
        final String contentType = getContentTypeValue(entity);
        final String className = entity.getClass().getName();
        if (!containsIgnoreCase(contentType, "multipart/") && !className.startsWith("org.apache.http.entity.mime.")) {
            return false;
        }

        final Object multipartEntity = unwrapMultipartEntity(entity);
        if (multipartEntity == null) {
            fileEntries.add(clipFileEntry("multipart", null, entity.getContentLength(), contentType));
            return true;
        }

        try {
            final Method getMultipart = multipartEntity.getClass().getDeclaredMethod("getMultipart");
            getMultipart.setAccessible(true);
            final Object multipartForm = getMultipart.invoke(multipartEntity);
            final Method getBodyParts = findMethod(multipartForm.getClass(), "getBodyParts");
            getBodyParts.setAccessible(true);
            final List<?> bodyParts = (List<?>) getBodyParts.invoke(multipartForm);
            for (Object bodyPart : bodyParts) {
                collectMultipartPart(bodyPart, paramEntries, fileEntries);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn(e, "### Collect multipart httpclient entity failed, entityClass={}, contentType={}, contentLength={}",
                    entity.getClass().getName(), contentType, entity.getContentLength());
            fileEntries.add(clipFileEntry("multipart", null, entity.getContentLength(), contentType));
            return true;
        }
    }

    private static Object unwrapMultipartEntity(final HttpEntity entity) {
        final String className = entity.getClass().getName();
        if ("org.apache.http.entity.mime.MultipartFormEntity".equals(className)) {
            return entity;
        }
        if ("org.apache.http.entity.mime.MultipartEntity".equals(className)) {
            try {
                final Method getEntity = entity.getClass().getDeclaredMethod("getEntity");
                getEntity.setAccessible(true);
                return getEntity.invoke(entity);
            } catch (Exception e) {
                LOGGER.warn(e, "### Unwrap multipart entity failed, entityClass={}", className);
                return null;
            }
        }
        return null;
    }

    private static void collectMultipartPart(final Object bodyPart,
                                             final List<String> paramEntries,
                                             final List<String> fileEntries) throws Exception {
        final Method getName = bodyPart.getClass().getMethod("getName");
        final Method getBody = bodyPart.getClass().getMethod("getBody");
        final String name = (String) getName.invoke(bodyPart);
        final Object contentBody = getBody.invoke(bodyPart);
        if (contentBody == null) {
            return;
        }

        final Method getFilename = contentBody.getClass().getMethod("getFilename");
        final Method getContentLength = contentBody.getClass().getMethod("getContentLength");
        final Method getMimeType = contentBody.getClass().getMethod("getMimeType");
        final String filename = (String) getFilename.invoke(contentBody);
        final long contentLength = ((Long) getContentLength.invoke(contentBody)).longValue();
        final String mimeType = (String) getMimeType.invoke(contentBody);

        if (StringUtil.isNotEmpty(filename) || isFileBody(contentBody)) {
            fileEntries.add(clipFileEntry(name, filename, contentLength, mimeType));
            return;
        }

        final String textValue = readMultipartText(contentBody);
        if (StringUtil.isNotEmpty(textValue)) {
            paramEntries.add(clip(name + "=" + textValue));
            return;
        }

        paramEntries.add(clip(name + "={contentType=" + nullSafe(mimeType) + ",contentLength=" + contentLength + "}"));
    }

    private static boolean isFileBody(final Object contentBody) {
        final String className = contentBody.getClass().getName();
        return "org.apache.http.entity.mime.content.FileBody".equals(className)
            || "org.apache.http.entity.mime.content.InputStreamBody".equals(className);
    }

    private static String readMultipartText(final Object contentBody) {
        final String className = contentBody.getClass().getName();
        if (!"org.apache.http.entity.mime.content.StringBody".equals(className)) {
            return null;
        }
        try {
            final Method getReader = contentBody.getClass().getMethod("getReader");
            final Reader reader = (Reader) getReader.invoke(contentBody);
            final StringBuilder builder = new StringBuilder();
            final char[] buffer = new char[READ_BUFFER_SIZE];
            int remaining = threshold();
            int read;
            while (remaining > 0 && (read = reader.read(buffer, 0, Math.min(buffer.length, remaining))) != -1) {
                builder.append(buffer, 0, read);
                remaining -= read;
            }
            reader.close();
            return builder.toString();
        } catch (Exception e) {
            LOGGER.warn(e, "### Read multipart text body failed, contentBodyClass={}", className);
            return null;
        }
    }

    private static String safeReadEntity(final HttpEntity entity) {
        try {
            Charset charset = StandardCharsets.UTF_8;
            final String contentType = getContentTypeValue(entity);
            if (StringUtil.isNotEmpty(contentType)) {
                final ContentType parsed = ContentType.parse(contentType);
                if (parsed.getCharset() != null) {
                    charset = parsed.getCharset();
                }
            }
            return EntityUtils.toString(entity, charset);
        } catch (Exception e) {
            LOGGER.warn(e, "### Read httpclient entity as text failed, entityClass={}, contentType={}, repeatable={}",
                    entity.getClass().getName(), getContentTypeValue(entity), entity.isRepeatable());
            return null;
        }
    }

    private static File extractFile(final FileEntity entity) {
        try {
            final Field fileField = FileEntity.class.getDeclaredField("file");
            fileField.setAccessible(true);
            return (File) fileField.get(entity);
        } catch (Exception e) {
            LOGGER.warn(e, "### Extract file metadata from FileEntity failed, contentType={}, contentLength={}",
                    getContentTypeValue(entity), entity.getContentLength());
            return null;
        }
    }

    private static Method findMethod(final Class<?> type, final String methodName) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(methodName);
    }

    private static String getContentTypeValue(final HttpEntity entity) {
        final Header header = entity.getContentType();
        return header == null ? null : header.getValue();
    }

    private static String clipFileEntry(final String fieldName,
                                        final String filename,
                                        final long size,
                                        final String contentType) {
        return clip(fieldName + "={filename=" + nullSafe(filename)
            + ",size=" + size
            + ",contentType=" + nullSafe(contentType) + "}");
    }

    private static String joinEntries(final List<String> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        final StringBuilder builder = new StringBuilder();
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                builder.append('&');
            }
            builder.append(entries.get(i));
        }
        return clip(builder.toString());
    }

    private static String clip(final String value) {
        if (value == null) {
            return null;
        }
        final int threshold = threshold();
        if (threshold > 0 && value.length() > threshold) {
            return StringUtil.cut(value, threshold);
        }
        return value;
    }

    private static int threshold() {
        return HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD;
    }

    private static boolean containsIgnoreCase(final String source, final String search) {
        return source != null && search != null && source.toLowerCase().contains(search.toLowerCase());
    }

    private static String nullSafe(final String value) {
        return value == null ? "" : value;
    }

    static final class CollectedTags {
        private final String params;
        private final String files;

        private CollectedTags(final String params, final String files) {
            this.params = params;
            this.files = files;
        }

        String getParams() {
            return params;
        }

        String getFiles() {
            return files;
        }
    }
}
