package org.apache.skywalking.apm.plugin.hutool.v5.http;

import cn.hutool.core.io.resource.Resource;
import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpRequest;
import org.apache.skywalking.apm.agent.core.logging.api.ILog;
import org.apache.skywalking.apm.agent.core.logging.api.LogManager;
import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.util.StringUtil;

import java.io.File;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// 支持 hutool 5.4.x 和 5.8.x
final class HutoolHttpParamCollector {
    static final String TAG_KEY_HTTP_PARAMS = "http.request.params";
    static final String TAG_KEY_HTTP_FILES = "http.request.files";

    private static final ILog LOGGER = LogManager.getLogger(HutoolHttpParamCollector.class);

    private HutoolHttpParamCollector() {
    }

    static CollectedTags collect(final HttpRequest request) {
        final List<String> paramEntries = new ArrayList<String>();
        final List<String> fileEntries = new ArrayList<String>();
        final boolean officialCollectEnabled = HutoolHttpCollectionSwitch.isOfficialCollectEnabled();
        final boolean overrideCollectEnabled = HutoolHttpCollectionSwitch.isOverrideCollectEnabled();

        if (officialCollectEnabled) {
            collectQueryString(request, paramEntries);
        }
        if (overrideCollectEnabled) {
            collectBody(request, paramEntries, fileEntries);
        }

        if (LOGGER.isDebugEnable()) {
            LOGGER.debug(
                "### Hutool http param collection finished, requestType={}, officialEnabled={}, overrideEnabled={}, paramEntryCount={}, fileEntryCount={}",
                request == null ? null : request.getClass().getName(), officialCollectEnabled, overrideCollectEnabled,
                paramEntries.size(), fileEntries.size());
        }
        return new CollectedTags(joinEntries(paramEntries), joinEntries(fileEntries));
    }

    private static void collectQueryString(final HttpRequest request, final List<String> paramEntries) {
        final URI uri = URLUtil.toURI(request.getUrl());
        if (uri == null || StringUtil.isEmpty(uri.getQuery())) {
            return;
        }
        paramEntries.add(clip("query=" + uri.getQuery()));
    }

    private static void collectBody(final HttpRequest request,
                                    final List<String> paramEntries,
                                    final List<String> fileEntries) {
        final Map<String, Object> form = request.form();
        if (form != null && !form.isEmpty()) {
            final Map<String, ? extends Object> fileForm = request.fileForm();
            if (fileForm == null || fileForm.isEmpty()) {
                collectFormUrlEncoded(form, paramEntries);
                return;
            }
            collectMultipart(form, fileForm, paramEntries, fileEntries);
            return;
        }

        final BodySnapshot bodySnapshot = extractBodySnapshot(request);
        if (bodySnapshot == null || bodySnapshot.bytes == null || bodySnapshot.bytes.length == 0) {
            return;
        }

        final String contentType = request.header("Content-Type");
        if (shouldTreatAsText(contentType)) {
            final String text = new String(bodySnapshot.bytes, resolveCharset(request));
            if (StringUtil.isNotEmpty(text)) {
                paramEntries.add(clip("body=" + text));
                return;
            }
        }

        fileEntries.add(clipFileEntry("body", bodySnapshot.name, bodySnapshot.bytes.length, contentType));
    }

