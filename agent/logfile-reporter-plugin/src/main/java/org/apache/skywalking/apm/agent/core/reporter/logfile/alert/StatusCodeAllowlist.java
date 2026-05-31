package org.apache.skywalking.apm.agent.core.reporter.logfile.alert;

/**
 * HTTP 状态码白名单，用固定数组避免热路径装箱。
 */
final class StatusCodeAllowlist {

    private final boolean[] allowed = new boolean[600];

    StatusCodeAllowlist(final Iterable<Integer> statusCodes) {
        if (statusCodes == null) {
            return;
        }
        for (Integer code : statusCodes) {
            if (code != null && code.intValue() >= 100 && code.intValue() <= 599) {
                allowed[code.intValue()] = true;
            }
        }
    }

    boolean allows(final int statusCode) {
        return statusCode >= 100 && statusCode <= 599 && allowed[statusCode];
    }
}
