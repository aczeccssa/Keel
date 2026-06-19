# AI Gateway 路由池化与负载观测设计规格

**状态：** 设计已批准，等待书面规格复核

**日期：** 2026-06-19
**范围：** `keel-samples` 中 AI Relay 的路由、运行态、管理 API 与 AI Gateway 管理界面

---

## 1. 摘要

当前 AI Gateway 将 routing group 编译成按 priority 排列的候选列表，relay 再按列表顺序执行 first-success-return。即使多个 channel 具有相同 priority 和 weight，低并发流量也可能长期集中到同一个 channel。

本设计将 `routing group + alias/public model` 下的 channel 明确定义为可负载均衡、可 failover、可观测、可解释的 upstream pool，同时保留历史 alias 的有序 failover 行为。

实施后的定义为：

- `priority` 表示 failover tier，数值越大越优先；
- `weight` 表示同 tier 内的目标请求份额；
- direct public model 默认使用 pool 调度；
- 新建 “any attached” alias 使用 `POOL_BALANCE`；
- 历史 alias 使用 `ORDERED_FAILOVER`；
- runtime effective status 是 live routing 的唯一状态真相源；
- DB `channel.status` 仅记录最近测试结果；
- 每个真实调度尝试都有指标和短期 explain trace。

## 2. 当前实现约束

当前实现的重要事实：

1. `ConfigService` 将每个 enabled group 编译成一个 `PoolChainConfig`，并把相同 membership priority 的 channel 放进同一个 `PoolLevelConfig`。
2. `PoolChainManager.selectCandidates` 一次性生成 ordered candidates。
3. `AIRelayService` 对 candidates 顺序发送，请求成功后立即返回。
4. runtime unit 名为 `UpstreamKeyState`，但 DB-backed 配置中 `keyId == channelId`，所以本设计在 operator/API 层统一称为 channel。
5. `ConfigService.reload()` 会替换整个 `PoolChainManager`，因此指标与可恢复运行态不能继续由 manager 实例独占。
6. 管理 API 的现有前缀是 `/api/plugins/airelay/admin`，公开 relay API 的前缀是 `/api/plugins/airelay/v1`。
7. 当前 React `GroupsPanel` 只读展示 group/channel，alias 编辑与 membership 编辑按钮尚未接入现有后端 CRUD。

## 3. 目标与非目标

### 3.1 目标

1. 将 direct public model 和 `POOL_BALANCE` alias 编译为明确的 pool。
2. 在最高可用 priority tier 内实现按 weight 公平、按 saturation 降载的调度。
3. 用原子 permit 严格执行 `maxConcurrency`。
4. 支持同 tier 重选和跨 tier failover。
5. 保持历史 `ORDERED_FAILOVER` alias 的 target 顺序和 first-success-return 行为。
6. 将运行态与滚动指标移到可跨 config reload 保留的独立服务。
7. 提供 pool snapshot、预览 explain、实际 request trace explain 和受控 debug headers。
8. 在 AI Gateway 管理界面中提供 pool、alias policy 和 channel runtime 视图。

### 3.2 非目标

1. 不实现跨进程或跨节点的一致调度；每个节点独立计算份额。
2. 不实现 sticky session、租户 affinity 或 per-key session。
3. 不修改 account、billing 或 riskcontrol 的总体模型。
4. 不持久化 1m/5m/15m runtime buckets；进程重启后窗口从零开始。
5. 不在本期增加可插拔 scheduler；唯一 scheduler 是 `WEIGHTED_LEAST_LOAD`。
6. 不将 debug topology headers 默认暴露给公开调用方。

## 4. 术语与标识

### 4.1 PoolRouteKey

一次路由解析后的逻辑池名称：

- 命中 alias 时为 alias name；
- direct public model 时为请求解析后的 public model；
- model variant fallback 命中时仍记录原请求 model，并额外返回 resolved public model。

### 4.2 PoolId

运行态内部稳定标识：

