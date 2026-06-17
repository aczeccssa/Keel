# AI Gateway 用户使用指南

## Dashboard 指标说明

### 顶部概览
- **Requests**：当前时间窗口内请求总数。
- **Success Rate**：HTTP 状态码小于 400 的请求占比。
- **Cost**：当前时间窗口内累计美元成本。
- **Avg Latency**：请求平均延迟。

### 缓存效率
- **Cache Hit Rate**：`cacheReadInputTokens / (promptTokens + cacheReadInputTokens)`。
- 命中率偏低时，检查请求是否正确设置 Anthropic `cache_control`，以及请求前缀是否稳定。

### Channel 健康度
- **Healthy**：可正常路由的 upstream key/channel。
- **Cooldown**：因 429/5xx/网络错误进入冷却的 upstream key/channel。
- **Disabled**：认证错误或手动禁用的 upstream key/channel。

### 延迟百分位
- **P50**：中位延迟。
- **P95**：95% 请求低于该延迟，超过 5s 建议关注。
- **P99**：尾延迟，超过 10s 应检查上游或网络。

## Usage 面板

Usage 面板用于定位单次请求问题：
- 使用 Group/Channel/Model/Status 筛选器缩小范围。
- 点击 **View** 打开详情 Drawer。
- 详情包含 Token、Cost、路由、延迟、Failover 和错误信息。

## Providers / Channels 面板

每个 Channel 卡片显示：
- 7 天可用率。
- 最近 60 次测试记录 sparkline（绿色成功，红色失败）。
- 7 天调用统计：请求数、平均延迟、成本、Token。

## 常见排查

### 缓存命中率为 0
1. 确认请求中包含可缓存内容。
2. 确认上游响应 usage 包含 cache read/write token。
3. 在 Usage 详情中查看 Cache Read / Cache Write。

### Channel 可用率低于 90%
1. 查看最近测试 sparkline 中失败时间点。
2. 查看 Channel 最后错误信息。
3. 在 Usage 面板按 Channel + Error 筛选，检查错误类型。
4. 若为 401/403，检查 API Key；若为 429，调整并发或限流；若为 5xx/网络错误，检查上游可用性。

### 延迟 P99 过高
1. Dashboard 查看 P95/P99 趋势。
2. 按 Channel 分布查看是否集中在某个上游。
3. 检查 Failover 次数和上游超时配置。
