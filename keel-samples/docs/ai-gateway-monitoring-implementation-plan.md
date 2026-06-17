# AI Gateway 监控增强 - 详细实施计划

## 计划概览

本文档提供 AI Gateway 监控功能增强的完整实施路径，包括：
- 6 个功能缺失的修复
- 1 个缓存命中率 Bug 修复
- 按任务分解的详细步骤
- 每个任务的输入、输出、验证标准

**配套文档**：`ai-gateway-monitoring-design.md`（设计文档）

---

## 阶段划分

| 阶段 | 目标 | 主要产物 |
|------|------|----------|
| **阶段 1** | 数据审计与准备 | 数据完整性报告、Schema 修正 |
| **阶段 2** | 后端 API 开发 | 新增 5 个 API 端点、增强 1 个现有端点 |
| **阶段 3** | 前端核心功能 | Usage/Providers 面板增强 |
| **阶段 4** | Dashboard 开发 | 完整运维面板 |
| **阶段 5** | 测试与优化 | 端到端测试、性能优化 |

---

## 阶段 1：数据审计与准备

### Task 1.1：检查 Usage 数据完整性

**目标**：验证 `usage` 表是否包含所有必需字段，特别是 `poolLevelId`、`upstreamKeyId`、缓存字段。

**执行步骤**：
```bash
# 1. 查看表结构
sqlite3 ~/keel-data/databases/aigateway_airelay.db ".schema usage"

# 2. 检查最近 20 条记录的关键字段
sqlite3 ~/keel-data/databases/aigateway_airelay.db <<EOF
SELECT 
  recordId,
  poolLevelId,
  upstreamKeyId,
  model,
  promptTokens,
  cacheReadInputTokens,
  cacheCreationInputTokens,
  createdAt
FROM usage 
ORDER BY createdAt DESC 
LIMIT 20;
EOF

# 3. 统计缓存字段的非零记录数
sqlite3 ~/keel-data/databases/aigateway_airelay.db <<EOF
SELECT 
  COUNT(*) as total,
  COUNT(CASE WHEN cacheReadInputTokens > 0 THEN 1 END) as has_cache_read,
  COUNT(CASE WHEN cacheCreationInputTokens > 0 THEN 1 END) as has_cache_write
FROM usage;
EOF
```

**验证标准**：
- ✅ `poolLevelId` 列存在且大部分记录非空
- ✅ `upstreamKeyId` 列存在且大部分记录非空
- ⚠️ 如果 `cacheReadInputTokens` 全为 0 → 转到 Task 1.3 排查
- ⚠️ 如果 `poolLevelId` / `upstreamKeyId` 不存在 → 转到 Task 1.2 添加字段

**输出**：`data-audit-report.txt`（记录查询结果）

---

### Task 1.2：修复 Usage 表字段缺失（如需要）

**前置条件**：Task 1.1 发现字段缺失

**修改文件**：
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/usage/UsageRecorder.kt`

**变更**：
确保建表语句包含以下字段：
```kotlin
CREATE TABLE IF NOT EXISTS usage (
    recordId TEXT PRIMARY KEY,
    keyId TEXT NOT NULL,
    userId TEXT NOT NULL,
    userGroupId TEXT NOT NULL,
    poolLevelId TEXT,           -- 新增（如缺失）
    upstreamKeyId TEXT,          -- 新增（如缺失）
    model TEXT NOT NULL,
    provider TEXT NOT NULL,
    promptTokens INTEGER NOT NULL,
    completionTokens INTEGER NOT NULL,
    cacheReadInputTokens INTEGER DEFAULT 0,     -- 确保存在
    cacheCreationInputTokens INTEGER DEFAULT 0, -- 确保存在
    -- ... 其他字段
)
```

**验证**：重启服务后，重新执行 Task 1.1 的查询，确认字段存在。

---

### Task 1.3：排查缓存字段为 0 的根因

**目标**：确定缓存 token 数据是否在上游返回、是否被正确解析。

**执行步骤**：

#### 步骤 A：检查上游响应
```bash
# 发送一个带缓存的测试请求
curl -X POST http://localhost:8080/api/airelay/v1/messages \
  -H "Authorization: Bearer your-test-key" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-opus-4-8",
    "max_tokens": 100,
    "messages": [{"role": "user", "content": "Hello"}],
    "system": [
      {
        "type": "text",
        "text": "You are a helpful assistant.",
        "cache_control": {"type": "ephemeral"}
      }
    ]
  }'
```

检查响应的 `usage` 字段是否包含：
```json
{
  "usage": {
    "input_tokens": 123,
    "output_tokens": 45,
    "cache_creation_input_tokens": 100,  // ← 应该有值
    "cache_read_input_tokens": 0
  }
}
```

#### 步骤 B：检查 Codec 解析
查看文件：
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicMessagesCodec.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/openai/chat/OpenAIChatCodec.kt`

确认 `decodeResponse()` 方法正确提取：
```kotlin
cacheCreationInputTokens = usageObj.int("cache_creation_input_tokens") ?: 0
cacheReadInputTokens = usageObj.int("cache_read_input_tokens") ?: 0
```

#### 步骤 C：检查记录逻辑
查看 `AIRelayService.kt` 的 `usageRecorder.record()` 调用，确认传入的 `usage` 对象包含缓存字段。

**输出**：根因诊断报告（是上游未返回、Codec 未解析，还是记录时丢失）

---

### Task 1.4：创建 Channel 测试历史表

**目标**：新增表存储每次 Channel 测试的结果，用于绘制"近60次记录"微型图。

**修改文件**：
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/config/ChannelRepository.kt`

**新增建表语句**：
```kotlin
database.execute("""
    CREATE TABLE IF NOT EXISTS channel_test_history (
        test_id TEXT PRIMARY KEY,
        channel_id TEXT NOT NULL,
        tested_at INTEGER NOT NULL,
        success BOOLEAN NOT NULL,
        latency_ms INTEGER,
        error_message TEXT
    )
""")
database.execute("""
    CREATE INDEX IF NOT EXISTS idx_channel_time 
    ON channel_test_history(channel_id, tested_at DESC)
""")
```

**新增方法**：
```kotlin
fun recordTestHistory(channelId: String, success: Boolean, latencyMs: Long?, error: String?) {
    database.transaction {
        it.insert("channel_test_history") {
            set("test_id", UUID.randomUUID().toString())
            set("channel_id", channelId)
            set("tested_at", System.currentTimeMillis())
            set("success", success)
            set("latency_ms", latencyMs)
            set("error_message", error)
        }
    }
    // 保留最近 100 条记录
    database.execute("""
        DELETE FROM channel_test_history 
        WHERE channel_id = ? 
        AND tested_at NOT IN (
            SELECT tested_at FROM channel_test_history 
            WHERE channel_id = ? 
            ORDER BY tested_at DESC 
            LIMIT 100
        )
    """, channelId, channelId)
}