```text
PoolId(groupId, routeKey)
```

### 4.3 TierId

调度状态内部标识：

```text
TierId(groupId, routeKey, priority)
```

### 4.4 CandidateId

候选项内部标识：

```text
CandidateId(channelId, targetModel)
```

同一个 pool tier 内，一个 channel 最多出现一次。

## 5. Routing Policy

新增枚举：

```kotlin
enum class AliasRoutingPolicy {
    POOL_BALANCE,
    ORDERED_FAILOVER,
}
```

### 5.1 `POOL_BALANCE`

alias 的 attached channels 共同组成 pool。在最高可用 priority tier 内使用 `WEIGHTED_LEAST_LOAD` 选择一个 channel。

### 5.2 `ORDERED_FAILOVER`

alias targets 继续按声明顺序处理。每个 target 内继续按 priority tier 和稳定候选顺序尝试，第一个成功结果立即返回。

此模式是兼容模式，不承诺按 weight 形成长期请求份额。

### 5.3 默认值与兼容规则

1. 数据库新增 `routing_policy VARCHAR(32) NOT NULL DEFAULT 'ORDERED_FAILOVER'`。
2. 启动迁移为所有既有 alias 补上 `ORDERED_FAILOVER`。
3. `AliasRouteConfig.routingPolicy` 的序列化默认值是 `ORDERED_FAILOVER`，保证旧静态 JSON 可继续解码。
4. `UpsertGroupAliasRequest` 未传 `routingPolicy` 时按 `ORDERED_FAILOVER` 处理，保证旧管理客户端不会改变语义。
5. UI 的 “Any attached channels” 创建动作必须显式提交 `POOL_BALANCE`。
6. direct public model 没有 alias row，固定按 `POOL_BALANCE` 处理。
7. 本期不持久化 `schedulerPolicy`；API 固定返回 `WEIGHTED_LEAST_LOAD`。

## 6. Alias Target 解析

### 6.1 `POOL_BALANCE` 解析规则

对 alias 中的有序 targets：

```text
(modelA, optionalChannelId)
(modelB, optionalChannelId)
...
```

按以下方式生成候选：

1. 遍历 group 中 enabled membership 对应的 enabled channel。
2. 对每个 channel 按 targets 声明顺序查找第一个匹配项。
3. target 带 `channelId` 时，只匹配该 channel。
4. target 不带 `channelId` 时，匹配所有支持该 model 的 channel。
5. 第一个匹配 target 的 model 成为该 channel 的 `targetModel`。
6. channel 一旦匹配就停止继续扫描 targets，因此同一 channel 在 pool 中只出现一次。
7. 没有匹配 target 的 channel 以 `UNSUPPORTED_MODEL` 原因排除。

该规则保留 target model 的偏好顺序，同时避免同一 channel 因支持多个 target 而重复获得 weight。

### 6.2 `ORDERED_FAILOVER` 解析规则

按 target 声明顺序逐个构建候选集合，不跨 target 去重。该行为保持现有 alias fallback 语义。

### 6.3 Direct model

direct public model 只有一个隐式 target。所有支持该 public model 或具有对应 model mapping 的 attached channel 都可进入 pool。

## 7. Eligibility 与 Effective Status

channel 进入调度候选集必须满足：

1. group enabled；
2. membership enabled；
3. channel enabled；
4. target/model 匹配；
5. runtime base status 为 `HEALTHY`；
6. `currentConcurrency < maxConcurrency`。

runtime base status：

```kotlin
enum class RuntimeChannelStatus {
    HEALTHY,
    COOLDOWN,
    DEGRADED,
    DISABLED,
}
```

API effective status 增加派生值 `SATURATED`：

```text
baseStatus == HEALTHY && currentConcurrency >= maxConcurrency
```

`SATURATED` 不写入 DB，也不覆盖 base status。permit 释放后 effective status 自动恢复为 `HEALTHY`。

