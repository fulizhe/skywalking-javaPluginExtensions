#include <algorithm>
#include <array>
#include <chrono>
#include <cctype>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <memory>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "httplib.h"
#include "opentelemetry/exporters/otlp/otlp_grpc_exporter.h"
#include "opentelemetry/sdk/resource/resource.h"
#include "opentelemetry/sdk/trace/batch_span_processor.h"
#include "opentelemetry/sdk/trace/tracer_provider.h"
#include "opentelemetry/context/propagation/text_map_propagator.h"
#include "opentelemetry/trace/propagation/http_trace_context.h"
#include "opentelemetry/trace/provider.h"
#include "opentelemetry/trace/span_context.h"
#include "opentelemetry/trace/trace_flags.h"

namespace trace = opentelemetry::trace;
namespace sdktrace = opentelemetry::sdk::trace;

class Headers : public opentelemetry::context::propagation::TextMapCarrier {
 public:
  explicit Headers(const httplib::Request *request = nullptr) : request_(request) {}
  explicit Headers(httplib::Headers *headers) : headers_(headers) {}
  opentelemetry::nostd::string_view Get(opentelemetry::nostd::string_view key) const noexcept override {
    if (headers_) {
      auto it = headers_->find(std::string(key));
      return it == headers_->end() ? opentelemetry::nostd::string_view{} : it->second;
    }
    if (!request_) return {};
    value_ = request_->get_header_value(std::string(key));
    return value_;
  }
  void Set(opentelemetry::nostd::string_view key, opentelemetry::nostd::string_view value) noexcept override {
    if (headers_) headers_->emplace(std::string(key), std::string(value));
  }
 private:
  const httplib::Request *request_;
  httplib::Headers *headers_{nullptr};
  mutable std::string value_;
};

struct Sw8Context {
  bool valid{false};
  std::string trace_id;
  std::string parent_segment_id;
  int parent_span_id{0};
};

static bool is_hex(const std::string &value);

// SkyWalking trace IDs are opaque strings (commonly longer than 32 hex chars).
// OTel needs 16 bytes, so derive a stable local representation while keeping the
// original string as the cross-protocol grouping key.
static std::array<uint8_t, 16> trace_bytes(const std::string &trace_id) {
  std::array<uint8_t, 16> bytes{};
  if (is_hex(trace_id)) {
    for (int i = 0; i < 16; i++) {
      bytes[i] = static_cast<uint8_t>(std::stoi(trace_id.substr(i * 2, 2), nullptr, 16));
    }
    return bytes;
  }
  uint64_t h1 = 1469598103934665603ULL;
  uint64_t h2 = 1099511628211ULL;
  for (unsigned char c : trace_id) {
    h1 = (h1 ^ c) * 1099511628211ULL;
    h2 = (h2 ^ c) * 1099511628211ULL + 0x9e3779b97f4a7c15ULL;
  }
  for (int i = 0; i < 8; i++) {
    bytes[i] = static_cast<uint8_t>((h1 >> (i * 8)) & 0xff);
    bytes[8 + i] = static_cast<uint8_t>((h2 >> (i * 8)) & 0xff);
  }
  if (!bytes[0] && !bytes[1] && !bytes[2] && !bytes[3]) bytes[0] = 1;
  return bytes;
}

static std::string env(const char *name, const char *fallback) {
  const char *value = std::getenv(name);
  return value && *value ? value : fallback;
}
static int number(const httplib::Request &req, const char *key, int fallback, int low, int high) {
  try { return std::max(low, std::min(high, std::stoi(req.get_param_value(key)))); }
  catch (...) { return fallback; }
}
static bool flag(const httplib::Request &req) {
  return req.has_param("fail") && req.get_param_value("fail") == "true";
}
static void json(httplib::Response &res, int status, const std::string &body) {
  res.status = status; res.set_content(body, "application/json");
}
static void plain(httplib::Response &res, int status, const std::string &body) {
  res.status = status; res.set_content(body, "text/plain; charset=utf-8");
}

