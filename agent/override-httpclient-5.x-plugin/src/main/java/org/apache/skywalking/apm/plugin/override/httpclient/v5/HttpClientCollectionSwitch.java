package org.apache.skywalking.apm.plugin.override.httpclient.v5;

import org.apache.skywalking.apm.plugin.httpclient.HttpClientPluginConfig;
import org.apache.skywalking.apm.plugin.override.httpclient.v4.OverrideHttpClientPluginConfig;

/**
 * 5.x 采集开关的只读适配层。
 *
 * <p>开关与运行时入口统一由 override-httpclient-4.x-plugin 拥有(同一个
 * {@code SWHttpClientCollectUtils} 宿主工具类), 本模块只读共享的
 * {@link OverrideHttpClientPluginConfig}, 不再注册自己的运行时增强 ——
 * 否则两个插件会争抢同一个被增强类, 外层的 defineReturnValue 会压住内层。
 */
final class HttpClientCollectionSwitch {
    private HttpClientCollectionSwitch() {
    }

    static boolean isOfficialCollectEnabled() {
        return HttpClientPluginConfig.Plugin.HttpClient.COLLECT_HTTP_PARAMS;
    }

    static boolean isOverrideCollectEnabled() {
        return OverrideHttpClientPluginConfig.Plugin.OverrideHttpClient.COLLECT_HTTP_PARAMS;
    }
}
