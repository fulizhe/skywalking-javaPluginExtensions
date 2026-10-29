# ---------------------------------------------------------------------------
# 阶段 1:SkyWalking Go agent 官方基础镜像 —— 注入 agent 并编译
#   镜像内置 skywalking-go-agent(/usr/local/bin),用 toolexec 方式做混合编译。
# ---------------------------------------------------------------------------
ARG SKYWALKING_GO_IMAGE=apache/skywalking-go:0.5.0-go1.23
ARG GOPROXY=https://goproxy.cn,direct

FROM ${SKYWALKING_GO_IMAGE} AS build
# 全局 ARG 在 FROM 之外不可用,阶段内需重新声明
ARG GOPROXY
ENV GOPROXY=${GOPROXY}
# skywalking-go 基础镜像基于 glibc(Debian 系);关闭 CGO 产出静态二进制,
# 才能跑在下方 alpine(musl)运行阶段 —— 否则 exec 报 "no such file or directory"。
ENV CGO_ENABLED=0
WORKDIR /src
# go.mod/go.sum 锁死 grpc v1.56.2 / protobuf v1.31.0 —— 与 skywalking-go 0.5.0
# 的 grpc 插件(其 go.mod 依赖 grpc v1.56.2)对齐,避免插件补丁打不上。
COPY go.mod go.sum ./
# Go 侧桩由 sb-skywalking/src/main/proto/kqservice.proto 生成后提交,镜像里不再需要 protoc
COPY proto ./proto
COPY main.go grpc_server.go ./
RUN skywalking-go-agent -inject /src \
 && go build -toolexec="skywalking-go-agent" -a -o /out/go-service .

# ---------------------------------------------------------------------------
# 阶段 2:精简运行(agent 已编译进二进制)
# ---------------------------------------------------------------------------
FROM alpine:3.20
RUN apk add --no-cache ca-certificates && adduser -D -u 10001 app
COPY --from=build /out/go-service /go-service
USER 10001
# 8080 = HTTP 入口,9090 = gRPC 入口(proto.KQService/Call)
EXPOSE 8080 9090
ENTRYPOINT ["/go-service"]