`COOLDOWN` 到期时惰性恢复：selection、snapshot 或 explain 读取状态时执行到期检查并切回 `HEALTHY`。

DB `ChannelTable.status` 仅表示 persisted/test status，不参与 eligibility。

## 8. `WEIGHTED_LEAST_LOAD` 调度算法

### 8.1 为什么不用 `currentConcurrency / weight`

空闲或低并发时所有 channel 的 current concurrency 都为 0，该公式无法表达 2:1 请求份额。因此调度器必须同时维护平滑权重状态和当前负载惩罚。

### 8.2 状态

每个 `TierId` 维护：

- 每个 channel 的 `currentWeight: Double`；
- `tieCursor: Long`；
- 一个只保护该 tier 调度状态的短临界区锁。

每个 channel 的 concurrency counter 独立存放在 runtime registry 中，以便同一 channel 被不同 model/alias pool 使用时仍共享同一个 `maxConcurrency`。

### 8.3 选择公式

一次选择中，对当前 tier 的 eligible channels 执行：

```text
normalizedWeight_i = max(configuredWeight_i, 1)
totalWeight = sum(normalizedWeight_i)
saturation_i = currentConcurrency_i / max(maxConcurrency_i, 1)

currentWeight_i += normalizedWeight_i
effectiveScore_i = currentWeight_i - saturation_i * totalWeight
```

按以下顺序选择：

1. `effectiveScore` 降序；
2. 分数完全相同时使用 per-tier `tieCursor` 做轮转；
3. 仍相同时按 `channelId` 升序。

成功获取 permit 后：

```text
selected.currentWeight -= totalWeight
tieCursor += 1
```

低并发、所有 saturation 为 0 时，该算法退化为 smooth weighted round robin，能够稳定产生目标请求份额；有 inflight 时，saturation penalty 会降低慢 channel 的新流量。

### 8.4 原子 permit

`tryAcquire(channelId)` 使用 CAS 循环：

```text
loop:
  observed = currentConcurrency
  if observed >= maxConcurrency: return false
  if compareAndSet(observed, observed + 1): return true
```

选择流程在获得 permit 后才返回 `PoolLease`。如果最高分 channel 的 CAS 失败，则将其标记为本轮 saturated，重新计算剩余候选；不得返回未持有 permit 的 selection。

`PoolLease.close()` 必须幂等，并在 relay 的 `finally` 中调用。blocking、streaming、上游异常和本地异常都必须释放 permit。

### 8.5 Preview

explain preview 使用 tier 状态和 counters 的只读副本计算，不推进 `currentWeight` 或 `tieCursor`，也不获取 permit。返回结果只代表该快照下的预计选择。

## 9. Priority 与 Failover

### 9.1 `POOL_BALANCE`

1. 按 priority 从大到小遍历 tiers。
2. 找到包含至少一个 eligible channel 的最高 tier。
3. 在该 tier 内选择并获取一个 lease。
4. 可重试失败后释放 lease，将该 channel 加入本请求的 `attemptedChannelIds`，然后在同 tier 重新执行实时选择。
5. 同 tier 无剩余 eligible channel 后进入下一 priority tier。
6. 每次重选都读取最新 runtime status、cooldown 和 concurrency；不得使用请求开始时预生成的静态 candidate list。

### 9.2 `ORDERED_FAILOVER`

保持 target 顺序和 first-success-return。为了严格执行 concurrency，兼容路径也必须通过 `tryAcquire` 获取 permit，但不使用新的长期 share 保证。

### 9.3 请求内去重

同一请求中，已经发生可重试失败的 `(channelId, targetModel)` 不得再次尝试。跨 priority 或不同 target 指向相同 pair 时同样去重。

## 10. 失败分类与状态变化

