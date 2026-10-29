# syntax=docker/dockerfile:1

FROM debian:bookworm AS build
RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' \
    /etc/apt/sources.list.d/debian.sources \
    && printf 'Acquire::Retries "5";\nAcquire::http::Timeout "60";\nAcquire::https::Timeout "60";\n' \
    > /etc/apt/apt.conf.d/80-retries \
    && apt-get update \
    && apt-get install -y --no-install-recommends \
    ca-certificates git cmake g++ make libgrpc++-dev libprotobuf-dev protobuf-compiler protobuf-compiler-grpc \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /src
COPY CMakeLists.txt main.cpp ./
# Cache the build directory between images. OTel SDK objects stay cached and a
# main.cpp-only change only recompiles/relinks cpp-service.
RUN --mount=type=cache,target=/src/build \
    cmake -S . -B build -DCMAKE_BUILD_TYPE=Release \
    && cmake --build build --parallel 2

FROM debian:bookworm-slim
RUN sed -i 's|deb.debian.org|mirrors.tuna.tsinghua.edu.cn|g' \
    /etc/apt/sources.list.d/debian.sources \
    && printf 'Acquire::Retries "5";\nAcquire::http::Timeout "60";\nAcquire::https::Timeout "60";\n' \
    > /etc/apt/apt.conf.d/80-retries \
    && apt-get update \
    && apt-get install -y --no-install-recommends libgrpc++1.51 libprotobuf32 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/build/cpp-service /cpp-service
EXPOSE 8083
USER 65534:65534
ENTRYPOINT ["/cpp-service"]
