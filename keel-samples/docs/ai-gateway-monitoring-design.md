# AI Gateway 监控与运维面板设计文档

## 一、核心问题清单

### 功能缺失
1. Channel/Group 调用图表（时序图、调用量分布）
2. Channel 可用率监控卡片（7天窗口，近60次测试记录的成功率）
3. Usage 面板缺少 group/channel 列，无法按 group/channel 筛选
4. Usage 记录点击无详情面板（需展示完整请求/响应数据）
5. 缺少专用运维面板（Dashboard）
6. Channel 卡片缺少调用统计和可用率指标

### Bug 修复
7. 缓存命中率显示 0%（需检查 `cacheReadInputTokens` 是否正确记录和计算）

---

## 二、Dashboard 运维面板完整设计

### 设计原则
- **一屏总览**：核心指标无需滚动即可看到
- **三层深度**：概览 → 趋势 → 详细分布
- **异常突出**：错误率、可用率低、延迟高的内容用色彩警示
- **可操作**：点击图表/卡片可跳转到对应的详细面板

---

### 2.1 顶部概览卡片区（4个核心指标卡片）

#### 卡片 1：总请求数与成功率
```
┌─────────────────────────┐
│ 总请求数                 │
│ 12,458                  │
│ 成功率 98.7%   ↑ 0.3%   │ ← 相比上一时间窗口
│ ▂▃▅▇█▇▅▃▂ (24h趋势)     │
└─────────────────────────┘
```
**数据**：
- 总请求数（当前时间窗口）
- 成功率 = SUCCESS / 总数
- 环比变化（相比上一窗口）
- 24小时微型趋势图

#### 卡片 2：成本与 Token 消耗
```
┌─────────────────────────┐
│ 总成本                   │
│ $234.56                 │
│ Token: 45.2M   ↑ 12%    │
│ 缓存命中率: 32.4%        │
└─────────────────────────┘
```
**数据**：
- 总成本（当前窗口）
- 总 Token 数（input + output + cache）
- 环比变化
- 缓存命中率 = cacheReadInputTokens / (promptTokens + cacheReadInputTokens)

#### 卡片 3：平均延迟与百分位
```
┌─────────────────────────┐
│ 平均延迟                 │
│ 1,245 ms                │
│ P50: 890ms  P95: 3.2s   │
│ P99: 5.8s               │
└─────────────────────────┘
```
**数据**：
- 平均延迟（所有请求）
- P50 / P95 / P99 延迟
- 异常检测：P99 > 10s 标红

#### 卡片 4：Channel 健康度
```
┌─────────────────────────┐
│ Channel 健康度           │
│ 8 个健康 / 10 个总数     │
│ 2 个异常   ⚠️            │
│ 平均可用率: 94.2%        │
└─────────────────────────┘
```
**数据**：
- HEALTHY / COOLDOWN / DISABLED 各状态 channel 数
- 异常 channel 列表（点击跳转到 Providers 面板）
- 7天平均可用率

---

### 2.2 时序趋势图区（3个核心趋势）

#### 图表 1：请求量与成功率时序（折线图 + 柱状图组合）
```
请求量（柱状图，左Y轴）+ 成功率（折线图，右Y轴）
X轴：时间（按窗口：1h 显示每5分钟，24h 显示每小时，7d 显示每天）
```
**数据**：
- 每个时间点的请求总数
- 每个时间点的成功率
- 失败请求数（可选叠加显示）

#### 图表 2：Token 消耗与成本趋势（双Y轴折线图）
```
Token 总量（左Y轴）+ 成本（右Y轴）
颜色区分：
  - 蓝色：Input Tokens
  - 绿色：Output Tokens
  - 橙色：Cache Write
  - 紫色：Cache Read
  - 红色：成本曲线
```
**数据**：
- promptTokens / completionTokens / cacheCreationInputTokens / cacheReadInputTokens 分别的趋势
- 总成本趋势

#### 图表 3：延迟分布时序（多线折线图）
```
三条曲线：P50 / P95 / P99
X轴：时间
Y轴：延迟（ms）
```
**数据**：
- 每个时间点的 P50 / P95 / P99
- 异常峰值标注

---

### 2.3 分布分析区（4个饼图/条形图）

#### 图表 4：模型调用分布（饼图）
```
显示 Top 10 模型及其占比
- 模型名称
- 请求数
- 占比百分比
- 总成本占比（鼠标悬停显示）
```

#### 图表 5：Channel 调用分布（条形图）
```
横向条形图，按请求量排序
每个条形显示：
- Channel 名称
- 请求数
- 成功率（颜色渐变：绿→黄→红）
- 平均延迟
```

#### 图表 6：Group 调用分布（条形图）
```
类似 Channel 分布，按 Group 聚合
显示每个 Group 的：
- 请求数
- 成本占比
- 主要使用的模型（Top 3）
```