| 结果 | 是否继续 failover | Runtime 状态变化 |
|---|---:|---|
| transport exception / timeout | 是 | `COOLDOWN`，transport backoff |
| HTTP 408 | 是 | `COOLDOWN`，transport backoff |
| HTTP 429 | 是 | `COOLDOWN`，优先使用合法 `Retry-After` |
| HTTP 500–599 | 是 | `COOLDOWN`，5xx backoff |
| HTTP 400 / 404 / 409 / 422 | 否 | 不改变 channel 状态 |
| HTTP 401 / 403 | 否 | `DISABLED` |
| 其他 HTTP 4xx | 否 | 不改变 channel 状态 |
| 上游成功后的本地计费失败 | 否 | 记 channel success，不改变健康状态 |
| 上游成功后的 usage 持久化失败 | 否 | 记 channel success，不改变健康状态 |
| response decode/协议错误 | 是 | `COOLDOWN`，按 transport/protocol failure 处理 |

`Retry-After` 仅接受非负秒数或可解析 HTTP date，并受 level cooldown 上限约束。非法值回退到指数退避。

不可重试错误必须返回原始映射后的客户端状态，不得统一改成 pool exhausted 503。只有所有可重试路径耗尽或没有任何 eligible channel 时返回 503。

## 11. Runtime Registry 与 Config Reload

新增独立 `PoolRuntimeRegistry`，生命周期与 AI Relay plugin 一致，不随 `PoolChainManager` reload 替换。

registry 以 `channelId` 保存：

- base runtime status；
- current concurrency；
- consecutive failures；
- cooldown deadline；
- last error；
- last selected time。

reload 行为：

1. 新 topology 引用既有 channelId 时复用相同 runtime entry。
2. 新 channel 创建 `HEALTHY` entry。
3. topology 不再引用的 channel 标记 orphaned。
4. orphan entry 在 15 分钟无 inflight、无新引用后清理。
5. orphan entry 仍有 inflight 时保留到最后一个 lease 释放。
6. reload 不重置 metrics buckets 或 request traces。
7. 管理 reset 将 base status、failure count、cooldown 和 last error 恢复初始值，但不修改 current concurrency。

## 12. Runtime Metrics

### 12.1 服务与时间桶

新增 `PoolRuntimeMetricsService`，按 monotonic clock 接收事件，使用 10 秒 bucket，保留最近 15 分钟，共 90 个 bucket。

窗口固定支持：

- `1m`：最近 6 个完整/当前 bucket；
- `5m`：最近 30 个 bucket；
- `15m`：最近 90 个 bucket。

进程重启后窗口从零开始。config reload 不清零。

### 12.2 事件

每次真实调度过程记录：

- `Selected`：拿到 permit 后；
- `AttemptSucceeded`：上游响应成功并可解码；
- `AttemptFailed`：包含 status/error class、retryable、latency；
- `Failover`：准备从一个失败 attempt 进入下一选择；
- `CooldownStarted`；
- `CooldownRecovered`。

### 12.3 Share 计算

`trafficShare` 使用 selected attempt 数量：

```text
trafficShare_i = selectedRequests_i / selectedRequests_pool
```

每次选择时，对当时同 tier eligible candidates 记录期望份额贡献：

```text
expectedContribution_i = weight_i / sum(eligibleWeights)
```

窗口内：

```text
expectedShare_i = sum(expectedContribution_i) / schedulingDecisions
shareDeviation_i = trafficShare_i - expectedShare_i
```

因此 cooldown、disabled、saturated 或模型不匹配期间不会继续给该 channel 累计期望流量。

当窗口内 `selectedRequests_pool < 100` 时，API 仍返回 share 数值，但同时返回 `shareSampleSufficient=false`，UI 不显示偏差告警。

### 12.4 Latency 与错误率

- per-channel latency 统计每次真实 upstream attempt；
- pool latency 统计最终完成的 relay 请求；
- per-channel error rate = failed attempts / selected attempts；
- pool error rate = 最终失败请求 / pool 请求数；
- percentile 使用最近窗口内样本的 nearest-rank 规则；空窗口返回 `null`，不返回伪造的 0。

### 12.5 指标字段