fun getRecentTests(channelId: String, limit: Int = 60): List<TestRecord> {
    return database.query("""
        SELECT tested_at, success, latency_ms 
        FROM channel_test_history 
        WHERE channel_id = ? 
        ORDER BY tested_at DESC 
        LIMIT ?
    """, channelId, limit).map {
        TestRecord(
            timestamp = it.getLong("tested_at"),
            ok = it.getBoolean("success"),
            latencyMs = it.getLong("latency_ms")
        )
    }
}
```

**验证**：
```bash
# 测试几次 channel
curl -X POST http://localhost:8080/api/airelay/admin/channels/{channelId}/test

# 查询历史记录
sqlite3 ~/keel-data/databases/aigateway_airelay.db \
  "SELECT * FROM channel_test_history ORDER BY tested_at DESC LIMIT 10;"
```

---

## 阶段 2：后端 API 开发

### Task 2.1：增强 UsageRecordView 数据模型

**目标**：在 Usage API 返回中增加 `groupId`、`channelId`、`channelName`、`cacheHitRate` 字段。

**修改文件**：
- `keel-contract/src/main/kotlin/com/keel/contract/ai/AiGatewayContracts.kt`

**变更**：
```kotlin
@Serializable
data class UsageRecordView(
    val recordId: String,
    val requestId: String?,         // 新增
    val userId: String,
    val keyId: String,
    val groupId: String?,           // 新增（从 poolLevelId 提取）
    val channelId: String?,         // 新增（upstreamKeyId）
    val channelName: String?,       // 新增（查询 channel 表）
    val model: String,
    val provider: String,
    val status: Int,
    val outcome: String,
    val errorCode: String?,
    val errorDetail: String?,
    val latencyMs: Long,
    val usage: TokenUsage,
    val cost: CostBreakdown,
    val cacheHitRate: Double?,      // 新增（后端计算）
    val streamed: Boolean,
    val failoverCount: Int,
    val createdAt: String
)
```

**验证**：
```bash
curl -s http://localhost:8080/api/airelay/usage | jq '.records[0] | {groupId, channelId, channelName, cacheHitRate}'
```

---

### Task 2.2：实现 Channel 统计 API

**目标**：提供单个 Channel 的详细统计数据。

**修改文件**：`AIRelayPlugin.kt`

**新增端点**：
```kotlin
get<ChannelStatsResponse>(
    "/admin/channels/{channelId}/stats",
    doc = OpenApiDoc(summary = "获取 Channel 统计数据", tags = listOf("ai-gateway", "airelay", "admin"))
) {
    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
    val window = queryParameters["window"]?.firstOrNull() ?: "7d"
    val stats = calculateChannelStats(channelRepository!!, channelId, window)
    PluginResult(body = stats)
}
```

**实现统计计算**：计算成功率、百分位延迟、测试历史（见设计文档）。

**验证**：
```bash
curl -s http://localhost:8080/api/airelay/admin/channels/ch-abc123/stats?window=7d | jq '.successRate, .p95LatencyMs'
```

---

### Task 2.3：实现 Dashboard 聚合统计 API

**目标**：提供 Dashboard 所需的全局统计数据。

**新增端点**：
```kotlin
get<DashboardStatsResponse>(
    "/admin/stats/dashboard",
    doc = OpenApiDoc(summary = "获取 Dashboard 聚合统计", tags = listOf("ai-gateway", "airelay", "admin"))
) {
    val window = queryParameters["window"]?.firstOrNull() ?: "24h"
    val stats = calculateDashboardStats(window)
    PluginResult(body = stats)
}
```

**核心数据结构**（见设计文档第四章）：
- `OverviewStats`：概览卡片数据
- `TrendsData`：时序趋势
- `DistributionsData`：分布分析

**验证**：
```bash
curl -s http://localhost:8080/api/airelay/admin/stats/dashboard?window=24h | jq '.overview'
```

---

### Task 2.4：增强 Usage API 查询参数

**目标**：支持按 group/channel/model/status 筛选。

**修改文件**：`AIRelayPlugin.kt` 中的 `/usage` 端点

**新增查询参数**：
```kotlin
get<UsageSnapshot>(
    "/usage",
    doc = OpenApiDoc(summary = "查询 usage 记录", tags = listOf("ai-gateway", "airelay"))
) {
    val limit = queryParameters["limit"]?.firstOrNull()?.toIntOrNull() ?: 200
    val groupId = queryParameters["groupId"]?.firstOrNull()
    val channelId = queryParameters["channelId"]?.firstOrNull()
    val model = queryParameters["model"]?.firstOrNull()
    val status = queryParameters["status"]?.firstOrNull()
    
    val snapshot = usageRecorder.snapshot(limit, groupId, channelId, model, status)
    PluginResult(body = snapshot)
}
```

**修改 UsageRecorder**：
```kotlin
fun snapshot(
    limit: Int = 200,
    groupId: String? = null,
    channelId: String? = null,
    model: String? = null,
    status: String? = null
): UsageSnapshot {
    val whereClauses = mutableListOf<String>()
    val params = mutableListOf<Any>()
    
    groupId?.let {
        whereClauses.add("poolLevelId = ?")
        params.add(it)
    }
    channelId?.let {
        whereClauses.add("upstreamKeyId = ?")
        params.add(it)
    }
    model?.let {
        whereClauses.add("model = ?")
        params.add(it)
    }
    status?.let {
        when (it) {
            "success" -> whereClauses.add("status >= 200 AND status < 300")
            "error" -> whereClauses.add("status >= 400")
            else -> {}
        }
    }
    
    val whereClause = if (whereClauses.isNotEmpty()) 
        "WHERE " + whereClauses.joinToString(" AND ") 
    else ""
    
    val records = database.query(
        "SELECT * FROM usage $whereClause ORDER BY createdAt DESC LIMIT ?",
        *params.toTypedArray(), limit
    )
    // ...
}
```

**验证**：
```bash
curl -s "http://localhost:8080/api/airelay/usage?groupId=production&status=error" | jq '.records | length'
```

---

### Task 2.5：修改 Channel Test 逻辑记录历史

**目标**：每次测试 Channel 时，自动记录到 `channel_test_history` 表。

**修改文件**：`AIRelayPlugin.kt`

**在 `testChannel()` 方法中新增**：
```kotlin
private suspend fun testChannel(channel: ChannelView): ChannelTestResponse {
    val repo = channelRepository ?: return ChannelTestResponse(false, null, "Channel store unavailable")
    // ... 现有逻辑
    val result = RealUpstreamHttpClient.create().pingChannel(...)
    
    // 记录到历史表
    repo.recordTestHistory(
        channelId = channel.channelId,
        success = result.ok,
        latencyMs = result.latencyMs,
        error = result.error
    )
    
    repo.recordTestResult(channel.channelId, result.latencyMs, result.error)
    return ChannelTestResponse(...)
}
```

**验证**：测试 Channel 后查询历史表，确认有新记录。

---

## 阶段 3：前端核心功能实现

### Task 3.1：修复缓存命中率显示 Bug

**目标**：确保缓存命中率正确计算和显示。

**方案 A：后端计算（推荐）**

修改 `CostCalculator.kt`，在 `calculate()` 方法中：
```kotlin
fun calculate(...): CostBreakdown {
    // ... 现有成本计算
    
    val cacheHitRate = if (usage.promptTokens + usage.cacheReadInputTokens > 0) {
        usage.cacheReadInputTokens.toDouble() / (usage.promptTokens + usage.cacheReadInputTokens)
    } else null
    
    return CostBreakdown(
        inputCostUsd = inputCost,
        outputCostUsd = outputCost,
        cacheWriteCostUsd = cacheWriteCost,
        cacheReadCostUsd = cacheReadCost,
        totalCostUsd = totalCost,
        cacheHitRate = cacheHitRate  // 确保填充此字段
    )
}
```

**方案 B：前端计算（备选）**

如果后端无法修改，在 `UsagePanel.tsx` 中：
```tsx
const calculateCacheHitRate = (r: UsageRecord): number | null => {
  const prompt = r.usage?.promptTokens ?? 0;
  const cacheRead = r.usage?.cacheReadInputTokens ?? 0;
  if (prompt + cacheRead === 0) return null;
  return cacheRead / (prompt + cacheRead);
};
```

**验证**：
1. 发送带缓存的请求（system 里设置 `cache_control`）
2. 查看 Usage 面板，缓存命中率应显示非零值（如 32.4%）
3. 检查 Dashboard 概览卡片的全局缓存命中率

---

### Task 3.2：Usage 面板增强

**目标**：新增 group/channel 列、筛选器、详情 Drawer。

**修改文件**：`frontend/apps/ai-gateway/src/panels/UsagePanel.tsx`

#### 3.2.1 新增列定义
```tsx
const columns: DataTableColumn<UsageRecord>[] = [
  { key: 'requestId', header: 'Request', mono: true, render: (r) => r.requestId?.slice(0, 16) ?? '—' },
  { key: 'createdAt', header: 'Timestamp', render: (r) => r.createdAt?.replace('T', ' ').slice(0, 19) ?? '—' },
  { key: 'groupId', header: 'Group', render: (r) => r.groupId ?? '—' },  // 新增
  { key: 'channelName', header: 'Channel', render: (r) => r.channelName ?? r.channelId ?? '—' },  // 新增
  { key: 'model', header: 'Model', render: (r) => renderModelRoute(r.model) },
  { 
    key: 'cache', 
    header: 'Cache', 
    render: (r) => {
      const rate = r.cost?.cacheHitRate ?? r.cacheHitRate;
      return rate != null ? `${(rate * 100).toFixed(1)}%` : '—';
    }
  },  // 新增
  // ... 其他现有列
];
```

#### 3.2.2 新增筛选器
```tsx
export function UsagePanel({ api }: { api: AiGatewayApi }) {
  const [rows, setRows] = useState<UsageRecord[]>([]);
  const [groups, setGroups] = useState<string[]>([]);
  const [channels, setChannels] = useState<Array<{id: string, name: string}>>([]);
  const [filters, setFilters] = useState({
    groupId: null as string | null,
    channelId: null as string | null,
    model: null as string | null,
    status: null as string | null
  });

  useEffect(() => {
    // 加载 groups 和 channels 列表
    Promise.all([
      api.groups(),
      api.channels()
    ]).then(([groupsData, channelsData]) => {
      setGroups((groupsData as any).groups?.map((g: any) => g.groupId) ?? []);
      setChannels((channelsData as any).channels?.map((c: any) => ({
        id: c.channelId,
        name: c.name
      })) ?? []);
    });
  }, [api]);

  useEffect(() => {
    const params = new URLSearchParams();
    if (filters.groupId) params.append('groupId', filters.groupId);
    if (filters.channelId) params.append('channelId', filters.channelId);
    if (filters.model) params.append('model', filters.model);
    if (filters.status) params.append('status', filters.status);
    
    api.usageRecords(200, params.toString())
      .then((data) => setRows(data.records ?? []))
      .catch((err) => setError(err.message));
  }, [api, filters]);

  return (
    <>
      <PageHeader title="Usage" description="调用记录" />
      
      {/* 筛选器 */}
      <div className="keel-filters">
        <select onChange={(e) => setFilters({...filters, groupId: e.target.value || null})}>
          <option value="">All Groups</option>
          {groups.map(g => <option key={g} value={g}>{g}</option>)}
        </select>
        
        <select onChange={(e) => setFilters({...filters, channelId: e.target.value || null})}>
          <option value="">All Channels</option>
          {channels.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select>
        
        <select onChange={(e) => setFilters({...filters, status: e.target.value || null})}>
          <option value="">All Status</option>
          <option value="success">Success</option>
          <option value="error">Error</option>
        </select>
      </div>
      
      <DataTable columns={columns} data={rows} onRowClick={handleRowClick} />
    </>
  );
}
```

#### 3.2.3 详情 Drawer
```tsx
const [selectedRecord, setSelectedRecord] = useState<UsageRecord | null>(null);

const handleRowClick = (record: UsageRecord) => {
  setSelectedRecord(record);
};

return (
  <>
    {/* ... 现有内容 */}
    
    {selectedRecord && (
      <Drawer open={true} onClose={() => setSelectedRecord(null)} title="请求详情">
        <div className="usage-detail">
          <section>
            <h4>基本信息</h4>
            <KeyValue label="Request ID">{selectedRecord.requestId}</KeyValue>
            <KeyValue label="Timestamp">{selectedRecord.createdAt}</KeyValue>
            <KeyValue label="Model">{selectedRecord.model}</KeyValue>
            <KeyValue label="Group">{selectedRecord.groupId ?? '—'}</KeyValue>
            <KeyValue label="Channel">{selectedRecord.channelName ?? '—'}</KeyValue>
          </section>
          
          <section>
            <h4>Token 详情</h4>
            <KeyValue label="Input">{selectedRecord.usage?.promptTokens ?? 0} tokens</KeyValue>
            <KeyValue label="Output">{selectedRecord.usage?.completionTokens ?? 0} tokens</KeyValue>
            <KeyValue label="Cache Write">{selectedRecord.usage?.cacheCreationInputTokens ?? 0} tokens</KeyValue>
            <KeyValue label="Cache Read">{selectedRecord.usage?.cacheReadInputTokens ?? 0} tokens</KeyValue>
            <KeyValue label="Cache Hit Rate">
              {selectedRecord.cacheHitRate != null 
                ? `${(selectedRecord.cacheHitRate * 100).toFixed(1)}%` 
                : '—'}
            </KeyValue>
          </section>
          
          <section>
            <h4>成本详情</h4>
            <KeyValue label="Input Cost">${selectedRecord.cost?.inputCostUsd?.toFixed(4) ?? '0.0000'}</KeyValue>
            <KeyValue label="Output Cost">${selectedRecord.cost?.outputCostUsd?.toFixed(4) ?? '0.0000'}</KeyValue>
            <KeyValue label="Cache Write">${selectedRecord.cost?.cacheWriteCostUsd?.toFixed(4) ?? '0.0000'}</KeyValue>
            <KeyValue label="Cache Read">${selectedRecord.cost?.cacheReadCostUsd?.toFixed(4) ?? '0.0000'}</KeyValue>
            <KeyValue label="Total">${selectedRecord.cost?.totalCostUsd?.toFixed(4) ?? '0.0000'}</KeyValue>
          </section>
          
          <section>
            <h4>性能</h4>
            <KeyValue label="延迟">{selectedRecord.latencyMs ?? 0} ms</KeyValue>
            <KeyValue label="Failover">{selectedRecord.failoverCount ?? 0} 次</KeyValue>
            <KeyValue label="Status">{selectedRecord.status} ({selectedRecord.outcome})</KeyValue>
          </section>
          
          {selectedRecord.errorCode && (
            <section>
              <h4>错误信息</h4>
              <KeyValue label="Error Code">{selectedRecord.errorCode}</KeyValue>
              <KeyValue label="Detail">{selectedRecord.errorDetail ?? '—'}</KeyValue>
            </section>
          )}
        </div>
      </Drawer>
    )}
  </>
);
```

**验证**：
1. 打开 Usage 面板，应看到 Group / Channel / Cache 列
2. 使用筛选器，记录应动态过滤
3. 点击任意记录，右侧应滑出详情面板

---

### Task 3.3：Providers 面板增强（Channel 卡片）

**目标**：在 Channel 卡片中显示可用率、微型图、调用统计。

**修改文件**：`frontend/apps/ai-gateway/src/panels/ProvidersPanel.tsx`

#### 3.3.1 扩展数据模型
```tsx
interface ChannelWithStats extends Channel {
  stats?: {
    successRate7d: number;
    recentTests: TestRecord[];
    requests24h: number;
    avgLatencyMs: number;
    totalCostUsd: number;
    totalTokens: number;
  };
}

interface TestRecord {
  timestamp: number;
  ok: boolean;
  latencyMs: number | null;
}
```

#### 3.3.2 加载统计数据
```tsx
useEffect(() => {
  let cancelled = false;
  
  api.channels().then(async (data) => {
    if (cancelled) return;
    const channelList = (data as { channels?: Channel[] }).channels ?? [];
    
    // 并行加载每个 channel 的统计
    const channelsWithStats = await Promise.all(
      channelList.map(async (ch) => {
        try {
          const stats = await api.channelStats(ch.channelId!, '7d');
          return { ...ch, stats };
        } catch {
          return ch;  // 如果统计加载失败，仍返回基础信息
        }
      })
    );
    
    setChannels(channelsWithStats);
  });
  
  return () => { cancelled = true; };
}, [api]);
```

#### 3.3.3 渲染增强卡片
```tsx
<article key={ch.channelId} className="keel-list-card">
  <div className="keel-list-card__header">
    <h3>{ch.name ?? '—'}</h3>
    <Chip tone={statusTone(ch.status)}>{ch.status ?? 'healthy'}</Chip>
  </div>
  
  <div className="keel-list-card__body">
    <KeyValue label="Protocol">{ch.protocol ?? '—'}</KeyValue>
    <KeyValue label="Base URL">{ch.baseUrl ?? '—'}</KeyValue>
    
    {/* 可用率区域 */}
    {ch.stats && (
      <>
        <KeyValue label="可用率 · 7天">
          <span style={{ 
            color: ch.stats.successRate7d >= 0.95 ? 'var(--keel-success)' : 
                   ch.stats.successRate7d >= 0.80 ? 'var(--keel-warning)' : 
                   'var(--keel-danger)',
            fontWeight: 'bold'
          }}>
            {(ch.stats.successRate7d * 100).toFixed(2)}%
          </span>
        </KeyValue>
        
        {/* 近60次测试微型图 */}
        <div className="channel-test-sparkline">
          <div className="sparkline-label">近 60 次记录</div>
          <div className="sparkline-bars">
            {ch.stats.recentTests.slice(0, 60).reverse().map((test, i) => (
              <div
                key={i}
                className="sparkline-bar"
                style={{
                  backgroundColor: test.ok ? 'var(--keel-success)' : 'var(--keel-danger)',
                  height: '16px',
                  width: '3px',
                  margin: '0 1px'
                }}
                title={`${new Date(test.timestamp).toLocaleString()}: ${test.ok ? 'OK' : 'FAIL'} (${test.latencyMs ?? 0}ms)`}
              />
            ))}
          </div>
        </div>
        
        {/* 调用统计 */}
        <KeyValue label="调用统计 · 24h">
          <div>
            {ch.stats.requests24h.toLocaleString()} 请求 · 平均 {ch.stats.avgLatencyMs}ms
          </div>
          <div style={{ fontSize: '12px', color: 'var(--keel-muted)' }}>
            成本: ${ch.stats.totalCostUsd.toFixed(2)} · Token: {(ch.stats.totalTokens / 1_000_000).toFixed(1)}M
          </div>
        </KeyValue>
      </>
    )}
  </div>
</article>
```

**CSS 样式**：
```css
/* frontend/apps/ai-gateway/src/styles/channel-card.css */
.channel-test-sparkline {
  margin: 8px 0;
}

.sparkline-label {
  font-size: 12px;
  color: var(--keel-muted);
  margin-bottom: 4px;
}

.sparkline-bars {
  display: flex;
  align-items: flex-end;
  height: 20px;
  gap: 1px;
}
```

**验证**：
1. Providers 面板应显示每个 Channel 的可用率百分比
2. 微型图应显示绿/红竖条（最近60次测试）
3. 鼠标悬停在竖条上应显示时间和延迟

---

## 阶段 4：Dashboard 面板开发

### Task 4.1：创建 Dashboard 面板文件

**新建文件**：`frontend/apps/ai-gateway/src/panels/DashboardPanel.tsx`

**基础结构**：
```tsx
import { useEffect, useState } from 'react';
import { PageHeader, ErrorBanner, Card, Chip } from '@keel/sample-ui';
import { LineChart, Line, BarChart, Bar, PieChart, Pie, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts';
import type { AiGatewayApi } from '../api/aiGatewayApi';

interface DashboardStats {
  window: string;
  overview: OverviewStats;
  trends: TrendsData;
  distributions: DistributionsData;
}

export function DashboardPanel({ api }: { api: AiGatewayApi }) {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [window, setWindow] = useState('24h');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    
    api.dashboardStats(window)
      .then((data) => {
        if (cancelled) return;
        setStats(data as DashboardStats);
        setLoading(false);
      })
      .catch((err) => {
        if (!cancelled) {
          setError(err.message);
          setLoading(false);
        }
      });
    
    return () => { cancelled = true; };
  }, [api, window]);

  if (loading) return <div>加载中...</div>;
  if (error) return <ErrorBanner message={error} />;
  if (!stats) return <div>无数据</div>;

  return (
    <>
      <PageHeader 
        title="Dashboard" 
        description="运维监控总览"
        actions={
          <select value={window} onChange={(e) => setWindow(e.target.value)}>
            <option value="1h">最近1小时</option>
            <option value="24h">最近24小时</option>
            <option value="7d">最近7天</option>
            <option value="30d">最近30天</option>
          </select>
        }
      />
      
      <div className="dashboard-container">
        {/* 概览卡片区 */}
        <OverviewCards overview={stats.overview} />
        
        {/* 趋势图区 */}
        <TrendsSection trends={stats.trends} window={window} />
        
        {/* 分布分析区 */}
        <DistributionsSection distributions={stats.distributions} />
      </div>
    </>
  );
}
```

---

### Task 4.2：实现概览卡片组件

**在 DashboardPanel.tsx 中新增**：
```tsx
function OverviewCards({ overview }: { overview: OverviewStats }) {
  return (
    <div className="overview-cards">
      {/* 卡片1：总请求数与成功率 */}
      <Card>
        <div className="metric-card">
          <div className="metric-label">总请求数</div>
          <div className="metric-value">{overview.totalRequests.toLocaleString()}</div>
          <div className="metric-detail">
            成功率 <span className={successRateClass(overview.successRate)}>
              {(overview.successRate * 100).toFixed(1)}%
            </span>
          </div>
        </div>
      </Card>
      
      {/* 卡片2：成本与Token */}
      <Card>
        <div className="metric-card">
          <div className="metric-label">总成本</div>
          <div className="metric-value">${overview.totalCostUsd.toFixed(2)}</div>
          <div className="metric-detail">
            Token: {(overview.totalTokens / 1_000_000).toFixed(1)}M
            <br />
            缓存命中率: {(overview.cacheHitRate * 100).toFixed(1)}%
          </div>
        </div>
      </Card>
      
      {/* 卡片3：延迟 */}
      <Card>
        <div className="metric-card">
          <div className="metric-label">平均延迟</div>
          <div className="metric-value">{overview.avgLatencyMs.toLocaleString()} ms</div>
          <div className="metric-detail">
            P50: {overview.p50LatencyMs}ms · P95: {overview.p95LatencyMs}ms
            <br />
            P99: {overview.p99LatencyMs}ms
          </div>
        </div>
      </Card>
      
      {/* 卡片4：Channel 健康度 */}
      <Card>
        <div className="metric-card">
          <div className="metric-label">Channel 健康度</div>
          <div className="metric-value">
            {overview.healthyChannels} / {overview.healthyChannels + overview.degradedChannels + overview.disabledChannels}
          </div>
          <div className="metric-detail">
            <Chip tone="ok">{overview.healthyChannels} 健康</Chip>
            {overview.degradedChannels > 0 && <Chip tone="warn">{overview.degradedChannels} 降级</Chip>}
            {overview.disabledChannels > 0 && <Chip tone="danger">{overview.disabledChannels} 禁用</Chip>}
          </div>
        </div>
      </Card>
    </div>
  );
}