static std::string hex(const uint8_t *bytes, size_t size) {
  static const char *digits = "0123456789abcdef";
  std::string out;
  out.reserve(size * 2);
  for (size_t i = 0; i < size; i++) {
    out.push_back(digits[(bytes[i] >> 4) & 0xf]);
    out.push_back(digits[bytes[i] & 0xf]);
  }
  return out;
}
static std::string hex(const std::vector<uint8_t> &bytes) { return hex(bytes.data(), bytes.size()); }

static std::string b64encode(const std::string &input) {
  static const char *alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string out;
  size_t i = 0;
  while (i + 2 < input.size()) {
    uint32_t v = (static_cast<uint8_t>(input[i]) << 16) | (static_cast<uint8_t>(input[i + 1]) << 8) |
                 static_cast<uint8_t>(input[i + 2]);
    out.push_back(alphabet[(v >> 18) & 63]);
    out.push_back(alphabet[(v >> 12) & 63]);
    out.push_back(alphabet[(v >> 6) & 63]);
    out.push_back(alphabet[v & 63]);
    i += 3;
  }
  if (i < input.size()) {
    uint32_t v = static_cast<uint8_t>(input[i]) << 16;
    bool two = i + 1 < input.size();
    if (two) v |= static_cast<uint8_t>(input[i + 1]) << 8;
    out.push_back(alphabet[(v >> 18) & 63]);
    out.push_back(alphabet[(v >> 12) & 63]);
    out.push_back(two ? alphabet[(v >> 6) & 63] : '=');
    out.push_back('=');
  }
  return out;
}

static std::string b64decode(const std::string &input) {
  static const std::string alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
  std::string out;
  int value = 0;
  int bits = -8;
  for (unsigned char c : input) {
    if (c == '=') break;
    size_t pos = alphabet.find(static_cast<char>(c));
    if (pos == std::string::npos) continue;
    value = (value << 6) | static_cast<int>(pos);
    bits += 6;
    if (bits >= 0) {
      out.push_back(static_cast<char>((value >> bits) & 0xff));
      bits -= 8;
    }
  }
  return out;
}

static std::vector<std::string> split(const std::string &value, char separator) {
  std::vector<std::string> parts;
  std::string current;
  std::istringstream stream(value);
  while (std::getline(stream, current, separator)) parts.push_back(current);
  return parts;
}

static bool is_hex(const std::string &value) {
  if (value.size() != 32) return false;
  for (char c : value) {
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
  }
  return true;
}

static Sw8Context parse_sw8(const std::string &header) {
  Sw8Context result;
  auto fields = split(header, '-');
  if (fields.size() != 8) return result;
  std::string trace_id = b64decode(fields[1]);
  if (trace_id.empty()) return result;
  result.valid = true;
  result.trace_id = trace_id;
  result.parent_segment_id = b64decode(fields[2]);
  try { result.parent_span_id = std::stoi(fields[3]); } catch (...) { result.parent_span_id = 0; }
  return result;
}

static std::string build_sw8(const std::string &trace_id, const std::string &segment_id, int span_id,
                             const std::string &service, const std::string &instance,
                             const std::string &endpoint, const std::string &peer) {
  return "1-" + b64encode(trace_id) + "-" + b64encode(segment_id) + "-" + std::to_string(span_id) + "-" +
         b64encode(service) + "-" + b64encode(instance) + "-" + b64encode(endpoint) + "-" + b64encode(peer);
}

static std::string trace_id_hex(const trace::Span &span) {
  std::array<char, trace::TraceId::kSize * 2> buffer{};
  span.GetContext().trace_id().ToLowerBase16(
      opentelemetry::nostd::span<char, trace::TraceId::kSize * 2>{buffer.data(), buffer.size()});
  return {buffer.data(), buffer.size()};
}

static std::string span_id_hex(const trace::Span &span) {
  std::array<char, trace::SpanId::kSize * 2> buffer{};
  span.GetContext().span_id().ToLowerBase16(
      opentelemetry::nostd::span<char, trace::SpanId::kSize * 2>{buffer.data(), buffer.size()});
  return {buffer.data(), buffer.size()};
}

