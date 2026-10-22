package com.fulizhe.observability.mockoap;

import java.util.Map;

/**
 * 进程内 gRPC server 的最小生命周期接口。
 *
 * <p>刻意不引用任何 shaded gRPC 类型 —— {@link MockOapGrpcServer} 只依赖本接口,
 * 这样在「未挂 agent、shaded 类不可见」时也不会触发类解析失败(见 ADR-0001)。
 */
public interface MockOapEmbeddedServer {

	int getPort();

	boolean isRunning();

	Map<String, Object> ingestStats();

	void stop();
}