    private static void collectFormUrlEncoded(final Map<String, Object> form, final List<String> paramEntries) {
        final StringBuilder builder = new StringBuilder("form=");
        boolean hasContent = false;
        for (Map.Entry<String, Object> entry : form.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (hasContent) {
                builder.append('&');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            hasContent = true;
        }
        if (hasContent) {
            paramEntries.add(clip(builder.toString()));
        }
    }

    private static void collectMultipart(final Map<String, Object> form,
                                         final Map<String, ? extends Object> fileForm,
                                         final List<String> paramEntries,
                                         final List<String> fileEntries) {
        for (Map.Entry<String, Object> entry : form.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            if (fileForm != null && fileForm.containsKey(entry.getKey())) {
                collectFileValue(entry.getKey(), entry.getValue(), fileEntries);
                continue;
            }
            paramEntries.add(clip(entry.getKey() + "=" + entry.getValue()));
        }
    }

    private static void collectFileValue(final String fieldName,
                                         final Object value,
                                         final List<String> fileEntries) {
        if (value instanceof Resource) {
            final Resource resource = (Resource) value;
            fileEntries.add(clipFileEntry(fieldName, resource.getName(), sizeOf(resource),
                guessContentType(resource.getName())));
            return;
        }
        if (value instanceof File) {
            final File file = (File) value;
            fileEntries.add(clipFileEntry(fieldName, file.getName(), file.length(), guessContentType(file.getName())));
            return;
        }
        if (value instanceof File[]) {
            final File[] files = (File[]) value;
            for (File file : files) {
                if (file != null) {
                    fileEntries.add(clipFileEntry(fieldName, file.getName(), file.length(),
                        guessContentType(file.getName())));
                }
            }
            return;
        }
        if (value instanceof byte[]) {
            fileEntries.add(clipFileEntry(fieldName, null, ((byte[]) value).length, null));
            return;
        }
        fileEntries.add(clipFileEntry(fieldName, null, -1, null));
    }

    private static BodySnapshot extractBodySnapshot(final HttpRequest request) {
        try {
            boolean knownBodyFieldFound = false;

        	// hutool 5.4.x
            final Field bodyBytesField = findField(request.getClass(), "bodyBytes");
            if (bodyBytesField != null) {
                knownBodyFieldFound = true;
                bodyBytesField.setAccessible(true);
                final Object bodyBytes = bodyBytesField.get(request);
                if (bodyBytes instanceof byte[]) {
                    return new BodySnapshot((byte[]) bodyBytes, null);
                }
            }
            // hutool 5.8.x
            final Field bodyField = findField(request.getClass(), "body");
            if (bodyField != null) {
                knownBodyFieldFound = true;
                bodyField.setAccessible(true);
                final Object body = bodyField.get(request);
                if (body instanceof Resource) {
                    final Resource resource = (Resource) body;
                    return new BodySnapshot(resource.readBytes(), resource.getName());
                }
                if (body == null) {
                    return null;
                }
            }

            if (!knownBodyFieldFound || mayHaveBody(request)) {
                LOGGER.warn("### Extract hutool http body skipped, unsupported hutool request structure, requestType={}, method={}, url={}, hutoolVersion={}, requestFields={}",
                    request.getClass().getName(), request.getMethod(), request.getUrl(), resolveHutoolVersion(),
                    describeFields(request));
            }
        } catch (Exception e) {
            LOGGER.warn(e, "### Extract hutool http body failed, requestType=" + request.getClass().getName());
        }
        return null;
    }

    private static Field findField(final Class<?> type, final String fieldName) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String describeFields(final Object target) {
        if (target == null) {
            return "null";
        }
        final StringBuilder builder = new StringBuilder();
        Class<?> current = target.getClass();
        while (current != null) {
            final Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (builder.length() > 0) {
                    builder.append(", ");
                }
                builder.append(current.getSimpleName()).append('.').append(field.getName()).append('=');
                try {
                    field.setAccessible(true);
                    builder.append(describeValue(field.get(target)));
                } catch (Exception e) {
                    builder.append("<inaccessible:");
                    builder.append(e.getClass().getSimpleName()).append('>');
                }
            }
            current = current.getSuperclass();
        }
        return clip(builder.toString());
    }

    private static String describeValue(final Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof byte[]) {
            return "byte[" + ((byte[]) value).length + "]";
        }
        if (value instanceof char[]) {
            return "char[" + ((char[]) value).length + "]";
        }
        if (value.getClass().isArray()) {
            return value.getClass().getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
        }
        if (value instanceof Resource) {
            final Resource resource = (Resource) value;
            return "Resource{name=" + resource.getName() + ",url=" + resource.getUrl() + "}";
        }
        final String text = String.valueOf(value);
        return text.length() > 256 ? text.substring(0, 256) + "..." : text;
    }

    private static String resolveHutoolVersion() {
        final Package pkg = HttpRequest.class.getPackage();
        if (pkg == null) {
            return "";
        }
        if (StringUtil.isNotEmpty(pkg.getImplementationVersion())) {
            return pkg.getImplementationVersion();
        }
        if (StringUtil.isNotEmpty(pkg.getSpecificationVersion())) {
            return pkg.getSpecificationVersion();
        }
        return "";
    }

    private static boolean mayHaveBody(final HttpRequest request) {
        if (request == null || request.getMethod() == null) {
            return false;
        }
        final String methodName = request.getMethod().name();
        return "POST".equals(methodName)
            || "PUT".equals(methodName)
            || "PATCH".equals(methodName)
            || "DELETE".equals(methodName);
    }

    private static Charset resolveCharset(final HttpRequest request) {
        final String charset = request.charset();
        if (StrUtil.isBlank(charset)) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charset);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private static boolean shouldTreatAsText(final String contentType) {
        if (StringUtil.isEmpty(contentType)) {
            return true;
        }
        final String lowerCase = contentType.toLowerCase();
        return lowerCase.contains("text/")
            || lowerCase.contains("application/json")
            || lowerCase.contains("application/xml")
            || lowerCase.contains("application/x-www-form-urlencoded")
            || lowerCase.contains("application/javascript")
            || lowerCase.contains("application/graphql");
    }

    private static long sizeOf(final Resource resource) {
        try {
            final URL url = resource.getUrl();
            if (url != null && "file".equalsIgnoreCase(url.getProtocol())) {
                return new File(url.toURI()).length();
            }
        } catch (Exception ignored) {
        }
        try {
            return resource.readBytes().length;
        } catch (Exception e) {
            LOGGER.warn(e, "### Read hutool resource bytes failed, resourceName={}" + resource.getName());
            return -1;
        }
    }

    private static String guessContentType(final String filename) {
        return filename == null ? null : URLConnection.guessContentTypeFromName(filename);
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
        final int threshold = HttpClientPluginConfig.Plugin.Http.HTTP_PARAMS_LENGTH_THRESHOLD;
        if (threshold > 0 && value.length() > threshold) {
            return StringUtil.cut(value, threshold);
        }
        return value;
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

    private static final class BodySnapshot {
        private final byte[] bytes;
        private final String name;

        private BodySnapshot(final byte[] bytes, final String name) {
            this.bytes = bytes;
            this.name = name;
        }
    }
}
