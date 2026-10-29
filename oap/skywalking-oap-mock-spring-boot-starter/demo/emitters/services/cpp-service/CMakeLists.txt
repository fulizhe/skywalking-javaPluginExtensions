cmake_minimum_required(VERSION 3.20)
project(cpp_trace_service LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
# Use source archives instead of git clones: the release archives omit optional
# upstream test/benchmark submodules, which this demo does not build.
FetchContent_Declare(httplib
    URL https://codeload.github.com/yhirose/cpp-httplib/legacy.tar.gz/refs/tags/v0.18.3
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_Declare(opentelemetry
    URL https://codeload.github.com/open-telemetry/opentelemetry-cpp/legacy.tar.gz/refs/tags/v1.22.0
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
FetchContent_Declare(opentelemetry-proto
    URL https://codeload.github.com/open-telemetry/opentelemetry-proto/legacy.tar.gz/refs/tags/v1.7.0
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
set(WITH_OTLP_GRPC ON CACHE BOOL "" FORCE)
set(WITH_OTLP_HTTP OFF CACHE BOOL "" FORCE)
set(WITH_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(WITH_FUNC_TESTS OFF CACHE BOOL "" FORCE)
set(WITH_BENCHMARK OFF CACHE BOOL "" FORCE)
FetchContent_GetProperties(opentelemetry-proto)
if(NOT opentelemetry-proto_POPULATED)
    FetchContent_Populate(opentelemetry-proto)
endif()
set(OTELCPP_PROTO_PATH "${opentelemetry-proto_SOURCE_DIR}" CACHE PATH "Use pinned OTLP protobuf source" FORCE)
FetchContent_MakeAvailable(httplib opentelemetry)

add_executable(cpp-service main.cpp)
target_link_libraries(cpp-service PRIVATE
    opentelemetry_exporter_otlp_grpc
    opentelemetry_trace
    opentelemetry_common
    httplib::httplib)
