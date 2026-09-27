# 已知 TODO（repo known gaps）

- `agent/demo-app`: initial validation scope is logfile-reporter-plugin only; extend to override-httpclient-4.x and override-hutool-http-5.x later (structure already multi-plugin ready).
- trace 写线程的分配 / CPU 优化（P0 SQL 复用+cap 节流 / P1 流式序列化直写 gzip / P2 去小对象 / P3 gzip 复用与 level，先量化再动）→ `docs/todos/h2化-trace写路径分配与CPU优化.md`