per-channel 每个窗口返回：

- selectedRequests；
- successRequests；
- failedRequests；
- errorRate；
- p50/p95/p99 latency；
- trafficShare；
- expectedShare；
- shareDeviation；
- shareSampleSufficient；
- cooldownCount。

pool 每个窗口返回：

- requestCount；
- successRequests；
- failedRequests；
- errorRate；
- p50/p95/p99 latency；
- failoverCount。

## 13. Explain 与 Request Trace

### 13.1 Trace 生命周期

每个 relay 请求使用现有或新生成的 `requestId`。runtime trace 最多保留 5 分钟且全局最多 10,000 条；超时或超量时按最旧优先清理。

trace 包含：

- requestId；
- groupId；
- requestedModel；
- routeKey；
- resolved public model；
- routing policy；
- scheduler policy；
- 每次 selection 时看到的 tiers；
- eligible/ineligible channel 及排除原因；
- weight、saturation、currentWeight、effectiveScore；
- attempt 顺序、target model、结果、latency；
- 最终 channel；
- failover count；
- final status。

排除原因使用固定枚举：

```text
GROUP_DISABLED
MEMBERSHIP_DISABLED
CHANNEL_DISABLED
UNSUPPORTED_MODEL
COOLDOWN
DEGRADED
RUNTIME_DISABLED
SATURATED
ALREADY_ATTEMPTED
LOWER_PRIORITY_TIER
ORDERED_TARGET_NOT_ACTIVE
```

### 13.2 Preview explain

不传 requestId 时执行 dry-run：

- 不推进 scheduler state；
- 不获取 permit；
- 不改变 status 或 metrics；
- 返回 `preview=true`；
- 返回 `wouldSelectChannelId`；
- 明确声明并发变化可能使真实选择不同。

`variantKey` 仅返回到 explain context；本期不参与 routing selection，因为当前实现只将它用于 pricing。

### 13.3 Actual trace explain

传 requestId 时只读取已记录 trace，返回 `preview=false`。不存在或已过期时返回 404。

## 14. 数据模型与迁移

### 14.1 `GroupAliasTable`

新增：

```text
routing_policy VARCHAR(32) NOT NULL DEFAULT 'ORDERED_FAILOVER'
```

启动迁移：

```sql
ALTER TABLE airelay_group_alias
ADD COLUMN IF NOT EXISTS routing_policy VARCHAR(32)
NOT NULL DEFAULT 'ORDERED_FAILOVER';
```

读取未知值时按 `ORDERED_FAILOVER`，同时记录警告日志；写入 API 对未知值返回 400。

### 14.2 DTO

`GroupAliasView` 新增：

```json
{
  "routingPolicy": "ORDERED_FAILOVER",
  "schedulerPolicy": "WEIGHTED_LEAST_LOAD"
}
```

`UpsertGroupAliasRequest` 新增：

```json
{
  "routingPolicy": "ORDERED_FAILOVER"
}
```

`schedulerPolicy` 只读，不接受写入。

## 15. 管理 API

所有接口沿用管理员认证和现有 `/api/plugins/airelay/admin` 前缀。

### 15.1 列出 group pools

```http
GET /api/plugins/airelay/admin/groups/{groupId}/pools?window=1m
```

`window` 仅允许 `1m|5m|15m`，缺省为 `1m`，非法值返回 400。

响应：

```json
{
  "groupId": "premium",
  "window": "1m",
  "pools": [
    {
      "routeKey": "smart-claude",
      "requestedKind": "ALIAS",
      "routingPolicy": "POOL_BALANCE",
      "schedulerPolicy": "WEIGHTED_LEAST_LOAD",
      "tiers": []
    }
  ]
}
```

### 15.2 获取一个 pool

```http
GET /api/plugins/airelay/admin/groups/{groupId}/pools/{routeKey}?window=1m
```

`routeKey` 使用标准 URL path encoding。alias 与 direct model 同名时沿用 relay 解析规则：enabled alias 优先。