function successRateClass(rate: number): string {
  if (rate >= 0.99) return 'success';
  if (rate >= 0.95) return 'warning';
  return 'danger';
}
```

---

### Task 4.3：实现趋势图组件

```tsx
function TrendsSection({ trends, window }: { trends: TrendsData; window: string }) {
  return (
    <div className="trends-section">
      {/* 图表1：请求量趋势 */}
      <Card title="请求量趋势">
        <ResponsiveContainer width="100%" height={300}>
          <LineChart data={trends.requestTrend}>
            <XAxis 
              dataKey="timestamp" 
              tickFormatter={(ts) => formatTimestamp(ts, window)} 
            />
            <YAxis />
            <Tooltip labelFormatter={(ts) => new Date(ts).toLocaleString()} />
            <Line type="monotone" dataKey="value" stroke="#3b82f6" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      </Card>
      
      {/* 图表2：Token 与成本趋势 */}
      <Card title="Token 与成本趋势">
        <ResponsiveContainer width="100%" height={300}>
          <LineChart data={trends.tokenTrend}>
            <XAxis dataKey="timestamp" tickFormatter={(ts) => formatTimestamp(ts, window)} />
            <YAxis yAxisId="left" />
            <YAxis yAxisId="right" orientation="right" />
            <Tooltip labelFormatter={(ts) => new Date(ts).toLocaleString()} />
            <Line yAxisId="left" type="monotone" dataKey="tokens" stroke="#10b981" name="Tokens" />
            <Line yAxisId="right" type="monotone" dataKey="cost" stroke="#ef4444" name="Cost ($)" />
          </LineChart>
        </ResponsiveContainer>
      </Card>
      
      {/* 图表3：延迟分布趋势 */}
      <Card title="延迟百分位趋势">
        <ResponsiveContainer width="100%" height={300}>
          <LineChart>
            <XAxis dataKey="timestamp" tickFormatter={(ts) => formatTimestamp(ts, window)} />
            <YAxis />
            <Tooltip labelFormatter={(ts) => new Date(ts).toLocaleString()} />
            <Line data={trends.latencyTrend.p50} type="monotone" dataKey="value" stroke="#3b82f6" name="P50" />
            <Line data={trends.latencyTrend.p95} type="monotone" dataKey="value" stroke="#f59e0b" name="P95" />
            <Line data={trends.latencyTrend.p99} type="monotone" dataKey="value" stroke="#ef4444" name="P99" />
          </LineChart>
        </ResponsiveContainer>
      </Card>
    </div>
  );
}

