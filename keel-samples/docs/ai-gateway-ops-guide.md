# AI Gateway 运维手册

## 监控窗口

Dashboard 支持 `1h`、`24h`、`7d`、`30d` 窗口。短窗口适合实时排障，长窗口适合容量和成本分析。

## 性能优化

### Dashboard 统计缓存
Dashboard API 使用 1 分钟内存缓存，减少重复聚合开销。缓存键按 `window` 区分。

### 百分位延迟
当前基于窗口内 usage 记录排序计算 P50/P95/P99。数据量很大时可以继续扩展为：
- SQL 窗口函数或预聚合表。
- 按时间桶预计算延迟直方图。
- 对超大窗口采样计算。

### Channel 测试历史
`channel_test_history` 每个 channel 保留最近 100 条测试记录，避免表无界增长。

## 建议告警阈值

- Channel 7 天可用率 < 90%。
- Success Rate < 95%。
- P99 延迟 > 10s。
- Cache Hit Rate 长期 < 5% 且预期使用缓存。
- 429 错误持续增长。

## 常见故障处理

### 401/403 上游认证错误
- 检查 Channel API Key 或环境变量。
- 更新配置后重新测试 Channel。

### 429 限流
- 降低 channel 并发或增加备用 channel。
- 调整 routing group 权重。

### 5xx 或网络错误
- 查看 Channel 最近测试 sparkline。
- 使用 Usage 面板按 Channel + Error 筛选。
- 检查上游服务、网络、防火墙或 baseUrl。

### 成本异常
- Dashboard 查看模型分布和 group 分布。
- 检查高成本模型是否被大量调用。
- 调整 pricing 或 routing group 权重。

## 发布验证清单

1. 编译通过：`./gradlew :keel-samples:build -x test`。
2. 前端构建通过：`npm run build -w @keel/ai-gateway-ui`。
3. 打开 Dashboard，确认概览、趋势、分布、实时区显示。
4. 打开 Usage，确认筛选器和详情 Drawer 可用。
5. 打开 Channels，确认可用率和 sparkline 显示。