#### 图表 7：错误类型分布（饼图）
```
显示各类错误的占比：
- 401/403 (认证错误)
- 429 (限流)
- 500-599 (上游错误)
- 网络错误 (NoRouteToHost 等)
- 4xx 其他
点击可跳转到对应错误的 Usage 记录
```

---

### 2.4 实时监控区（动态数据）

#### 实时请求流（滚动列表）
```
最近 20 条请求，实时刷新（每 5 秒）
显示：
- 时间戳
- Model
- Channel
- 状态（成功/失败）
- 延迟
- Token 数
颜色编码：绿色=成功，红色=失败，黄色=降级
```

#### Channel 实时健康看板（网格布局）
```
每个 Channel 一个小卡片（2x5 网格）
显示：
- Channel 名称
- 状态指示灯（绿/黄/红）
- 最近 60 次调用的微型图（||||||||）
- 实时可用率
- 当前并发数 / 最大并发
点击展开详细统计
```

---

### 2.5 高级分析区（可折叠）

#### 缓存效能分析
```
- 缓存命中率趋势（7天）
- 缓存节省成本估算
- Cache Write vs Cache Read 对比
- 按模型的缓存命中率排行
```

#### Failover 分析
```
- Failover 发生次数趋势
- 触发 Failover 的主要原因（按错误类型分组）
- Failover 后的成功率
- 平均 Failover 延迟增加
```

#### 并发与排队分析
```
- 当前并发数 vs 最大并发（实时）
- 各 Channel 的并发使用率热力图
- 429 限流触发次数
- 排队等待时间分布（如果有）
```

#### 成本优化建议
```
基于数据的自动建议：
- "模型 A 在 Group X 的成本占比过高，考虑切换到更便宜的模型 B"
- "Channel Y 的失败率 15%，建议检查配置或禁用"
- "缓存命中率仅 5%，检查是否正确配置 cache_control"
```

---

### 2.6 时间窗口选择器（全局控制）

```
[1小时] [24小时] [7天] [30天] [自定义]
+ 自动刷新开关（默认关闭）
+ 导出报告按钮（CSV / PDF）
```

---

## 三、各面板增强设计

### 3.1 Usage 面板增强

#### 新增列
- **Group** (`poolLevelId`)
- **Channel** (`upstreamKeyId` → 映射到 channel name)
- **Cache Hit Rate** (`cacheReadInputTokens / (promptTokens + cacheReadInputTokens)`)
- **Request ID**（完整显示，可复制）

#### 顶部筛选器
```
┌─────────────────────────────────────────────────────┐
│ [Group ▼] [Channel ▼] [Model ▼] [Status ▼] [搜索]  │
│ [时间范围: 最近24小时 ▼]  [导出 CSV]                │
└─────────────────────────────────────────────────────┘
```

#### 点击详情 Drawer
```
右侧滑出抽屉，显示：
┌─────────────────────────────────────┐
│ 请求详情                             │
├─────────────────────────────────────┤
│ Request ID: req-abc123...           │
│ Timestamp: 2026-06-17 15:23:45      │
│ Model: claude-opus-4-8              │
│ Group: production                   │
│ Channel: anthropic-primary          │
├─────────────────────────────────────┤
│ Token 详情                          │
│  Input: 1,234 tokens                │
│  Output: 567 tokens                 │
│  Cache Write: 89 tokens             │
│  Cache Read: 456 tokens             │
│  Cache Hit Rate: 37.5%              │
├─────────────────────────────────────┤
│ 成本详情                            │
│  Input: $0.0123                     │
│  Output: $0.0567                    │
│  Cache: -$0.0045 (节省)             │
│  Total: $0.0645                     │
├─────────────────────────────────────┤
│ 性能                                │
│  延迟: 1,234 ms                     │
│  Failover: 1 次                     │
│  Status: 200 (SUCCESS)              │
├─────────────────────────────────────┤
│ 错误信息（如有）                     │
│  Error Code: rate_limit_exceeded    │
│  Detail: ...                        │
└─────────────────────────────────────┘
```

---

### 3.2 Providers (Channels) 面板增强

#### Channel 卡片新增内容
```
┌────────────────────────────────────────┐
│ 君の的公益                    [正常 ▼]  │
│ Anthropic  claude-opus-4-8            │
├────────────────────────────────────────┤
│ ⚡ 对话延迟    🌐 端点 PING           │
│   3965 ms       907 ms                │
│                                        │
│ 📊 可用率 · 7天      50.14%           │ ← 新增
│ +1 模型                                │
│                                        │
│ 近 60 次记录           59s 后刷新      │
│ ||||||||||||||||||||||||||||          │ ← 新增：绿=成功，红=失败
│ PAST                          NOW     │
│                                        │
│ 📈 调用统计 · 24h                      │ ← 新增
│   1,234 请求  ·  平均 376ms           │
│   成本: $12.34  ·  Token: 2.3M        │
├────────────────────────────────────────┤
│ [详细统计] [测试连接] [编辑] [禁用]    │
└────────────────────────────────────────┘
```