function formatTimestamp(ts: number, window: string): string {
  const date = new Date(ts);
  switch (window) {
    case '1h': return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    case '24h': return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' });
    case '7d': return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
    case '30d': return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' });
    default: return date.toLocaleTimeString();
  }
}
```

---

### Task 4.4：实现分布分析组件

```tsx
function DistributionsSection({ distributions }: { distributions: DistributionsData }) {
  return (
    <div className="distributions-section">
      {/* 图表4：模型调用分布 */}
      <Card title="模型调用分布">
        <ResponsiveContainer width="100%" height={300}>
          <PieChart>
            <Pie
              data={distributions.modelDistribution}
              dataKey="requests"
              nameKey="model"
              cx="50%"
              cy="50%"
              outerRadius={80}
              fill="#3b82f6"
              label={(entry) => `${entry.model}: ${entry.percentage.toFixed(1)}%`}
            />
            <Tooltip formatter={(value: number) => value.toLocaleString()} />
          </PieChart>
        </ResponsiveContainer>
      </Card>
      
      {/* 图表5：Channel 调用分布 */}
      <Card title="Channel 调用分布">
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={distributions.channelDistribution} layout="vertical">
            <XAxis type="number" />
            <YAxis type="category" dataKey="channelName" width={150} />
            <Tooltip />
            <Bar dataKey="requests" fill="#10b981" />
          </BarChart>
        </ResponsiveContainer>
      </Card>
      
      {/* 图表6：Group 调用分布 */}
      <Card title="Group 调用分布">
        <ResponsiveContainer width="100%" height={300}>
          <BarChart data={distributions.groupDistribution}>
            <XAxis dataKey="groupId" />
            <YAxis />
            <Tooltip />
            <Bar dataKey="requests" fill="#8b5cf6" />
          </BarChart>
        </ResponsiveContainer>
      </Card>
      
      {/* 图表7：错误类型分布 */}
      <Card title="错误类型分布">
        <ResponsiveContainer width="100%" height={300}>
          <PieChart>
            <Pie
              data={distributions.errorDistribution}
              dataKey="count"
              nameKey="errorType"
              cx="50%"
              cy="50%"
              outerRadius={80}
              fill="#ef4444"
              label={(entry) => `${entry.errorType}: ${entry.percentage.toFixed(1)}%`}
            />
            <Tooltip />
          </PieChart>
        </ResponsiveContainer>
      </Card>
    </div>
  );
}
```

---

### Task 4.5：添加 Dashboard 样式

**新建文件**：`frontend/apps/ai-gateway/src/styles/dashboard.css`

```css
.dashboard-container {
  display: grid;
  gap: 24px;
}