每个 tier 返回：

```json
{
  "priority": 100,
  "active": true,
  "healthyChannels": 1,
  "cooldownChannels": 0,
  "degradedChannels": 0,
  "disabledChannels": 0,
  "saturatedChannels": 1,
  "totalInflight": 10,
  "metrics": {},
  "channels": []
}
```

channel runtime view 返回：

```json
{
  "channelId": "ch-a",
  "channelName": "Anthropic A",
  "targetModel": "claude-sonnet-4",
  "effectiveStatus": "HEALTHY",
  "persistedTestStatus": "HEALTHY",
  "priority": 100,
  "weight": 100,
  "currentConcurrency": 2,
  "maxConcurrency": 10,
  "saturation": 0.2,
  "metrics": {},
  "cooldownUntilEpochMs": null,
  "lastError": null,
  "lastSelectedAtEpochMs": 1781860000000
}
```

### 15.3 Explain

```http
POST /api/plugins/airelay/admin/groups/{groupId}/pools/{routeKey}/explain
Content-Type: application/json
```

预览请求：

```json
{
  "requestedModel": "smart-claude",
  "variantKey": null
}
```

实际 trace 请求：

```json
{
  "requestId": "req-123"
}
```

同时提供 `requestId` 和 `requestedModel` 时以 requestId trace 为准，并校验 trace 的 groupId/routeKey 与 URL 一致，不一致返回 404。

### 15.4 Runtime reset

保留现有 reset 能力，并新增 channel 语义路径：

```http
POST /api/plugins/airelay/admin/groups/{groupId}/channels/{channelId}/runtime/reset
```

成功返回 200；group/channel 组合不存在返回 404。

## 16. Debug Response Headers

debug headers 默认关闭。只有以下两个条件同时满足才返回：

1. JVM property `keel.airelay.debugHeaders.enabled=true` 或环境变量 `KEEL_AIRELAY_DEBUG_HEADERS_ENABLED=true`；
2. relay 请求包含 `X-AIRelay-Debug: true`。

返回：

```text
X-AIRelay-Selected-Channel
X-AIRelay-Selected-Priority
X-AIRelay-Routing-Policy
X-AIRelay-Failover-Count
X-AIRelay-Request-Id
```

未同时满足条件时，除现有通用 request id 外不返回任何 topology header。debug header 值不得包含 API key、base URL 或完整 error message。

## 17. UI

### 17.1 Group Pool 视图

`GroupsPanel` 从 group 卡片进入 pool 详情，层级为：

```text
Group
  Route key / alias
    Priority tier
      Channel
```

每个 channel 展示：

- channel name/id；
- target model；
- routing policy；
- priority 与 weight；
- effective status 与 persisted test status；
- inflight/max concurrency；
- saturation；
- 1m selected requests、success rate、p95/p99；
- traffic share、expected share、deviation；
- cooldown deadline；
- last error。

pool 页面每 5 秒刷新一次；页面不可见时暂停 polling，重新可见时立即刷新。

### 17.2 Channel detail

channel drawer 展示最近 5m 的：

- latency p50/p95/p99；
- inflight 当前值和窗口峰值；
- traffic/expected share；
- cooldown、failure 和 failover 事件摘要。

曲线使用已有 UI/CSS 能力实现轻量 SVG，不为本需求引入新的 chart dependency。

### 17.3 Alias editor

alias 编辑器必须展示：

- `POOL_BALANCE`：attached channels 共同承载流量；
- `ORDERED_FAILOVER`：targets 按顺序尝试。

选择 “Any attached channels” 时自动设置 `POOL_BALANCE`，并创建不带 `channelId` 的 targets。编辑历史 alias 时保持后端返回的 policy，不自动切换。

### 17.4 Explain panel

支持：

- 输入 requested model 执行 preview；
- 输入 request id 查询真实 trace；
- 展示 tiers、排除原因、score、预计/实际 channel 和 failover 路径；
- 明确区分 Preview 与 Recorded request。

