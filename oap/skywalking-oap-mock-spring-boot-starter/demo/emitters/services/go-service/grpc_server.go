package main

import (
	"context"
	"encoding/json"
	"net"

	"github.com/sirupsen/logrus"
	"google.golang.org/grpc"

	proto "mock-oap-emitters/go-service/proto"
)

// kqService 实现 proto.KQService —— 一个泛化 Call(method + param),
// 形状对齐实际工程(gisserver 的 proto/service.proto):所有方法共用一个 rpc,
// 方法名走 method,参数走 param(bytes),返回 code + result(bytes)。
type kqService struct {
	proto.UnimplementedKQServiceServer
	serviceName string
	next        string
}

// Call 泛化入口:method=forward(或空)时按 param 里的 hops/latency 做转发。
//
// 注意:这里**没有任何 SkyWalking 代码** —— skywalking-go 的 grpc 插件会在 grpc.NewServer()
// 上自动挂服务端拦截器:从 HTTP/2 header 里取 Java 侧注入的 sw8,建 Entry span,
// 于是 java(gRPC Exit) -> go(gRPC Entry) 才连得上。
func (s *kqService) Call(ctx context.Context, req *proto.KQRequest) (*proto.KQResponse, error) {
	logger := log.WithFields(logrus.Fields{
		"service": s.serviceName,
		"rpc":     "proto.KQService/Call",
		"method":  req.GetMethod(),
	})
	logger.Infof("收到 gRPC 泛化调用: param=%s", string(req.GetParam()))

	if m := req.GetMethod(); m != "" && m != "forward" {
		logger.WithField("status", 404).Warnf("未知方法: %s", m)
		return &proto.KQResponse{Code: 404, Result: []byte(`{"error":"unknown method: ` + m + `"}`)}, nil
	}

	var p struct {
		Hops    int    `json:"hops"`
		Latency int    `json:"latency"`
		Fail    bool   `json:"fail"`
		OrderID string `json:"orderId"`
	}
	if raw := req.GetParam(); len(raw) > 0 {
		if err := json.Unmarshal(raw, &p); err != nil {
			logger.WithField("status", 400).Errorf("参数解析失败: %v", err)
			return &proto.KQResponse{Code: 400, Result: []byte(`{"error":"bad param: ` + err.Error() + `"}`)}, nil
		}
	}
	if p.Hops <= 0 {
		p.Hops = 1
	}
	if p.OrderID == "" {
		p.OrderID = newOrderID("GO-GRPC")
	}

	code, body := forward(s.serviceName, s.next, p.Hops, p.Latency, p.Fail, p.OrderID)
	logger.WithField("status", code).Infof("gRPC 调用完成: orderId=%s", p.OrderID)
	// resType=1 表示 result 是纯文本(实际工程里是业务自定义的类型编号)
	return &proto.KQResponse{Code: int32(code), Result: []byte(body), ResType: 1}, nil
}

// startGRPC 起 gRPC 口;失败直接退出(演示服务,不做优雅降级)。
func startGRPC(addr string, srv *kqService) {
	lis, err := net.Listen("tcp", addr)
	if err != nil {
		log.Fatalf("grpc listen %s failed: %v", addr, err)
	}
	g := grpc.NewServer()
	proto.RegisterKQServiceServer(g, srv)
	log.WithFields(logrus.Fields{
		"service": srv.serviceName,
		"addr":    addr,
	}).Info("gRPC 服务启动 (proto.KQService/Call)")
	go func() {
		if err := g.Serve(lis); err != nil {
			log.Fatalf("grpc serve failed: %v", err)
		}
	}()
}