.overview-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 16px;
}

.metric-card {
  padding: 16px;
}

.metric-label {
  font-size: 14px;
  color: var(--keel-muted);
  margin-bottom: 8px;
}

.metric-value {
  font-size: 32px;
  font-weight: bold;
  margin-bottom: 8px;
}

.metric-detail {
  font-size: 13px;
  color: var(--keel-muted);
  line-height: 1.6;
}

.metric-detail .success {
  color: var(--keel-success);
  font-weight: bold;
}

.metric-detail .warning {
  color: var(--keel-warning);
  font-weight: bold;
}

.metric-detail .danger {
  color: var(--keel-danger);
  font-weight: bold;
}

.trends-section {
  display: grid;
  gap: 16px;
}

.distributions-section {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(400px, 1fr));
  gap: 16px;
}

@media (max-width: 768px) {
  .overview-cards {
    grid-template-columns: 1fr;
  }
  
  .distributions-section {
    grid-template-columns: 1fr;
  }
}
```

**在 App.tsx 中引入**：
```tsx
import './styles/dashboard.css';
```

---

### Task 4.6：注册 Dashboard 路由

**修改文件**：`frontend/apps/ai-gateway/src/App.tsx`

```tsx
import { DashboardPanel } from './panels/DashboardPanel';

// 在路由配置中新增
const routes = [
  { path: '/dashboard', component: DashboardPanel, label: 'Dashboard' },  // 新增
  { path: '/usage', component: UsagePanel, label: 'Usage' },
  { path: '/providers', component: ProvidersPanel, label: 'Providers' },
  // ... 其他路由
];
```

**验证**：
1. 访问 `/dashboard` 路由
2. 应看到 4 个概览卡片、3 个趋势图、4 个分布图
3. 切换时间窗口（1h/24h/7d/30d），数据应更新

---

## 阶段 5：测试与优化

### Task 5.1：端到端功能测试

**测试清单**：

#### 5.1.1 缓存命中率测试
```bash
# 1. 发送带缓存的请求
curl -X POST http://localhost:8080/api/airelay/v1/messages \
  -H "Authorization: Bearer test-key" \
  -H "Content-Type: application/json" \
  -d '{
    "model": "claude-opus-4-8",
    "max_tokens": 100,
    "messages": [{"role": "user", "content": "Hello"}],
    "system": [{
      "type": "text",
      "text": "You are a helpful assistant.",
      "cache_control": {"type": "ephemeral"}
    }]
  }'

