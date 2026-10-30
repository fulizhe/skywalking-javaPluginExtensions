package org.apache.skywalking.apm.plugin.override.httpclient.v5;

import org.apache.hc.client5.http.entity.mime.ByteArrayBody;
import org.apache.hc.client5.http.entity.mime.ContentBody;
import org.apache.hc.client5.http.entity.mime.FileBody;
import org.apache.hc.client5.http.entity.mime.FormBodyPart;
import org.apache.hc.client5.http.entity.mime.InputStreamBody;
import org.apache.hc.client5.http.entity.mime.MultipartPart;
import org.apache.hc.client5.http.entity.mime.StringBody;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.FileEntity;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.http.io.entity.SerializableEntity;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.util.StringUtil;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
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
    /**
     * 单次请求体读取的硬上限。阈值 &lt;= 0 时也用它兜底, 避免无界缓冲。
     */
    private static final int MAX_READ_BYTES = 1024 * 1024;
    private static final String MULTIPART_ENTITY_CLASS =
        "org.apache.hc.client5.http.entity.mime.MultipartFormEntity";
    private static final String MULTIPART_PACKAGE =
        "org.apache.hc.client5.http.entity.mime.";
    private static final ILog LOGGER = LogManager.getLogger(HttpClientParamCollector.class);

    private HttpClientParamCollector() {
    }

    static CollectedTags collect(final ClassicHttpRequest request) {
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
            LOGGER.debug("### Httpclient5 param collection finished, requestType={}, officialEnabled={}, overrideEnabled={}, paramEntryCount={}, fileEntryCount={}",
                request == null ? null : request.getClass().getName(), officialCollectEnabled,
                overrideCollectEnabled, paramEntries.size(), fileEntries.size());
        }
        return new CollectedTags(joinEntries(paramEntries), joinEntries(fileEntries));
    }

    private static void collectQueryString(final ClassicHttpRequest request, final List<String> paramEntries) {
        if (request == null) {
            return;
        }
        try {
            final URI uri = request.getUri();
            if (uri == null || StringUtil.isEmpty(uri.getQuery())) {
                return;
            }
            paramEntries.add(clip("query=" + uri.getQuery()));
        } catch (Exception e) {
            LOGGER.warn(e, "### Read httpclient5 request uri failed, requestType={}",
                request.getClass().getName());
        }
    }

    private static void collectEntity(final ClassicHttpRequest request,
                                      final List<String> paramEntries,
                                      final List<String> fileEntries) {
        if (request == null) {
            return;
        }
        final HttpEntity entity = request.getEntity();
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
        // 读取不可重复实体会耗尽请求体, 破坏真正发出的请求; 交给后面的通用分支只记 body_meta。
        if (!entity.isRepeatable()) {
            // 不消费不可重复实体(会耗尽真正发出的请求体), 但仍按 contentType 归为表单,
            // 只记录元数据, 避免被误判成文件上传。
            paramEntries.add(clip("form_meta={contentType=" + nullSafe(contentType)
                + ",contentLength=" + entity.getContentLength()
                + ",repeatable=false}"));
            return true;
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
            final File file = extractFile(entity);
            fileEntries.add(clipFileEntry("body", file == null ? null : file.getName(),
                file == null ? entity.getContentLength() : file.length(),
                getContentTypeValue(entity)));
            return true;
        }
        if (entity instanceof ByteArrayEntity || entity instanceof InputStreamEntity
            || entity instanceof SerializableEntity) {
            fileEntries.add(clipFileEntry("body", null, entity.getContentLength(), getContentTypeValue(entity)));
            return true;
        }
        return false;
    }

    private static void collectGenericEntity(final HttpEntity entity, final List<String> paramEntries) {
        // StringEntity 本就是可重复实体, 非重复实体不读, 避免耗尽业务流。
        final String text = entity.isRepeatable() ? safeReadEntity(entity) : null;

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
        if (!containsIgnoreCase(contentType, "multipart/") && !className.startsWith(MULTIPART_PACKAGE)) {
            return false;
        }

        final Object multipart = unwrapMultipart(entity);
        if (multipart == null) {
            fileEntries.add(clipFileEntry("multipart", null, entity.getContentLength(), contentType));
            return true;
        }

        try {
            final Method getParts = findMethod(multipart.getClass(), "getParts");
            getParts.setAccessible(true);
            final List<?> parts = (List<?>) getParts.invoke(multipart);
            for (Object part : parts) {
                collectMultipartPart(part, paramEntries, fileEntries);
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn(e, "### Collect multipart httpclient5 entity failed, entityClass={}, contentType={}, contentLength={}",
                    entity.getClass().getName(), contentType, entity.getContentLength());
            fileEntries.add(clipFileEntry("multipart", null, entity.getContentLength(), contentType));
            return true;
        }
    }

    private static Object unwrapMultipart(final HttpEntity entity) {
        if (!MULTIPART_ENTITY_CLASS.equals(entity.getClass().getName())) {
            return null;
        }
        try {
            // MultipartFormEntity 与 getMultipart() 在 5.0 均为包级可见, 只能反射访问。
            final Method getMultipart = entity.getClass().getDeclaredMethod("getMultipart");
            getMultipart.setAccessible(true);
            return getMultipart.invoke(entity);
        } catch (Exception e) {
            LOGGER.warn(e, "### Unwrap multipart httpclient5 entity failed, entityClass={}",
                entity.getClass().getName());
            return null;
        }
    }

    private static void collectMultipartPart(final Object part,
                                             final List<String> paramEntries,
                                             final List<String> fileEntries) throws Exception {
        final String name = part instanceof FormBodyPart ? ((FormBodyPart) part).getName() : null;
        final ContentBody contentBody = ((MultipartPart) part).getBody();
        if (contentBody == null) {
            return;
        }

        final String filename = contentBody.getFilename();
        final long contentLength = contentBody.getContentLength();
        final String mimeType = contentBody.getMimeType();

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

    private static boolean isFileBody(final ContentBody contentBody) {
        return contentBody instanceof FileBody
            || contentBody instanceof InputStreamBody
            || contentBody instanceof ByteArrayBody;
    }

    private static String readMultipartText(final ContentBody contentBody) {
        if (!(contentBody instanceof StringBody)) {
            return null;
        }
        try {
            final Reader reader = ((StringBody) contentBody).getReader();
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
            LOGGER.warn(e, "### Read multipart text body failed, contentBodyClass={}",
                contentBody.getClass().getName());
            return null;
        }
    }

    private static String safeReadEntity(final HttpEntity entity) {
        final Charset charset = resolveCharset(entity);
        final int maxBytes = maxReadBytes();
        final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        try {
            // 不能只依赖 getContent(): Spring 的 HttpComponentsClientHttpRequest$BodyEntity
            // 等包装实体会直接抛 UnsupportedOperationException, 但 writeTo 可用。
            // 调用方已保证 isRepeatable()==true, 因此多写一次不会耗尽业务流。
            entity.writeTo(new LimitedOutputStream(out, maxBytes));
        } catch (LimitReachedException e) {
            // 达到读取上限, 用已写入的前缀; 后续 clip() 继续按字符阈值裁剪。
            return out.size() == 0 ? null : new String(out.toByteArray(), charset);
        } catch (Exception e) {
            // 兼容只实现 getContent() 的自定义实体: writeTo 不可用时回到旧读取路径。
            out.reset();
            try {
                return EntityUtils.toString(entity, charset);
            } catch (Exception fallback) {
                LOGGER.warn(fallback, "### Read httpclient5 entity as text failed, entityClass={}, contentType={}, repeatable={}",
                        entity.getClass().getName(), getContentTypeValue(entity), entity.isRepeatable());
                return null;
            }
        }
        if (out.size() == 0) {
            return null;
        }
        return new String(out.toByteArray(), charset);
    }

    private static Charset resolveCharset(final HttpEntity entity) {
        final String contentType = getContentTypeValue(entity);
        if (StringUtil.isNotEmpty(contentType)) {
            try {
                final ContentType parsed = ContentType.parse(contentType);
                if (parsed.getCharset() != null) {
                    return parsed.getCharset();
                }
            } catch (Exception ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 按字符阈值换算字节上限: UTF-8 每字符最多 4 字节, 再留 4 字节余量。
     * 阈值 &lt;= 0 表示不裁剪, 此时仍受 {@link #MAX_READ_BYTES} 保护。
     */
    private static int maxReadBytes() {
        final int threshold = threshold();
        if (threshold <= 0) {
            return MAX_READ_BYTES;
        }
        return (int) Math.min((long) threshold * 4 + 4, MAX_READ_BYTES);
    }

    private static File extractFile(final HttpEntity entity) {
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
        return entity.getContentType();
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

    /**
     * 写入上限保护: 超过 {@code limit} 时抛 {@link LimitReachedException}, 由调用方保留已写前缀。
     */
    private static final class LimitedOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final int limit;
        private int written;

        private LimitedOutputStream(final OutputStream delegate, final int limit) {
            this.delegate = delegate;
            this.limit = limit;
        }

        @Override
        public void write(final int b) throws IOException {
            ensureRoom(1);
            delegate.write(b);
            written++;
        }

        @Override
        public void write(final byte[] bytes, final int off, final int len) throws IOException {
            if (len <= 0) {
                return;
            }
            ensureRoom(len);
            delegate.write(bytes, off, len);
            written += len;
        }

        private void ensureRoom(final int len) throws LimitReachedException {
            if (len > limit - written) {
                throw new LimitReachedException();
            }
        }
    }

    private static final class LimitReachedException extends IOException {
        private static final long serialVersionUID = 1L;
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