## 18. 验收标准

### 18.1 调度

1. 两个同 priority、weight=1 的 healthy channels，在 600 个串行选择并立即释放 permit 的确定性测试中，各获得 300±1 次选择。
2. weight=100:50 的两个 healthy channels，在 600 个串行选择中分别获得 400±2 和 200±2 次选择。
3. 持续并发测试中，慢 channel 的 saturation 上升后获得的新增选择少于同权重空闲 channel。
4. 任意并发压力下 `currentConcurrency` 不得超过 `maxConcurrency`。
5. 高 priority tier 存在 eligible channel 时，低 tier selectedRequests 为 0。
6. 高 tier 全部 cooldown/disabled/degraded/saturated 时，选择下一 tier。
7. cooldown 到期后 channel 自动重新参与调度。
8. `ORDERED_FAILOVER` alias 保持第一个有效 target/channel 优先。
9. `POOL_BALANCE` 中同一 channel 支持多个 targets 时只出现和计权一次，并使用第一个匹配 target model。

### 18.2 失败处理

1. transport、408、429、5xx 和 response decode failure 会 failover。
2. 400、404、409、422 与其他非 401/403 的 4xx 不 failover、不降级 channel，并返回原错误。
3. 401/403 不 failover，并将 runtime base status 设为 disabled。
4. 本地计费或 usage 持久化失败不降低 upstream channel 健康状态。

### 18.3 指标与 explain

1. 1m/5m/15m bucket 边界测试使用 fake clock，结果确定且不依赖 sleep。
2. failed attempt 和 failover 均归属实际 channel/pool。
3. config reload 后既有 channel 的 runtime 状态和窗口指标保留。
4. preview explain 不改变下一次真实选择。
5. actual request trace 能返回真实 attempt 顺序和最终 channel。
6. trace 超过 5 分钟或被容量淘汰后返回 404。
7. debug headers 在任一开关未满足时都不出现。

### 18.4 UI

1. pool 页面可按 route/tier/channel 显示 runtime snapshot。
2. polling 间隔不超过 5 秒，页面隐藏时不继续请求。
3. 历史 alias 显示 `ORDERED_FAILOVER` 提示。
4. “Any attached channels” 保存为 `POOL_BALANCE`。
5. selectedRequests 少于 100 时不显示 share deviation 告警。
6. explain panel 能区分 preview 和 recorded trace。

## 19. 实施边界与分期

该需求拆分为三个顺序执行、分别可验证的实施计划：

### Phase 1：路由与兼容迁移

- routing policy schema/DTO/migration；
- alias/direct candidate compilation；
- runtime registry；
- weighted least-load scheduler；
- atomic lease；
- blocking/streaming dynamic failover；
- failure taxonomy；
- routing unit/integration tests。

完成标准：所有路由、权重、priority、cooldown、saturation 和兼容测试通过。

### Phase 2：指标与管理 API

- runtime rolling metrics；
- request trace；
- pool snapshot API；
- preview/actual explain；
- reset API；
- debug headers；
- API integration tests。

完成标准：runtime snapshot、窗口统计、explain 与安全开关测试通过。

### Phase 3：管理 UI

- API client contracts；
- group pool view；
- alias policy editor；
- channel runtime drawer；
- explain panel；
- 5 秒 visibility-aware polling；
- Vitest 和 frontend build。

完成标准：UI component tests、typecheck 和 production build 通过。

## 20. 最终定义

完成三个阶段后，对 operator 而言：

> routing group 下某个 alias/public model 对应的多个 attached channels，是一个按 priority failover、按 weight 公平分流、按实时 saturation 降载、可观测且可解释的 upstream pool。

同时继续满足：

- 历史 alias 不会被静默改成 pool；
- max concurrency 是硬上限；
- live routing 不受持久化测试状态误导；
- 任何一次真实请求都能在短期内解释其选择和 failover 路径。