# 2. 再次发送相同请求（应触发 cache read）
# 重复上述请求

# 3. 检查 Usage 面板
# ✅ Cache 列应显示非零百分比（如 45.2%）
# ✅ Dashboard 概览卡片的全局缓存命中率应更新
```

#### 5.1.2 Usage 筛选测试
```bash
# 访问 Usage 面板
# ✅ 应看到 Group / Channel / Cache 列
# ✅ 使用筛选器选择特定 Group，记录应过滤
# ✅ 点击任意记录，右侧应滑出详情 Drawer
# ✅ Drawer 中应显示完整的 Token / Cost / 性能信息
```

#### 5.1.3 Channel 可用率测试
```bash
# 1. 测试 Channel 多次
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/airelay/admin/channels/{channelId}/test
  sleep 2
done

# 2. 访问 Providers 面板
# ✅ Channel 卡片应显示可用率（如 90.5%）
# ✅ 微型图应显示 10 个绿色/红色竖条
# ✅ 调用统计应显示最近 24h 的请求数和延迟
```

#### 5.1.4 Dashboard 数据一致性测试
```bash
# 访问 Dashboard
# ✅ 概览卡片的总请求数应与 Usage 面板的记录数一致
# ✅ 成功率应与 Usage 中成功/失败比例一致
# ✅ 趋势图应无断点或异常峰值
# ✅ 分布图的百分比总和应为 100%
```

---

### Task 5.2：性能优化

#### 5.2.1 Dashboard API 响应时间优化

**问题**：7天窗口可能有数十万条 usage 记录，聚合查询慢。

**方案**：实现增量聚合缓存

**新建表**：
```sql
CREATE TABLE dashboard_stats_cache (
    cache_key TEXT PRIMARY KEY,
    window_start INTEGER NOT NULL,
    window_end INTEGER NOT NULL,
    stats_json TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
```

**缓存策略**：
```kotlin
private val dashboardCache = ConcurrentHashMap<String, CachedStats>()

data class CachedStats(
    val stats: DashboardStatsResponse,
    val cachedAt: Long,
    val ttl: Long = 60_000  // 1分钟
)

private suspend fun calculateDashboardStats(window: String): DashboardStatsResponse {
    val cacheKey = "dashboard:$window"
    val cached = dashboardCache[cacheKey]
    
    // 如果缓存有效，直接返回
    if (cached != null && System.currentTimeMillis() - cached.cachedAt < cached.ttl) {
        return cached.stats
    }
    
    // 重新计算
    val stats = doCalculateDashboardStats(window)
    dashboardCache[cacheKey] = CachedStats(stats, System.currentTimeMillis())
    return stats
}
```

**验证**：
```bash
# 第一次请求（冷启动）
time curl -s http://localhost:8080/api/airelay/admin/stats/dashboard?window=7d
# 应在 500-1000ms 内返回

# 第二次请求（命中缓存）
time curl -s http://localhost:8080/api/airelay/admin/stats/dashboard?window=7d
# 应在 50ms 内返回
```

#### 5.2.2 百分位延迟计算优化

**问题**：对大量记录排序计算 P95/P99 耗时。

**方案**：使用采样或 SQL 窗口函数

```kotlin
// 方案 A：采样（数据量 > 10000 时）
private fun calculatePercentiles(latencies: List<Long>): LatencyPercentiles {
    val sampled = if (latencies.size > 10_000) {
        latencies.shuffled().take(10_000)
    } else {
        latencies
    }
    val sorted = sampled.sorted()
    return LatencyPercentiles(
        p50 = sorted.percentile(0.50),
        p95 = sorted.percentile(0.95),
        p99 = sorted.percentile(0.99)
    )
}

// 方案 B：SQL 直接计算（H2 支持 PERCENTILE_CONT）
val stats = database.query("""
    SELECT 
        PERCENTILE_CONT(0.5) WITHIN GROUP (ORDER BY latencyMs) as p50,
        PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY latencyMs) as p95,
        PERCENTILE_CONT(0.99) WITHIN GROUP (ORDER BY latencyMs) as p99
    FROM usage
    WHERE createdAt >= ?
""", startTime).first()
```

#### 5.2.3 前端数据加载优化

**问题**：Dashboard 初始加载需要请求多个 API。

**方案**：骨架屏 + 并行加载

```tsx
function DashboardPanel({ api }: { api: AiGatewayApi }) {
  const [stats, setStats] = useState<DashboardStats | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // 并行加载所有数据
    Promise.all([
      api.dashboardStats(window),
      api.poolHealth()  // 如果需要额外数据
    ]).then(([dashboardData, healthData]) => {
      setStats(mergeData(dashboardData, healthData));
      setLoading(false);
    });
  }, [window]);

  if (loading) {
    return <DashboardSkeleton />;  // 显示骨架屏
  }
  
  // ... 正常渲染
}