static int synthetic_span_id(const trace::Span &span) {
  auto id = span.GetContext().span_id().Id();
  uint64_t value = 0;
  for (uint8_t b : id) value = (value << 8) | b;
  int result = static_cast<int>(value & 0x7fffffffULL);
  return result == 0 ? 1 : result;
}

static std::string json_escape(const std::string &value) {
  std::string out;
  for (char c : value) {
    switch (c) {
      case '"': out += "\\\""; break;
      case '\\': out += "\\\\"; break;
      case '\n': out += "\\n"; break;
      case '\r': out += "\\r"; break;
      default: out.push_back(c); break;
    }
  }
  return out;
}

static trace::SpanContext sw8_parent(const Sw8Context &sw8) {
  std::array<uint8_t, 16> trace_id = trace_bytes(sw8.trace_id);
  uint8_t span_bytes[8]{};
  uint32_t parent = static_cast<uint32_t>(sw8.parent_span_id);
  span_bytes[4] = static_cast<uint8_t>((parent >> 24) & 0xff);
  span_bytes[5] = static_cast<uint8_t>((parent >> 16) & 0xff);
  span_bytes[6] = static_cast<uint8_t>((parent >> 8) & 0xff);
  span_bytes[7] = static_cast<uint8_t>(parent & 0xff);
  return trace::SpanContext(
      trace::TraceId(opentelemetry::nostd::span<const uint8_t, 16>(trace_id.data(), 16)),
      trace::SpanId(opentelemetry::nostd::span<const uint8_t, 8>(span_bytes, 8)),
      trace::TraceFlags(1), true);
}

