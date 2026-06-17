# AI Gateway API 参考

## Dashboard 统计

```http
GET /api/plugins/airelay/admin/stats/dashboard?window=24h
```

### 查询参数
- `window`: `1h` | `24h` | `7d` | `30d`，默认 `24h`。

### 响应结构
```json
{
  "overview": {
    "totalRequests": 1234,
    "successRate": 0.987,
    "totalCostUsd": 56.78,
    "totalTokens": 1000000,
    "avgLatencyMs": 1200,
    "p50LatencyMs": 800,
    "p95LatencyMs": 3000,
    "p99LatencyMs": 6000,
    "cacheHitRate": 0.32,
    "healthyChannels": 8,
    "cooldownChannels": 1,
    "disabledChannels": 1
  },
  "trends": {
    "requestsByHour": [{ "timestamp": 1780000000000, "requests": 42, "successRate": 0.98 }],
    "tokensByHour": [{ "timestamp": 1780000000000, "promptTokens": 1000, "completionTokens": 500, "cacheWriteTokens": 100, "cacheReadTokens": 300, "costUsd": 0.42 }],
    "latencyByHour": [{ "timestamp": 1780000000000, "p50": 800, "p95": 3000, "p99": 6000 }]
  },
  "distributions": {
    "modelDistribution": [],
    "channelDistribution": [],
    "groupDistribution": [],
    "errorDistribution": []
  }
}
```

## Channel 统计

```http
GET /api/plugins/airelay/admin/channels/{channelId}/stats?window=7d
```

### 响应结构
```json
{
  "channelId": "ch-abc",
  "successRate7d": 0.95,
  "totalRequests7d": 100,
  "avgLatencyMs": 1200,
  "totalCostUsd": 12.34,
  "totalTokens": 234567,
  "recentTests": [
    { "timestamp": 1780000000000, "ok": true, "latencyMs": 900 }
  ]
}
```

## Usage 记录筛选

```http
GET /api/plugins/token/admin/usage/records?limit=200&groupId=default&channelId=ch-abc&model=claude&statusFilter=error
```

### 查询参数
- `limit`: 返回条数，1-200。
- `groupId`: 按 `poolLevelId` 筛选。
- `channelId`: 按 `upstreamKeyId` 筛选。
- `model`: 按模型名称筛选。
- `statusFilter`: `success` 或 `error`。

### 响应字段
每条记录包含：
- `groupId` / `poolLevelId`
- `channelId` / `upstreamKeyId`
- `usage`：input/output/cache tokens
- `cost`：input/output/cache/total cost + cacheHitRate
- `latencyMs`, `status`, `outcome`, `errorCode`, `errorDetail`

## 错误码
- `400`: 参数错误。
- `401/403`: 管理权限不足。
- `404`: Channel/Group 不存在。
- `503`: Channel store 或依赖服务不可用。
