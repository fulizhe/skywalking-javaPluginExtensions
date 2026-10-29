// go-service:两栖出口 —— HTTP(net/http)与 gRPC(proto.KQService/Call),
// 两者共用同一段 forward 逻辑,都可以继续往下游 node-service 转发。
//
// 埋点全部由 skywalking-go agent 完成(HTTP 插件 + grpc 插件),业务代码里没有任何
// SkyWalking API:gRPC 入口能取到 Java 侧注入的 sw8,从而把 java -> go 串成一条 trace。
//
// 日志用 logrus 而不是标准库 log:skywalking-go 的日志插件会自动接管 logrus(zap 同理),
// 把业务日志连同当前 trace 上下文一起上报到 OAP(本 demo 是进程内 mock-OAP)。
// 标准库 log 只会写容器 stderr,不会出现在 /mock-oap-logs.html 里。
package main

import (
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"time"

	"github.com/sirupsen/logrus"
)

// skywalking-go 的日志插件通过 logrus.New / SetFormatter 等调用识别当前 logger。
var log = logrus.New()

func init() {
	log.SetFormatter(&logrus.TextFormatter{FullTimestamp: true})
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func atoi(s string, def int) int {
	if s == "" {
		return def
	}
	n, err := strconv.Atoi(s)
	if err != nil {
		return def
	}
	return n
}

func newOrderID(prefix string) string {
	return fmt.Sprintf("%s-%d", prefix, time.Now().UnixNano())
}

// forward 是所有入口共用的转发逻辑:睡 latency,必要时再往下游多跳一跳。
// HTTP 出口由 skywalking-go 的 http 插件埋点并注入 sw8。
func forward(serviceName, next string, hops, latency int, fail bool, orderID string) (int, string) {
	begin := time.Now()
	logger := log.WithFields(logrus.Fields{
		"service": serviceName,
		"orderId": orderID,
		"hops":    hops,
	})
	logger.Infof("收到转发请求: orderId=%s, latency=%dms, fail=%t, next=%s", orderID, latency, fail, next)

	if latency > 0 {
		time.Sleep(time.Duration(latency) * time.Millisecond)
	}

	downstream := ""
	if hops > 1 && next != "" {
		// orderId 继续透传,下游服务会把同一条订单的日志串起来
		target := fmt.Sprintf("%s/api/forward?hops=%d&latency=%d&fail=%t&orderId=%s",
			next, hops-1, latency, fail, url.QueryEscape(orderID))
		req, reqErr := http.NewRequest(http.MethodGet, target, nil)
		var resp *http.Response
		var err error
		if reqErr != nil {
			err = reqErr
		} else {
			// The HTTP client plugin creates the Exit span and injects sw8 inside
			// Transport.RoundTrip; the same request object carries the propagated header.
			resp, err = http.DefaultClient.Do(req)
		}
		if err != nil {
			logger.WithFields(logrus.Fields{
				"downstream": next,
				"status":     http.StatusBadGateway,
			}).Errorf("调用下游失败: %v", err)
			downstream = "error"
			fail = true
		} else {
			b, _ := io.ReadAll(resp.Body)
			resp.Body.Close()
			downstream = string(b)
		}
	}

	out := serviceName
	if downstream != "" {
		out += " -> " + downstream
	}
	code := http.StatusOK
	if fail {
		code = http.StatusInternalServerError
	}
	logger.WithFields(logrus.Fields{
		"status":     code,
		"costMs":     time.Since(begin).Milliseconds(),
		"downstream": downstream,
	}).Infof("转发完成: orderId=%s", orderID)
	return code, out
}

func main() {
	serviceName := env("SW_AGENT_NAME", "go-service")
	port := env("SERVER_PORT", "8080")
	grpcPort := os.Getenv("GRPC_PORT") // 未设置则不启 gRPC 口
	next := os.Getenv("NEXT_SERVICE_URL")

	if grpcPort != "" {
		startGRPC(":"+grpcPort, &kqService{serviceName: serviceName, next: next})
	}

	mux := http.NewServeMux()

	// 健康检查(也会被 agent 采集,量小可忽略)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		fmt.Fprint(w, "ok")
	})

	// 继续调用下一跳,把链路串起来
	mux.HandleFunc("/api/forward", func(w http.ResponseWriter, r *http.Request) {
		hops := atoi(r.URL.Query().Get("hops"), 1)
		latency := atoi(r.URL.Query().Get("latency"), 0)
		fail := r.URL.Query().Get("fail") == "true"
		orderID := r.URL.Query().Get("orderId")
		if orderID == "" {
			orderID = newOrderID("GO")
		}

		code, body := forward(serviceName, next, hops, latency, fail, orderID)
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.WriteHeader(code)
		fmt.Fprint(w, body)
	})

	log.WithFields(logrus.Fields{
		"service":  serviceName,
		"port":     port,
		"grpcPort": grpcPort,
		"next":     next,
	}).Info("go-service 启动")
	if err := http.ListenAndServe(":"+port, mux); err != nil {
		log.Fatal(err)
	}
}