int main() {
  const std::string service = env("OTEL_SERVICE_NAME", "cpp-service");
  std::string instance = env("SERVICE_INSTANCE", "");
  if (instance.empty()) instance = service + "-1";
  const std::string next = env("NEXT_SERVICE_URL", "");
  const int port = std::stoi(env("SERVER_PORT", "8083"));

  opentelemetry::exporter::otlp::OtlpGrpcExporterOptions options;
  options.endpoint = env("OTEL_EXPORTER_OTLP_ENDPOINT", "mock-oap:11800");
  options.use_ssl_credentials = false;
  auto exporter = std::make_unique<opentelemetry::exporter::otlp::OtlpGrpcExporter>(options);
  sdktrace::BatchSpanProcessorOptions processor_options;
  auto processor = std::make_unique<sdktrace::BatchSpanProcessor>(std::move(exporter), processor_options);
  auto resource = opentelemetry::sdk::resource::Resource::Create({
      {"service.name", service}, {"service.language", "cpp"},
      {"service.version", "1.0.0"}, {"service.instance.id", instance}});
  opentelemetry::nostd::shared_ptr<sdktrace::TracerProvider> sdk_provider(
      new sdktrace::TracerProvider(std::move(processor), resource));
  auto sdk = sdk_provider.get();
  opentelemetry::nostd::shared_ptr<trace::TracerProvider> provider(std::move(sdk_provider));
  trace::Provider::SetTracerProvider(provider);
  auto tracer = trace::Provider::GetTracerProvider()->GetTracer("cpp-trace-service", "1.0.0");
  opentelemetry::trace::propagation::HttpTraceContext propagator;

  httplib::Server server;
  server.Get("/healthz", [&](const httplib::Request &, httplib::Response &res) {
    json(res, 200, "{\"status\":\"ok\",\"service\":\"" + service + "\"}");
  });
  server.Get("/", [&](const httplib::Request &, httplib::Response &res) {
    json(res, 200, "{\"service\":\"" + service + "\",\"language\":\"cpp\"}");
  });
  server.Get("/api/work", [&](const httplib::Request &req, httplib::Response &res) {
    Headers carrier(&req); opentelemetry::context::Context parent_context;
    auto parent = propagator.Extract(carrier, parent_context);
    trace::StartSpanOptions server_options; server_options.kind = trace::SpanKind::kServer; server_options.parent = parent;
    auto span = tracer->StartSpan("GET /api/work", server_options);
    auto scope = tracer->WithActiveSpan(span);
    trace::StartSpanOptions db_options; db_options.kind = trace::SpanKind::kClient;
    auto db = tracer->StartSpan("SELECT demo_items", db_options);
    std::this_thread::sleep_for(std::chrono::milliseconds(20)); db->End();
    std::this_thread::sleep_for(std::chrono::milliseconds(number(req, "latency", 100, 0, 3000)));
    int status = flag(req) ? 500 : 200;
    if (status >= 500) span->SetStatus(trace::StatusCode::kError, "simulated failure in " + service + " /api/work");
    span->SetAttribute("http.response.status_code", status); auto id = trace_id_hex(*span); span->End();
    json(res, status, "{\"service\":\"" + service + "\",\"trace_id\":\"" + id + "\",\"status\":" + std::to_string(status) + "}");
  });
  server.Get("/api/forward", [&](const httplib::Request &req, httplib::Response &res) {
    Headers carrier(&req); opentelemetry::context::Context parent_context;
    Sw8Context sw8 = parse_sw8(req.get_header_value("sw8"));
    auto parent = propagator.Extract(carrier, parent_context);
    trace::StartSpanOptions server_options;
    server_options.kind = trace::SpanKind::kServer;
    if (sw8.valid) {
      server_options.parent = sw8_parent(sw8);
    } else {
      server_options.parent = parent;
    }
    auto span = tracer->StartSpan("GET /api/forward", server_options);
    auto scope = tracer->WithActiveSpan(span);

    std::string trace_id = trace_id_hex(*span);
    std::string sw8_trace_id = sw8.valid ? sw8.trace_id : trace_id;
    std::string span_hex = span_id_hex(*span);
    int span_id = synthetic_span_id(*span);
    std::string segment_id = "cpp-" + trace_id + "-" + span_hex;
    span->SetAttribute("sw8.trace.id", sw8_trace_id);
    span->SetAttribute("sw8.segment.id", segment_id);
    span->SetAttribute("sw8.span.id", std::to_string(span_id));
    if (sw8.valid) {
      span->SetAttribute("sw8.parent.segment.id", sw8.parent_segment_id);
      span->SetAttribute("sw8.parent.span.id", std::to_string(sw8.parent_span_id));
    }

    int hops = number(req, "hops", 1, 1, 4);
    int latency = number(req, "latency", 0, 0, 3000);
    int status = 200;
    std::string downstream;
    if (latency > 0) std::this_thread::sleep_for(std::chrono::milliseconds(latency));

    if (hops > 1 && !next.empty()) {
      trace::StartSpanOptions client_options;
      client_options.kind = trace::SpanKind::kClient;
      auto client = tracer->StartSpan("GET node-service:8081/api/forward", client_options);
      auto client_scope = tracer->WithActiveSpan(client);
      httplib::Headers request_headers;
      request_headers.emplace("sw8", build_sw8(sw8_trace_id, segment_id, span_id, service, instance,
                                                "GET /api/forward", "node-service:8081"));
      Headers out(&request_headers);
      opentelemetry::context::Context client_context;
      client_context = trace::SetSpan(client_context, client);
      propagator.Inject(out, client_context);

      httplib::Client http(next);
      http.set_connection_timeout(5);
      auto result = http.Get(("/api/forward?hops=" + std::to_string(hops - 1) + "&latency=" +
                              std::to_string(latency) + "&fail=" + (flag(req) ? "true" : "false")).c_str(),
                             request_headers);
      status = result ? result->status : 502;
      if (result) downstream = result->body;
      client->SetAttribute("http.response.status_code", status);
      if (status >= 500) {
        client->SetStatus(trace::StatusCode::kError);
        span->SetStatus(trace::StatusCode::kError, "downstream returned " + std::to_string(status));
      }
      client->End();
    } else if (flag(req)) {
      status = 500;
      span->SetStatus(trace::StatusCode::kError, "simulated failure in " + service);
    }

    span->SetAttribute("http.response.status_code", status);
    span->End();
    std::string body = service;
    if (!downstream.empty()) body += " -> " + downstream;
    plain(res, status, body);
  });

  std::cout << "[" << service << "] listening on :" << port << ", next hop = "
            << (next.empty() ? "<none>" : next) << std::endl;
  server.listen("0.0.0.0", port);
  sdk->Shutdown();
}
