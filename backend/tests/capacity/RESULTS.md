# 容量测试结果

尚无本次容量测试的实测数字。测试只在 GitHub Actions 执行；通过后将用
`capacity-results` artifact 中的自动报告替换本文件，并归档原始 `measurements.json`。

固定 seed、两组指定容量、每种 SQL 30 次与 p95 ≤100 ms 目标见 `capacity_test.ts`。
本文件不使用估算充当实测。