#### 点击"详细统计"展开
```
显示该 Channel 的：
- 7天请求量趋势图
- 成功率趋势
- 延迟百分位趋势
- 使用该 Channel 的 Top 10 模型
- 最近 100 次测试记录（表格）
```

---

### 3.3 Groups 面板增强

#### Group 卡片新增
```
显示每个 Group 的：
- 总请求数（当前窗口）
- 成本总额
- 主要使用的模型（Top 3 + 饼图）
- attached 的 Channels 及其可用率
- 点击展开调用趋势图
```

---

## 四、后端 API 需求清单

### 4.1 新增端点

#### `/api/airelay/stats/dashboard`
```http
GET /api/airelay/stats/dashboard?window=24h&refresh=true
Response: DashboardStatsResponse (详见实现计划)
```

#### `/api/airelay/channels/{channelId}/stats`
```http
GET /api/airelay/channels/{channelId}/stats?window=7d
Response: ChannelStatsResponse
```

#### `/api/airelay/groups/{groupId}/stats`
```http
GET /api/airelay/groups/{groupId}/stats?window=7d
Response: GroupStatsResponse
```

#### `/api/airelay/usage/recent-stream`
```http
GET /api/airelay/usage/recent-stream (SSE 实时推送最新请求)
```

### 4.2 现有端点增强

#### `/api/airelay/usage`
新增查询参数：
- `?groupId=xxx`
- `?channelId=xxx`
- `?model=xxx`
- `?status=success|error`
- `?startTime=...&endTime=...`

返回增强字段：
- `groupId` (从 `poolLevelId` 解析)
- `channelId` / `channelName` (从 `upstreamKeyId` 映射)
- `requestId` (完整)
- `cacheHitRate` (后端计算)

---

## 五、数据存储增强

### 5.1 Channel 测试历史表（新增）
```sql
CREATE TABLE channel_test_history (
    test_id TEXT PRIMARY KEY,
    channel_id TEXT NOT NULL,
    tested_at INTEGER NOT NULL,  -- epoch ms
    success BOOLEAN NOT NULL,
    latency_ms INTEGER,
    error_message TEXT,
    INDEX idx_channel_time (channel_id, tested_at)
);
```
用途：存储每次 `POST /channels/{id}/test` 的结果，用于绘制"近60次记录"微型图

### 5.2 Usage 表字段检查
确保以下字段正确记录：
- `poolLevelId` → 对应 Group
- `upstreamKeyId` → 对应 Channel
- `cacheReadInputTokens` / `cacheCreationInputTokens` → 缓存数据
- `requestId` → 用于详情追踪

---

## 六、技术选型

### 前端
- **图表库**：`recharts`（已在依赖中）
- **状态管理**：React Hooks (useState / useEffect)
- **实时更新**：SSE (EventSource) 或定时轮询
- **UI 组件**：继续使用 `@keel/sample-ui`

### 后端
- **聚合查询**：SQL GROUP BY + 窗口函数
- **百分位计算**：
  - 方案 1：SQL PERCENTILE_CONT (H2 支持)
  - 方案 2：内存排序（数据量大时考虑采样）
- **缓存**：Dashboard 统计结果缓存 1 分钟（减轻 DB 压力）

---

## 七、性能考虑

### 7.1 Dashboard 数据聚合
- **问题**：7天窗口可能有数十万条 usage 记录，聚合查询慢
- **方案**：
  1. 短窗口（1h/24h）实时查询
  2. 长窗口（7d/30d）后台定时预聚合，存入 `dashboard_stats_cache` 表
  3. 用户请求时返回缓存 + 增量计算

### 7.2 实时数据推送
- **问题**：每秒可能数百个请求，全量推送前端压力大
- **方案**：
  1. SSE 只推送"最近 20 条"变更
  2. 前端本地维护滚动缓冲区
  3. Dashboard 自动刷新间隔可配置（默认 30 秒）

---

## 八、UI/UX 细节

### 8.1 色彩规范
- **成功/健康**：绿色 `#10b981`
- **警告/降级**：黄色 `#f59e0b`
- **错误/禁用**：红色 `#ef4444`
- **中性**：灰色 `#6b7280`

### 8.2 响应式设计
- 桌面（>1200px）：Dashboard 显示 4 列网格
- 平板（768-1200px）：2 列网格
- 移动（<768px）：1 列堆叠

### 8.3 空数据占位
所有图表/卡片在无数据时显示友好提示：
```
"暂无数据"
"该时间窗口内没有请求记录"
```

---

## 九、优先级说明

**P0（核心功能）**：
- 修复缓存命中率 Bug
- Usage 面板增加 group/channel 列和筛选
- Channel 可用率卡片和微型图

**P1（重要增强）**：
- Dashboard 核心区域（概览卡片 + 三个趋势图）
- Usage 详情 Drawer
- Channel 统计 API

**P2（进阶功能）**：
- Dashboard 分布分析区（饼图/条形图）
- 实时请求流
- Failover 分析

**P3（优化功能）**：
- 成本优化建议
- 并发分析
- 报告导出