function DashboardSkeleton() {
  return (
    <div className="dashboard-skeleton">
      {[1, 2, 3, 4].map(i => (
        <div key={i} className="skeleton-card" />
      ))}
    </div>
  );
}
```

---

### Task 5.3：错误处理增强

#### 5.3.1 后端 API 错误处理
```kotlin
get<ChannelStatsResponse>(
    "/admin/channels/{channelId}/stats",
    // ...
) {
    try {
        val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
        val window = queryParameters["window"]?.firstOrNull() ?: "7d"
        
        // 验证窗口参数
        if (window !in listOf("1h", "24h", "7d", "30d")) {
            throw PluginApiException(400, "Invalid window. Must be one of: 1h, 24h, 7d, 30d")
        }
        
        val stats = calculateChannelStats(channelRepository!!, channelId, window)
        PluginResult(body = stats)
    } catch (e: Exception) {
        System.err.println("Error calculating channel stats: ${e.message}")
        throw PluginApiException(500, "Failed to calculate stats: ${e.message}")
    }
}
```

#### 5.3.2 前端错误边界
```tsx
// 在 DashboardPanel 中新增
const [error, setError] = useState<string | null>(null);

useEffect(() => {
  api.dashboardStats(window)
    .then(setStats)
    .catch((err) => {
      console.error('Dashboard stats error:', err);
      setError(err.message || '加载失败，请稍后重试');
    });
}, [window]);

if (error) {
  return (
    <div className="error-container">
      <ErrorBanner message={error} />
      <button onClick={() => window.location.reload()}>重新加载</button>
    </div>
  );
}
```

---

### Task 5.4：文档完善

#### 5.4.1 用户使用文档

**新建文件**：`keel-samples/docs/ai-gateway-user-guide.md`

内容：
- Dashboard 各指标的含义
- 如何解读缓存命中率
- 如何使用 Usage 筛选器定位问题
- Channel 可用率低于 90% 时的排查步骤
- 百分位延迟的含义和阈值建议

#### 5.4.2 API 文档

**新建文件**：`keel-samples/docs/ai-gateway-api-reference.md`

内容：
- 所有新增 API 端点的详细说明
- 请求/响应示例
- 查询参数说明
- 错误码列表

#### 5.4.3 运维手册

**新建文件**：`keel-samples/docs/ai-gateway-ops-guide.md`

内容：
- 性能调优建议
- 缓存策略配置
- 数据库索引优化
- 告警阈值设置建议

---

### Task 5.5：自动化测试（可选）

#### 5.5.1 后端单元测试

**新建文件**：`keel-samples/src/test/kotlin/com/keel/samples/aigateway/airelay/ChannelStatsTest.kt`

```kotlin
class ChannelStatsTest {
    @Test
    fun `should calculate success rate correctly`() {
        // 准备测试数据
        val repo = mockChannelRepository()
        repo.insertUsageRecord(channelId = "ch-1", status = 200)
        repo.insertUsageRecord(channelId = "ch-1", status = 200)
        repo.insertUsageRecord(channelId = "ch-1", status = 500)
        
        // 执行计算
        val stats = calculateChannelStats(repo, "ch-1", "7d")
        
        // 验证
        assertEquals(3, stats.totalRequests)
        assertEquals(2, stats.successRequests)
        assertEquals(0.67, stats.successRate, 0.01)
    }
    
    @Test
    fun `should calculate percentiles correctly`() {
        val latencies = listOf(100L, 200L, 300L, 400L, 500L, 1000L, 2000L, 5000L, 10000L)
        val p50 = latencies.percentile(0.50)
        val p95 = latencies.percentile(0.95)
        val p99 = latencies.percentile(0.99)
        
        assertTrue(p50 in 400L..600L)
        assertTrue(p95 in 5000L..10000L)
        assertTrue(p99 >= 9000L)
    }
}
```

#### 5.5.2 前端集成测试

**新建文件**：`frontend/apps/ai-gateway/src/panels/DashboardPanel.test.tsx`

```tsx
import { render, screen, waitFor } from '@testing-library/react';
import { DashboardPanel } from './DashboardPanel';

describe('DashboardPanel', () => {
  it('should render overview cards', async () => {
    const mockApi = {
      dashboardStats: jest.fn().mockResolvedValue({
        overview: {
          totalRequests: 1234,
          successRate: 0.987,
          totalCostUsd: 56.78,
          totalTokens: 1_000_000
        }
      })
    };
    
    render(<DashboardPanel api={mockApi} />);
    
    await waitFor(() => {
      expect(screen.getByText('1,234')).toBeInTheDocument();
      expect(screen.getByText('98.7%')).toBeInTheDocument();
    });
  });
});
```

---

## 总结与检查清单

### 功能完成度检查

- [ ] **缓存命中率 Bug 修复**
  - [ ] 后端正确记录 `cacheReadInputTokens` / `cacheCreationInputTokens`
  - [ ] `CostBreakdown.cacheHitRate` 正确计算
  - [ ] Usage 面板显示缓存列
  - [ ] Dashboard 显示全局缓存命中率

- [ ] **Usage 面板增强**
  - [ ] 新增 Group / Channel / Cache 列
  - [ ] 实现筛选器（Group / Channel / Model / Status）
  - [ ] 点击记录展开详情 Drawer
  - [ ] Drawer 显示完整 Token / Cost / 性能信息

- [ ] **Providers 面板增强**
  - [ ] Channel 卡片显示可用率（7天）
  - [ ] 显示近60次测试微型图
  - [ ] 显示 24h 调用统计

- [ ] **Dashboard 面板**
  - [ ] 4 个概览卡片（请求、成本、延迟、健康度）
  - [ ] 3 个趋势图（请求、Token/成本、延迟）
  - [ ] 4 个分布图（模型、Channel、Group、错误）
  - [ ] 时间窗口切换功能

- [ ] **后端 API**
  - [ ] `/admin/channels/{id}/stats` 端点
  - [ ] `/admin/stats/dashboard` 端点
  - [ ] `/usage` 增加查询参数
  - [ ] `channel_test_history` 表创建和记录

- [ ] **性能优化**
  - [ ] Dashboard 统计缓存（1分钟）
  - [ ] 百分位计算优化
  - [ ] 前端并行加载

- [ ] **文档**
  - [ ] 设计文档（已完成）
  - [ ] 实施计划（已完成）
  - [ ] 用户使用指南
  - [ ] API 参考文档
  - [ ] 运维手册

### 验收标准

1. **功能正确性**：所有功能按设计文档要求实现
2. **数据准确性**：Dashboard 数据与原始 usage 记录一致
3. **性能要求**：Dashboard 加载时间 < 2s（7天窗口）
4. **用户体验**：所有交互流畅，无明显卡顿
5. **错误处理**：所有异常场景有友好提示

### 后续改进方向

- 实时数据推送（SSE）
- 告警规则配置（可用率低于阈值自动告警）
- 报告导出（CSV / PDF）
- 高级分析（Failover 分析、并发分析）
- 成本优化建议（基于 AI 的智能推荐）

---

**文档版本**：v1.0  
**最后更新**：2026-06-17  
**作者**：Kiro AI Assistant
