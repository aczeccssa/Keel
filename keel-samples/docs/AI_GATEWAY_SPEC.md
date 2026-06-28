# AI Gateway Platform — Technical Specification

> **Status**: Draft v1.0
> **Audience**: 实现者（即作者本人）
> **Scope**: 基于 Keel 框架在 `keel-samples` 中实现一个完整的 AI Gateway 平台
> **Last Updated**: 2026-05-31

---

## 0. Document Map

| 章节 | 内容 |
|------|------|
| 1 | 项目目标 & 非目标 |
| 2 | 关键决策记录（ADR） |
| 3 | 架构总览 |
| 4 | Keel 框架契约约束 |
| 5 | 模块 1: Account Plugin |
| 6 | 模块 2: Token Plugin |
| 7 | 模块 3: AI Relay Plugin |
| 8 | 模块 4: Risk Control Plugin |
| 9 | 模块 5: Observability Plugin |
| 10 | 跨模块协议 & 数据流 |
| 11 | 部署 & 配置 |
| 12 | Roadmap & 验收标准 |
| 附录 A | 竞品对照表 |
| 附录 B | OpenAI/Anthropic 协议参考 |

---

## 1. 项目目标

### 1.1 What

在 Keel 框架的 `keel-samples` 模块下，实现一个具备以下能力的 **AI Gateway 平台**：

1. **三协议入口** — 同时暴露：
   - **OpenAI Chat Completions** (`/v1/chat/completions`) — legacy 兼容
   - **OpenAI Responses API** (`/v1/responses`) — OpenAI 主推的新一代 agent-style API（2025-03 GA）
   - **Anthropic Messages** (`/v1/messages`) — Anthropic 原生协议；普通客户端走结构化兼容模式，Claude Code 类请求在 Anthropic→Anthropic 路由时走 fidelity 转发模式
2. **协议任意互转** — 客户端用任意一种协议请求，可路由到任意一种协议的上游（3×3 矩阵）
3. **账户体系** — 用户注册、JWT 鉴权、用户组、角色（admin/user）
4. **虚拟 API Key** — 平台为用户派发 `sk-keel-xxx`，与上游真实 Key 解耦
5. **多级故障转移号池** — P-Level 顺序切换 + 同层负载均衡 + 自动降级
6. **风控限流** — Token Bucket 多维度限流（IP / User / API Key / Model）
7. **成本追踪** — 精确计费 + Prompt Caching 支持（Anthropic & OpenAI）+ Reasoning tokens 计费
8. **可观测性** — 扩展现有 Observability Plugin，新增 AI Gateway 专属面板

### 1.2 What NOT

明确不做的事情（避免 scope creep）：

- ❌ 多实例分布式部署（Phase 1 单机优先；Redis 共享状态留到 Phase 3）
- ❌ Embedding API、Image Generation、Realtime API（先做 chat completions / messages）
- ❌ 复杂的 OAuth/SSO（用 JWT 即可；GitHub/Google 登录留到 Phase 3）
- ❌ Web 管理后台 UI（管理操作通过 API + 现有 Observability Plugin 面板）
- ❌ Webhook、Slack 集成、SDK 包装

---

## 2. 关键决策记录（ADR）

### ADR-001: 模块边界

**决策**：拆成 5 个独立 Keel Plugin：`account`、`token`、`airelay`、`riskcontrol`、`observability`（复用已有的）

**理由**：
- Keel 强约束 Plugin 间不能直接 import
- 拆分后每个 plugin 可独立热重载（如调整风控规则不影响进行中的请求）
- 符合关注点分离

**取舍**：
- Plugin 间通信只能通过 (a) `kernelKoin` 全局 scope 注册共享接口，(b) `KeelEventBus`
- 选 (a)：在 `keel-contract` 里定义 `ApiKeyVerifier`、`UsageRecorder`、`RateLimitGate` 接口，由对应插件实现并注册到 kernelKoin

### ADR-002: 限流算法

**决策**：Token Bucket（参考之前讨论）

**理由**：
- 天然支持突发流量
- 实现经典，社区方案多
- LiteLLM、OneAPI 都采用类似思路

### ADR-003: 故障转移设计

**决策**：P-Level 多层号池（用户提出，受 LiteLLM `order` 启发）

**结构**：
```
Pool Chain (per model)
├── Level 1: [key_a, key_b, key_c]  ← 同层 LB；全部失败 → 降到 L2
├── Level 2: [key_d, key_e]
└── Level N: [...]
```

**与 LiteLLM `order` 等价**，但用户视角更明确：层与层语义不同（如 L1 官方、L2 Azure、L3 代理商）

### ADR-004: 上游 Cache 处理

**决策**：**Phase 1 必须做**（不是 Phase 2 才考虑）

**原因**：
- Anthropic `cache_read_input_tokens` 价格只有普通 input 的 10%，不正确计费会严重多收/少收
- OpenAI `prompt_tokens_details.cached_tokens` 同理
- 计费引擎从 Day 1 就要支持这些字段

### ADR-005: 响应级缓存

**决策**：Phase 2 实现，Phase 1 预留接口

**理由**：
- 涉及 Redis 依赖，增加 demo 启动复杂度
- 命中率高度依赖业务场景，demo 阶段意义有限

### ADR-006: 持久化

**决策**：
- **Phase 1**：H2 内存数据库（Keel 已有 `AuditPluginTable` 支持）
- **Phase 2**：可切换 PostgreSQL
- 上游号池配置 **不入库**，从配置文件/环境变量加载（避免敏感 key 入 demo 库）

### ADR-007: 流式响应故障转移

**决策**：Buffer-first-chunk 策略

**实现**：
- 拿到上游第一个 chunk 之前可以静默切换号池
- 第一个 chunk 之后失败 → 只能把错误注入 SSE 流告知客户端
- 这是业界通用做法

### ADR-008: Token 计数

**决策**：
- **优先**：解析上游响应的 `usage` 字段（精确）
- **fallback**：用 `tiktoken-jvm` 等库本地估算（误差 ±5%）
- **流式**：累积 chunk 估算用于限流，最终用上游精确值入账

### ADR-009: 三协议互转的设计 — 引入 IR（Internal Representation）

**决策**：不写 N×N 的转换函数（6 个方向 → 维护噩梦），而是引入**内部规范化中间表示 GatewayIR**，所有协议先转 IR，再从 IR 转出。

**理由**：
- 三协议互转需要 9 种组合（含自身透传）。直接两两映射 → 需要 6 个转换器，新增协议成本爆炸
- OpenAI Responses API 的 `output[]` items 结构和 Anthropic 的 `content[]` blocks 概念高度对齐（都是 typed item array：text / tool_call / reasoning / refusal …），天然适合做 IR 基础
- Chat Completions 的扁平 `messages` 结构是 IR 的退化形式（只有 text item），转换是有损但可定义

**架构**：

```
Client Request (Any Protocol)
        ↓
   ParseToIR (协议特定)
        ↓
   GatewayIR  ←── 规范化、不可变、所有协议的共同上界
        ↓
   SerializeFromIR (协议特定，可能损失信息)
        ↓
Upstream Request (Any Protocol)

回程同理（响应/流式事件）。
```

**取舍**：
- IR 必须能表达 3 协议的并集；不在并集里的字段塞到 `extras: Map<String, JsonElement>`
- 损失项必须显式：当 Chat Completions ← Anthropic thinking block 时，要么序列化为 `<thinking>...</thinking>` 文本（兼容但污染），要么完全丢弃（推荐，加 warning header）
- IR 是**实现技术**，不是对外协议：客户端永远看不到 IR

### ADR-010: Responses API 的 stateful 状态管理

**决策**：**Phase 1 不代理 `previous_response_id` 服务端状态**，强制要求客户端用 `store: false`（stateless 模式）或客户端自己管理 history。

**理由**：
- 服务端状态意味着每个 `response_id` 必须 sticky 到同一个上游 key（同 LiteLLM 的 `encrypted_content_affinity` 问题）
- 多 Provider 的情况下，response_id 跨 provider 不通用，会让 fallback 失效
- Phase 1 用 `encrypted_content` 模式（OpenAI 原生支持的 stateless reasoning 传递）解决推理上下文保留问题

**Phase 3 再考虑**：在我们这层做一个 response cache 层，把 `previous_response_id` 映射回完整历史，绕过上游状态管理。

---

## 3. 架构总览

### 3.1 模块拓扑

```
┌─────────────────────────────────────────────────────────────────┐
│                       Keel Kernel                                │
│  ┌──────────────┐  ┌──────────────┐  ┌────────────────────┐     │
│  │  account     │  │  token       │  │  airelay           │     │
│  │  plugin      │  │  plugin      │  │  plugin            │     │
│  │              │  │              │  │  ┌──────────────┐  │     │
│  │ users        │  │ api_keys     │  │  │ ProtocolCodec│  │     │
│  │ JWT          │  │ usage_records│  │  │ + GatewayIR  │  │     │
│  │ groups       │  │ quotas       │  │  │ (3 protocols)│  │     │
│  │              │  │              │  │  │ PoolChainMgr │  │     │
│  │              │  │              │  │  │ StreamRelay  │  │     │
│  │              │  │              │  │  │ UpstreamHttp │  │     │
│  └──────┬───────┘  └──────┬───────┘  │  └──────────────┘  │     │
│         │                  │           └────────────────────┘    │
│  ┌──────▼──────────────────▼────────────────────────────────┐    │
│  │           keel-contract: shared interfaces               │    │
│  │  UserDirectory, ApiKeyVerifier, UsageRecorder,           │    │
│  │  RateLimitGate, ModelPricingRegistry                     │    │
│  └──────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌──────────────┐  ┌──────────────────────────────────────┐      │
│  │ riskcontrol  │  │ observability (existing, extended)   │      │
│  │ plugin       │  │                                      │      │
│  │              │  │ + AI Gateway panel:                  │      │
│  │ TokenBucket  │  │   - cost dashboard                   │      │
│  │ multi-dim    │  │   - pool chain health                │      │
│  │ cooldown     │  │   - top users/models                 │      │
│  └──────────────┘  └──────────────────────────────────────┘      │
└─────────────────────────────────────────────────────────────────┘
        ↓ HTTP
   ┌─────────────────┐    ┌───────────────────┐    ┌──────────────┐
   │ OpenAI          │    │ OpenAI            │    │ Anthropic    │
   │ Chat Completions│    │ Responses API     │    │ Messages     │
   │ (legacy)        │    │ (preferred)       │    │              │
   └─────────────────┘    └───────────────────┘    └──────────────┘
                  ↑           三种上游协议任意混合              ↑
                  └────────── 客户端入口三协议任选 ─────────────┘
```

### 3.2 一次典型请求的生命周期

```
1. Client: POST /api/plugins/airelay/v1/chat/completions
   Headers: Authorization: Bearer sk-keel-xxx

2. Keel GatewayInterceptor: 检查 plugin 可用性 → 通过

3. airelay 插件的拦截器链按序执行:
   ├── ApiKeyAuthInterceptor (来自 token plugin via kernelKoin)
   │   ├── 解析 sk-keel-xxx → hash → 查 api_keys 表
   │   ├── 校验状态/过期/IP 白名单/模型权限
   │   └── 写入 context.attributes["apiKey"]、["userId"]
   │
   ├── RateLimitInterceptor (来自 riskcontrol plugin via kernelKoin)
   │   ├── 提取维度: IP、userId、keyId、model
   │   ├── 逐个维度 tryAcquire Token Bucket
   │   ├── 失败 → reject 429 + Retry-After
   │   └── 成功 → 继续
   │
   └── 业务 handler:
       ├── 解析 OpenAI 格式请求体
       ├── PoolChainMgr.resolve(model) → PoolChain
       ├── for level in chain.levels:
       │     for key in level.healthyKeys():
       │         tryRelay(request, level.provider, key) → 成功 return
       │         分类错误 → cooldown/disable key
       ├── ProtocolTranscoder：根据入口协议 & 目标 provider 协议
       │   走 IR transcoding（参见 §7.5）
       ├── StreamRelay 或 BlockingRelay 转发
       ├── 解析上游 usage → CostCalculator.calculate()
       └── UsageRecorder.record(...) → 异步写 usage_records

4. Response 写回 Client，带上 X-RateLimit-* 和 X-Cost-USD headers
```

### 3.3 数据流：成本追踪

```
                 ┌──────────────────────────────────┐
                 │ AIRelay handler 完成 upstream call │
                 └────────────────┬─────────────────┘
                                  ↓
                  upstream usage (cache fields included)
                                  ↓
                  ┌──────────────────────────────┐
                  │ CostCalculator (in airelay)   │
                  │  - 查 ModelPricingRegistry    │
                  │  - 计算 input/output/cache    │
                  │  - 应用 user group multiplier │
                  └────────────┬─────────────────┘
                                  ↓
                       CostBreakdown
                                  ↓
            ┌─────────────────────────────────────┐
            │ UsageRecorder.record() — 异步       │
            │  (在 token plugin 实现)               │
            └─────┬────────────────┬──────────────┘
                  ↓                ↓
        api_keys.current_spend   usage_records
        原子扣减                  append-only
```

---

## 4. Keel 框架契约约束

实现时必须严格遵守的契约（从 Keel 源码提取）：

### 4.1 Plugin 类骨架

```kotlin
@KeelApiPlugin(
    pluginId = "<plugin_id>",
    title = "...",
    description = "...",
    version = "1.0.0"
)
class XxxPlugin : StandardKeelPlugin {

    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "<plugin_id>",
        version = "1.0.0",
        displayName = "...",
        defaultRuntimeMode = PluginRuntimeMode.IN_PROCESS,
        maxConcurrentCalls = 128,
        callTimeoutMs = 60_000  // 注意 AI 请求可能慢
    )

    override fun modules(): List<Module> = listOf(module {
        single { SomeService() }
        single { SomeInterceptor(get()) }
    })

    override suspend fun onInit(context: PluginInitContext) {
        // 数据库表创建、向 kernelKoin 注册共享接口
    }

    override suspend fun onStart(context: PluginRuntimeContext) {
        // 从 context.privateScope.get() 取服务
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        // 清理资源
    }

    override fun endpoints(): List<PluginRouteDefinition> =
        pluginEndpoints(descriptor.pluginId) {
            // DSL 定义路由
        }
}
```

### 4.2 路由 DSL 约束

```kotlin
pluginEndpoints("airelay") {
    interceptors(ApiKeyAuthInterceptor::class, RateLimitInterceptor::class)

    route("/v1") {
        post<ChatCompletionRequest, ChatCompletionResponse>("/chat/completions") { req ->
            // handler
            PluginResult(body = ...)
        }

        post<MessagesRequest, MessagesResponse>("/messages") { req ->
            PluginResult(body = ...)
        }

        sse("/chat/completions/stream") {
            // SSE handler
        }
    }
}
```

**重要约束**：
- 路由最终被挂载在 `/api/plugins/{pluginId}/...`
- 所以 OpenAI 客户端 base URL 必须配置为 `http://localhost:8080/api/plugins/airelay`
- `interceptors(...)` 是继承式的，子 route 会继承
- `noInterceptors()` 显式清空继承

### 4.3 表定义约束

```kotlin
// 必须用 AuditPluginTable（带审计字段）或 PluginTable（裸表）
object ApiKeysTable : AuditPluginTable(
    pluginId = "token",       // 物理表名: token_api_keys
    tableName = "api_keys"
) {
    val keyId = varchar("key_id", 32)
    // ... 其他列
    override val primaryKey = PrimaryKey(keyId)
}
```

### 4.4 跨插件共享接口

不能 `Plugin A import Plugin B`，必须经由 `keel-contract`：

```kotlin
// keel-contract/src/main/kotlin/com/keel/contract/ai/ApiKeyVerifier.kt
package com.keel.contract.ai

interface ApiKeyVerifier {
    suspend fun verify(rawKey: String): VerifiedKey?
}

data class VerifiedKey(
    val keyId: String,
    val userId: String,
    val userGroupId: String,
    val allowedModels: List<String>,
    val rpmLimit: Int?,
    val tpmLimit: Int?,
    val remainingBudgetUsd: Double
)
```

**TokenPlugin 实现并注册到 kernelKoin**：
```kotlin
override suspend fun onInit(context: PluginInitContext) {
    val kernelModule = module {
        single<ApiKeyVerifier> { ApiKeyVerifierImpl(get()) }
    }
    // 注册到 kernel scope，所有其他插件可见
    context.kernelKoin.loadModules(listOf(kernelModule))
}
```

**AIRelayPlugin 消费**：
```kotlin
class ApiKeyAuthInterceptor(
    private val verifier: ApiKeyVerifier  // 通过 Koin 注入，来自 kernelKoin
) : KeelRequestInterceptor { ... }
```

---

## 5. 模块 1: Account Plugin

### 5.1 职责

- 用户注册、登录、JWT 签发与验证
- 用户基本信息 CRUD
- 用户组管理（影响计费倍率）
- 角色管理（admin / user）
- 向 kernelKoin 暴露 `UserDirectory` 接口供其他插件查询用户

### 5.2 数据模型

```kotlin
object UsersTable : AuditPluginTable("account", "users") {
    val userId       = varchar("user_id", 32)        // PK, e.g. "usr-abc123"
    val email        = varchar("email", 256).uniqueIndex()
    val passwordHash = varchar("password_hash", 256) // bcrypt
    val displayName  = varchar("display_name", 120)
    val role         = varchar("role", 16)           // "admin" | "user"
    val groupId      = varchar("group_id", 32)       // FK to UserGroupsTable
    val status       = varchar("status", 16)         // "active" | "suspended" | "banned"
    val lastLoginAt  = timestamp("last_login_at").nullable()
    override val primaryKey = PrimaryKey(userId)
}

object UserGroupsTable : AuditPluginTable("account", "user_groups") {
    val groupId        = varchar("group_id", 32)
    val name           = varchar("name", 80)
    val costMultiplier = double("cost_multiplier")   // 1.0 = 原价
    val defaultRpm     = integer("default_rpm").nullable()
    val defaultTpm     = integer("default_tpm").nullable()
    val defaultBudgetUsd = double("default_budget_usd")
    override val primaryKey = PrimaryKey(groupId)
}
```

### 5.3 共享接口（keel-contract）

```kotlin
// keel-contract/src/main/kotlin/com/keel/contract/ai/UserDirectory.kt
interface UserDirectory {
    suspend fun findById(userId: String): UserSummary?
    suspend fun findGroup(groupId: String): UserGroupSummary?
}

data class UserSummary(
    val userId: String,
    val email: String,
    val displayName: String,
    val role: String,
    val groupId: String,
    val status: String
)

data class UserGroupSummary(
    val groupId: String,
    val name: String,
    val costMultiplier: Double,
    val defaultRpm: Int?,
    val defaultTpm: Int?
)
```

### 5.4 REST API

挂载在 `/api/plugins/account/`：

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST   | `/v1/auth/register` | none  | 注册账户 |
| POST   | `/v1/auth/login`    | none  | 用户名密码登录 → JWT |
| POST   | `/v1/auth/refresh`  | refresh token | 刷新 access token |
| GET    | `/v1/auth/me`       | JWT   | 当前用户信息 |
| PUT    | `/v1/auth/me`       | JWT   | 更新 display_name 等 |
| GET    | `/admin/users`      | admin JWT | 用户列表（分页） |
| GET    | `/admin/users/{id}` | admin JWT | 用户详情 |
| PUT    | `/admin/users/{id}` | admin JWT | 更新用户（角色、组、状态） |
| POST   | `/admin/users/{id}/suspend`  | admin JWT | 封禁 |
| POST   | `/admin/users/{id}/activate` | admin JWT | 解封 |
| GET    | `/admin/groups`     | admin JWT | 用户组列表 |
| POST   | `/admin/groups`     | admin JWT | 创建用户组 |

### 5.5 JWT 设计

```
Access Token:
  alg: HS256
  payload: {
    sub: "<userId>",
    email: "...",
    role: "user|admin",
    groupId: "...",
    iat: ...,
    exp: ... (1h)
  }

Refresh Token:
  随机 64 字节，bcrypt 存库，14天过期
```

### 5.6 拦截器

- `JwtAuthInterceptor` — 校验 Bearer JWT，写 `context.principal = UserPrincipal`
- `AdminOnlyInterceptor` — 在 JwtAuth 之后，校验 `role == "admin"`

---

## 6. 模块 2: Token Plugin

### 6.1 职责

- 派发平台虚拟 API Key（`sk-keel-<base62>`）
- 校验 Key 有效性（hash 查表、状态、过期、IP 白名单、模型权限、配额）
- 配额管理：预算、RPM、TPM、临时增额
- 用量记录：每次 AI 请求后异步写入
- 向 kernelKoin 暴露 `ApiKeyVerifier`、`UsageRecorder`

### 6.2 数据模型

```kotlin
object ApiKeysTable : AuditPluginTable("token", "api_keys") {
    val keyId          = varchar("key_id", 32)          // PK
    val keyPrefix      = varchar("key_prefix", 16)      // "sk-keel-xxx..." 前缀用于展示
    val keyHash        = varchar("key_hash", 64)        // SHA-256(rawKey)
    val userId         = varchar("user_id", 32).index()
    val displayName    = varchar("display_name", 120)

    val maxBudgetUsd       = double("max_budget_usd")
    val budgetDurationDays = integer("budget_duration_days")
    val budgetResetAt      = timestamp("budget_reset_at")
    val currentSpendUsd    = double("current_spend_usd").default(0.0)

    val tempBudgetIncrease = double("temp_budget_increase").nullable()
    val tempBudgetExpiry   = timestamp("temp_budget_expiry").nullable()

    val rpmLimit       = integer("rpm_limit").nullable()
    val tpmLimit       = integer("tpm_limit").nullable()

    val allowedModelsJson = text("allowed_models_json").default("[]")  // JSON array
    val allowedIpsJson    = text("allowed_ips_json").default("[]")     // JSON array of CIDRs

    val status         = varchar("status", 16)          // "active" | "revoked" | "expired"
    val expiresAt      = timestamp("expires_at").nullable()
    val lastUsedAt     = timestamp("last_used_at").nullable()

    override val primaryKey = PrimaryKey(keyId)
    init {
        index(true, keyHash)  // 唯一索引，用于快速查找
    }
}

object UsageRecordsTable : AuditPluginTable("token", "usage_records") {
    val recordId       = varchar("record_id", 32)
    val keyId          = varchar("key_id", 32).index()
    val userId         = varchar("user_id", 32).index()
    val timestamp      = timestamp("timestamp").index()

    val model          = varchar("model", 80)
    val provider       = varchar("provider", 32)        // "openai" | "anthropic" | ...
    val poolLevelId    = varchar("pool_level_id", 64).nullable()
    val upstreamKeyId  = varchar("upstream_key_id", 64).nullable()

    val promptTokens             = integer("prompt_tokens")
    val completionTokens         = integer("completion_tokens")
    val cacheCreationInputTokens = integer("cache_creation_input_tokens").default(0)
    val cacheReadInputTokens     = integer("cache_read_input_tokens").default(0)
    val cachedPromptTokens       = integer("cached_prompt_tokens").default(0)  // OpenAI
    val reasoningTokens          = integer("reasoning_tokens").default(0)      // OpenAI o1/o3

    val inputCostUsd       = double("input_cost_usd")
    val outputCostUsd      = double("output_cost_usd")
    val cacheWriteCostUsd  = double("cache_write_cost_usd").default(0.0)
    val cacheReadCostUsd   = double("cache_read_cost_usd").default(0.0)
    val totalCostUsd       = double("total_cost_usd")

    val latencyMs          = long("latency_ms")
    val status             = integer("status")          // HTTP status
    val errorCode          = varchar("error_code", 64).nullable()
    val cacheHitRate       = double("cache_hit_rate").nullable()

    override val primaryKey = PrimaryKey(recordId)
}
```

### 6.3 共享接口

```kotlin
// keel-contract/ai/ApiKeyVerifier.kt
interface ApiKeyVerifier {
    suspend fun verify(rawKey: String, clientIp: String?): VerifiedKey
}

data class VerifiedKey(
    val keyId: String,
    val userId: String,
    val userGroupId: String,
    val allowedModels: List<String>,
    val rpmLimit: Int?,
    val tpmLimit: Int?,
    val remainingBudgetUsd: Double
)

class InvalidApiKeyException(val reason: String) : RuntimeException(reason)
class QuotaExceededException(val keyId: String) : RuntimeException()

// keel-contract/ai/UsageRecorder.kt
interface UsageRecorder {
    suspend fun record(record: UsageRecordInput)
}

data class UsageRecordInput(
    val keyId: String,
    val userId: String,
    val model: String,
    val provider: String,
    val poolLevelId: String?,
    val upstreamKeyId: String?,
    val usage: TokenUsage,
    val cost: CostBreakdown,
    val latencyMs: Long,
    val status: Int,
    val errorCode: String?
)
```

### 6.4 REST API

挂载在 `/api/plugins/token/`：

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST   | `/v1/keys`                | JWT | 创建 API Key |
| GET    | `/v1/keys`                | JWT | 列出自己的 Key |
| GET    | `/v1/keys/{id}`           | JWT | Key 详情 |
| PUT    | `/v1/keys/{id}`           | JWT | 更新 Key 配置 |
| DELETE `/v1/keys/{id}`           | JWT | 吊销 Key |
| POST   | `/v1/keys/{id}/regenerate`     | JWT | 轮换 Key（保留 keyId，换 raw） |
| POST   | `/v1/keys/{id}/temp-budget`    | JWT | 临时增额 |
| GET    | `/v1/keys/{id}/usage`     | JWT | 用量查询 |
| GET    | `/v1/keys/{id}/usage/export.csv` | JWT | 导出 CSV |
| GET    | `/admin/usage/global`     | admin | 全局用量统计 |

### 6.5 关键算法：原子扣减

```kotlin
// 用 Exposed 事务 + SELECT FOR UPDATE 防超扣
suspend fun chargeBudget(keyId: String, costUsd: Double) = transaction {
    val row = ApiKeysTable
        .select { ApiKeysTable.keyId eq keyId }
        .forUpdate()
        .firstOrNull() ?: throw InvalidApiKeyException("not_found")

    val currentSpend = row[ApiKeysTable.currentSpendUsd]
    val maxBudget = row[ApiKeysTable.maxBudgetUsd] +
        (row[ApiKeysTable.tempBudgetIncrease] ?: 0.0)

    if (currentSpend + costUsd > maxBudget) {
        throw QuotaExceededException(keyId)
    }

    ApiKeysTable.update({ ApiKeysTable.keyId eq keyId }) {
        with(SqlExpressionBuilder) {
            it[currentSpendUsd] = currentSpendUsd + costUsd
        }
        it[lastUsedAt] = Clock.System.now()
    }
}
```

**注意**：
- AI Relay 应在 **请求前** 预扣预估额度（防止流式请求中超额）
- 响应后用真实成本 **结算**（多退少补）
- 预估失败 → 拒绝；结算失败 → 记录到对账表

### 6.6 滚动预算重置

每次 verify 时检查 `budgetResetAt`：
```kotlin
if (Clock.System.now() >= row[ApiKeysTable.budgetResetAt]) {
    ApiKeysTable.update({ ApiKeysTable.keyId eq keyId }) {
        it[currentSpendUsd] = 0.0
        it[budgetResetAt] = Clock.System.now() + row[ApiKeysTable.budgetDurationDays].days
    }
}
```

---

## 7. 模块 3: AI Relay Plugin

### 7.1 职责

- 暴露 OpenAI 兼容 API（`/v1/chat/completions`、`/v1/models`）
- 暴露 Anthropic 兼容 API（`/v1/messages`，含 generic compatibility 与 Claude Code fidelity 两种模式）
- 协议互转
- 多级故障转移号池
- SSE 流式转发
- Token 计数与成本核算
- 加载并维护 ModelPricingRegistry

### 7.2 配置模型（不入库）

```kotlin
// 从 config/airelay.json 或环境变量加载
data class UpstreamProvider(
    val providerId: String,        // "openai-official"
    val displayName: String,
    val baseUrl: String,           // "https://api.openai.com"
    val protocol: WireProtocol,    // OPENAI_CHAT | OPENAI_RESPONSES | ANTHROPIC_MESSAGES
    val timeoutMs: Long = 60_000,
    val defaultHeaders: Map<String, String> = emptyMap()
)

// WireProtocol 复用 §7.5 定义
// 注意：上游 provider 必须指定明确的协议；
// 同一个真实账号（如 OpenAI）若同时支持 chat & responses，
// 配两个 provider 实例（不同 protocol）即可

data class PooledKey(
    val keyId: String,             // 内部标识
    val apiKey: String,            // 真实 sk-xxx，启动时从 env 注入
    val weight: Int = 100,
    val maxConcurrency: Int = 10,
    val supportedModels: List<String> = emptyList()  // 空 = 全部
)

enum class LoadBalanceStrategy {
    ROUND_ROBIN,
    WEIGHTED_RANDOM,
    LEAST_BUSY
}

data class PoolLevel(
    val levelId: String,           // "l1-openai-official"
    val levelIndex: Int,           // 1, 2, 3...
    val provider: UpstreamProvider,
    val keys: List<PooledKey>,
    val loadBalanceStrategy: LoadBalanceStrategy = LoadBalanceStrategy.ROUND_ROBIN,
    val onFailureCooldownMs: Long = 60_000,
    val maxRetriesPerKey: Int = 0  // 通常 0，靠层内换 key
)

data class PoolChain(
    val chainId: String,           // "gpt-4o-chain"
    val modelAliases: List<String>, // ["gpt-4o", "gpt-4o-2024-08-06"]
    val levels: List<PoolLevel>     // 按 levelIndex 排序
)

// 完整配置示例
{
  "chains": [
    {
      "chainId": "gpt-4o-chain",
      "modelAliases": ["gpt-4o"],
      "levels": [
        {
          "levelId": "l1-openai-official",
          "levelIndex": 1,
          "provider": {
            "providerId": "openai",
            "baseUrl": "https://api.openai.com",
            "protocol": "OPENAI"
          },
          "keys": [
            { "keyId": "k1", "apiKey": "${OPENAI_KEY_1}", "weight": 100 },
            { "keyId": "k2", "apiKey": "${OPENAI_KEY_2}", "weight": 100 }
          ],
          "loadBalanceStrategy": "ROUND_ROBIN"
        },
        {
          "levelId": "l2-azure",
          "levelIndex": 2,
          "provider": {
            "providerId": "azure",
            "baseUrl": "https://my-resource.openai.azure.com",
            "protocol": "OPENAI",
            "defaultHeaders": { "api-key": "${AZURE_KEY}" }
          },
          "keys": [
            { "keyId": "k3", "apiKey": "azure-stub", "weight": 100 }
          ]
        }
      ]
    }
  ]
}
```

### 7.3 运行时状态

```kotlin
// 不入库，纯内存（ConcurrentHashMap）
data class KeyState(
    val key: PooledKey,
    val providerId: String,
    val levelId: String,
    var status: KeyStatus = KeyStatus.HEALTHY,
    val consecutiveFailures: AtomicInteger = AtomicInteger(0),
    @Volatile var cooldownUntilMs: Long = 0,
    @Volatile var retryAfterSeconds: Int? = null,
    val currentConcurrency: AtomicInteger = AtomicInteger(0),
    val totalRequests: AtomicLong = AtomicLong(0),
    val totalFailures: AtomicLong = AtomicLong(0),
    @Volatile var lastUsedAtMs: Long = 0,
    @Volatile var lastError: UpstreamError? = null
)

enum class KeyStatus { HEALTHY, COOLDOWN, DEGRADED, DISABLED }

data class PoolLevelRuntime(
    val config: PoolLevel,
    val keyStates: List<KeyState>,
    val roundRobinIndex: AtomicInteger = AtomicInteger(0)
)
```

### 7.4 PoolChainManager 核心算法

```kotlin
class PoolChainManager(
    private val chains: List<PoolChainRuntime>,
    private val upstreamClient: UpstreamHttpClient
) {
    /** 找到 model 对应的 chain，没有 → throw ModelNotFoundException */
    fun resolveChain(model: String): PoolChainRuntime

    /** 非流式：一直找到能用的 key，失败则降级到下一层 */
    suspend fun relay(
        chain: PoolChainRuntime,
        request: UpstreamRequest
    ): UpstreamResponse {
        var lastError: UpstreamError? = null
        for (level in chain.levels) {
            val result = tryLevel(level, request)
            when (result) {
                is RelayResult.Success -> return result.response
                is RelayResult.ClientError -> throw result.toException() // 4xx 不重试
                is RelayResult.LevelExhausted -> lastError = result.lastError
            }
        }
        throw AllPoolsExhaustedException(lastError)
    }

    /** 流式：成功拿到 first chunk 之前可换层 */
    suspend fun relayStream(
        chain: PoolChainRuntime,
        request: UpstreamRequest
    ): Flow<ServerSentEvent>

    private suspend fun tryLevel(
        level: PoolLevelRuntime,
        request: UpstreamRequest
    ): RelayResult {
        val now = System.currentTimeMillis()
        // 恢复冷却结束的 key
        level.keyStates
            .filter { it.status == KeyStatus.COOLDOWN && now >= it.cooldownUntilMs }
            .forEach { it.status = KeyStatus.HEALTHY; it.consecutiveFailures.set(0) }

        val available = level.keyStates
            .filter { it.status == KeyStatus.HEALTHY }
            .filter { it.currentConcurrency.get() < it.key.maxConcurrency }
            .filter { it.key.supportedModels.isEmpty() || request.model in it.key.supportedModels }

        if (available.isEmpty()) return RelayResult.LevelExhausted(null)

        var lastError: UpstreamError? = null
        for (attempt in 0 until available.size) {
            val keyState = pickByStrategy(available, level.config.loadBalanceStrategy, level.roundRobinIndex)
            keyState.currentConcurrency.incrementAndGet()
            try {
                val response = upstreamClient.send(
                    provider = level.config.provider,
                    apiKey = keyState.key.apiKey,
                    request = request
                )
                // 成功：重置失败计数
                keyState.consecutiveFailures.set(0)
                keyState.totalRequests.incrementAndGet()
                keyState.lastUsedAtMs = System.currentTimeMillis()
                return RelayResult.Success(response, keyState, level.config)
            } catch (e: UpstreamException) {
                val error = classify(e)
                handleFailure(keyState, error, level.config)
                lastError = error
                when (error.type) {
                    UpstreamFailureType.CLIENT_ERROR -> return RelayResult.ClientError(error)
                    else -> continue  // 试同层下一个 key
                }
            } finally {
                keyState.currentConcurrency.decrementAndGet()
            }
        }
        return RelayResult.LevelExhausted(lastError)
    }

    private fun classify(e: UpstreamException): UpstreamError = when {
        e is UpstreamHttpException && e.status == 429 -> UpstreamError(
            type = UpstreamFailureType.RATE_LIMITED,
            statusCode = 429,
            message = e.message ?: "rate limited",
            retryAfterSeconds = e.retryAfterSeconds
        )
        e is UpstreamHttpException && e.status in 401..403 -> UpstreamError(
            type = UpstreamFailureType.AUTH_ERROR, statusCode = e.status,
            message = e.message ?: "auth"
        )
        e is UpstreamHttpException && e.status in 400..499 -> UpstreamError(
            type = UpstreamFailureType.CLIENT_ERROR, statusCode = e.status,
            message = e.message ?: "client"
        )
        e is UpstreamHttpException && e.status in 500..599 -> UpstreamError(
            type = UpstreamFailureType.SERVER_ERROR, statusCode = e.status,
            message = e.message ?: "server"
        )
        e is UpstreamTimeoutException -> UpstreamError(
            type = UpstreamFailureType.TIMEOUT, statusCode = null,
            message = "timeout"
        )
        else -> UpstreamError(
            type = UpstreamFailureType.NETWORK_ERROR, statusCode = null,
            message = e.message ?: "network"
        )
    }

    private fun handleFailure(state: KeyState, err: UpstreamError, level: PoolLevel) {
        state.totalFailures.incrementAndGet()
        state.lastError = err
        when (err.type) {
            UpstreamFailureType.RATE_LIMITED -> {
                state.status = KeyStatus.COOLDOWN
                val cooldown = err.retryAfterSeconds?.let { it * 1000L }
                    ?: level.onFailureCooldownMs
                state.cooldownUntilMs = System.currentTimeMillis() + cooldown
                state.retryAfterSeconds = err.retryAfterSeconds
            }
            UpstreamFailureType.AUTH_ERROR -> {
                state.status = KeyStatus.DISABLED
            }
            UpstreamFailureType.SERVER_ERROR,
            UpstreamFailureType.TIMEOUT,
            UpstreamFailureType.NETWORK_ERROR -> {
                val failures = state.consecutiveFailures.incrementAndGet()
                if (failures >= 3) {
                    state.status = KeyStatus.COOLDOWN
                    state.cooldownUntilMs = System.currentTimeMillis() +
                        (1000L * (1L shl minOf(failures, 6)))  // 指数退避
                }
            }
            UpstreamFailureType.CLIENT_ERROR -> { /* 不惩罚 key */ }
        }
    }
}
```

### 7.5 协议转换（三方互转，基于 IR）

**核心架构（参见 ADR-009）**：所有协议先转入 `GatewayIR`，再从 IR 转出，避免 N×N 矩阵。

```
┌─────────────────────────────────────────────────────────────────┐
│                         GatewayIR                                │
│                                                                  │
│  data class IrRequest(                                           │
│    model: String,                                                │
│    instructions: String?,        // 顶层指令 / system           │
│    items: List<IrItem>,          // 输入 items                  │
│    maxOutputTokens: Int?,                                        │
│    temperature: Double?,                                         │
│    topP: Double?,                                                │
│    stopSequences: List<String>,                                  │
│    tools: List<IrTool>,                                          │
│    toolChoice: IrToolChoice?,                                    │
│    reasoningEffort: ReasoningEffort?,  // none/low/medium/high  │
│    responseFormat: IrResponseFormat?,  // json_schema 等        │
│    cacheHints: List<IrCacheHint>,      // 何处加缓存            │
│    stream: Boolean,                                              │
│    metadata: Map<String, String>,      // 透传                  │
│    extras: Map<String, JsonElement>    // 协议专属字段          │
│  )                                                               │
│                                                                  │
│  sealed interface IrItem {                                       │
│    data class UserMessage(val content: List<IrContentPart>)      │
│    data class AssistantMessage(val content: List<IrContentPart>) │
│    data class ToolResult(val toolUseId: String,                  │
│                          val content: String,                    │
│                          val isError: Boolean)                   │
│  }                                                               │
│                                                                  │
│  sealed interface IrContentPart {                                │
│    data class Text(val text: String,                             │
│                    val cacheControl: IrCacheHint?)               │
│    data class Image(val mimeType: String, val dataBase64: String)│
│    data class ToolUse(val id: String, val name: String,          │
│                       val input: JsonElement)                    │
│    data class Reasoning(val summary: String?,                    │
│                          val encryptedContent: String?)          │
│  }                                                               │
│                                                                  │
│  data class IrResponse(                                          │
│    id: String,                                                   │
│    model: String,                                                │
│    output: List<IrItem>,                                         │
│    stopReason: IrStopReason,                                     │
│    usage: TokenUsage                                             │
│  )                                                               │
└─────────────────────────────────────────────────────────────────┘
```

#### 7.5.1 ProtocolCodec 接口

每个协议实现一个 `ProtocolCodec`，负责自己协议 ↔ IR：

```kotlin
enum class WireProtocol { OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC_MESSAGES }

interface ProtocolCodec {
    val protocol: WireProtocol

    fun decodeRequest(rawJson: JsonObject): IrRequest
    fun encodeRequest(ir: IrRequest): JsonObject

    fun decodeResponse(rawJson: JsonObject): IrResponse
    fun encodeResponse(ir: IrResponse): JsonObject

    /** 流式：把上游 SSE 转 IR 事件流 */
    fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent>

    /** 流式：把 IR 事件流序列化为本协议 SSE */
    fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent>
}

class ProtocolTranscoder(private val codecs: Map<WireProtocol, ProtocolCodec>) {
    fun transcodeRequest(
        rawJson: JsonObject,
        from: WireProtocol,
        to: WireProtocol
    ): JsonObject {
        val ir = codecs[from]!!.decodeRequest(rawJson)
        return codecs[to]!!.encodeRequest(ir)
    }
    // transcodeResponse / transcodeStream 同理
}
```

#### 7.5.2 三协议 ↔ IR 映射矩阵

**请求字段映射**：

| IrRequest 字段 | OpenAI Chat Completions | OpenAI Responses | Anthropic Messages |
|----------------|-------------------------|------------------|-----|
| `model` | `model` | `model` | `model` |
| `instructions` | `messages[role=system].content` | `instructions`（顶层） | `system`（顶层，可数组） |
| `items` | `messages[role≠system]` | `input`（若是 array） | `messages[]` |
| `maxOutputTokens` | `max_tokens`（可选） | `max_output_tokens` | `max_tokens`（必填） |
| `temperature` | `temperature` | `temperature` | `temperature` |
| `topP` | `top_p` | `top_p` | `top_p` |
| `stopSequences` | `stop` | `stop` | `stop_sequences` |
| `tools` | `tools[type=function]` | `tools[type=function/file_search/web_search/...]` | `tools` |
| `reasoningEffort` | `reasoning_effort`（顶层） | `reasoning.effort` | `thinking.budget_tokens`（间接） |
| `responseFormat` | `response_format` | `text.format` | （无原生，须用 tool） |
| `cacheHints` | （无原生，由 Provider 自动） | （无原生） | `cache_control` on blocks |
| `stream` | `stream` | `stream` | `stream` |

**Item / Content 映射**：

| IR 类型 | Chat Completions | Responses | Anthropic |
|---------|------------------|-----------|-----------|
| `IrItem.UserMessage` | `{role:"user", content:...}` | `{role:"user", content:[...]}` (input array) | `{role:"user", content:[...]}` |
| `IrItem.AssistantMessage` | `{role:"assistant", content:..., tool_calls:[...]}` | output items: `message` + `function_call` | `{role:"assistant", content:[...blocks]}` |
| `IrItem.ToolResult` | `{role:"tool", tool_call_id:..., content:...}` | `function_call_output` item | content block: `{type:"tool_result", tool_use_id:...}` |
| `Content.Text` | string or `{type:"text", text}` | `{type:"input_text"/"output_text", text}` | `{type:"text", text, cache_control?}` |
| `Content.Image` | `{type:"image_url", image_url:{url:"data:..."}}` | `{type:"input_image", image_url:"data:..."}` | `{type:"image", source:{type:"base64", data}}` |
| `Content.ToolUse` | `tool_calls[]` (separate field) | `function_call` item (separate) | `{type:"tool_use", id, name, input}` |
| `Content.Reasoning` | (无标准字段；某些兼容服务用 `reasoning_content`) | `reasoning` item with `summary` and `encrypted_content` | `{type:"thinking", thinking, signature}` |

**StopReason 映射**：

| IR | Chat Completions `finish_reason` | Responses `status` | Anthropic `stop_reason` |
|----|----------------------------------|---------------------|-------------------------|
| END_TURN | `stop` | `completed` | `end_turn` |
| MAX_TOKENS | `length` | `incomplete`(reason=max_output_tokens) | `max_tokens` |
| TOOL_USE | `tool_calls` | `completed`(含 function_call item) | `tool_use` |
| STOP_SEQUENCE | `stop` | `completed` | `stop_sequence` |
| REFUSAL | `content_filter` | `completed`(含 refusal) | `refusal` |

#### 7.5.3 流式事件映射（SSE Transcoding）

每种协议的流式事件结构完全不同，必须严格映射。先定义 IR 流事件：

```kotlin
sealed interface IrStreamEvent {
    data class ResponseStart(val id: String, val model: String) : IrStreamEvent
    data class ItemStart(val index: Int, val item: IrItem) : IrStreamEvent
    data class TextDelta(val index: Int, val delta: String) : IrStreamEvent
    data class ReasoningDelta(val index: Int, val delta: String) : IrStreamEvent
    data class ToolUseInputDelta(val index: Int, val deltaJson: String) : IrStreamEvent
    data class ItemDone(val index: Int) : IrStreamEvent
    data class UsageUpdate(val usage: TokenUsage) : IrStreamEvent
    data class ResponseDone(val stopReason: IrStopReason, val finalUsage: TokenUsage) : IrStreamEvent
    data class Error(val message: String, val code: String?) : IrStreamEvent
}
```

**事件映射表**：

| IR 事件 | OpenAI Chat Completions | OpenAI Responses | Anthropic |
|---------|-------------------------|------------------|-----------|
| `ResponseStart` | 第一个 `data:` chunk（id 提取） | `response.created` | `message_start`（含初始 usage） |
| `ItemStart` (text) | （隐式：第一个 content delta） | `response.output_item.added` + `response.content_part.added` | `content_block_start` (text) |
| `ItemStart` (tool_use) | `choices[].delta.tool_calls[]` first delta | `response.output_item.added` (function_call) | `content_block_start` (tool_use) |
| `ItemStart` (reasoning) | （Chat 无标准） | `response.output_item.added` (reasoning) | `content_block_start` (thinking) |
| `TextDelta` | `choices[].delta.content` | `response.output_text.delta` | `content_block_delta` (text_delta) |
| `ReasoningDelta` | （无；可选 `reasoning_content`） | `response.reasoning_text.delta` / `reasoning_summary_text.delta` | `content_block_delta` (thinking_delta) |
| `ToolUseInputDelta` | `choices[].delta.tool_calls[].function.arguments` | `response.function_call_arguments.delta` | `content_block_delta` (input_json_delta) |
| `ItemDone` | （隐式） | `response.output_item.done` | `content_block_stop` |
| `UsageUpdate` | （末尾 chunk 的 `usage`） | （末尾 `response.completed` 的 `usage`） | `message_delta` (含累积 usage) |
| `ResponseDone` | `[DONE]` | `response.completed` | `message_stop` |
| `Error` | `data: {error:...}` | `response.failed` / `error` | `error` event |

**关键设计约束**：
1. Responses API 的事件粒度最细（几十种事件类型），IR 必须能容纳；某些精细事件（如 `web_search_call.searching`）映射到 `extras` 而不强制其他协议表达
2. Chat Completions ← Responses 时，reasoning items 默认丢弃（加 `X-Lost-Reasoning: true` 响应头警告）
3. Anthropic ← Responses 时，`encrypted_content`（reasoning）可以无损映射到 `thinking` block 的 `signature` 字段（语义近似）

#### 7.5.4 自动 cache 注入

当 `IrRequest` 包含 `instructions` 且其 token 数 > 1024 时：
- 序列化为 **Anthropic** 时：自动给 system block 加 `cache_control: {type:"ephemeral"}`
- 序列化为 **OpenAI**（任意）时：依赖 OpenAI 的自动 cache，不主动操作
- 用户通过 `cacheHints` 显式指定时，优先用户的

### 7.6 流式转发实现（Buffer-first-chunk）

```kotlin
suspend fun relayStream(
    chain: PoolChainRuntime,
    request: UpstreamRequest
): Flow<ServerSentEvent> = channelFlow {
    var sentFirstChunk = false
    var lastError: UpstreamError? = null

    outer@ for (level in chain.levels) {
        for (keyState in level.availableKeys()) {
            try {
                val upstream = upstreamClient.openStream(level.config.provider, keyState.key.apiKey, request)
                val firstChunk = upstream.firstOrNull() ?: continue
                // 拿到首 chunk → 提交，开始转发
                send(firstChunk)
                sentFirstChunk = true
                upstream.drop(1).collect { send(it) }
                return@channelFlow
            } catch (e: Exception) {
                if (sentFirstChunk) {
                    // 已经在发数据了，错误塞回流告知客户端
                    send(errorEvent(e))
                    return@channelFlow
                }
                lastError = classify(e)
                handleFailure(keyState, lastError!!, level.config)
                continue
            }
        }
    }
    if (!sentFirstChunk) throw AllPoolsExhaustedException(lastError)
}
```

### 7.7 ModelPricingRegistry

```kotlin
data class ModelPricing(
    val model: String,
    val provider: WireProtocol,
    val inputCostPerMTok: Double,
    val outputCostPerMTok: Double,
    val cacheCreationCostPerMTok: Double? = null,  // Anthropic
    val cacheReadCostPerMTok: Double? = null,      // Anthropic
    val cachedInputDiscount: Double? = null,       // OpenAI: 0.5 = 50% off
    val reasoningOutputCostPerMTok: Double? = null // OpenAI o-series / gpt-5 reasoning models
)

// 加载自 model_prices.json（参考 LiteLLM 格式）
class ModelPricingRegistry(private val pricings: Map<String, ModelPricing>) {
    fun get(model: String): ModelPricing = pricings[model]
        ?: pricings.entries.firstOrNull { model.startsWith(it.key) }?.value
        ?: ModelPricing(model, WireProtocol.OPENAI_CHAT, 1.0, 2.0)  // 兜底
}
```

**注意**：Responses API 和 Chat Completions 共享同一个底层模型的计费（如 `gpt-5` 两个接口同价），但 reasoning tokens 在 Responses 里能精确统计、Chat 里不一定可见。ModelPricing 不区分 OPENAI_CHAT vs OPENAI_RESPONSES，按 model 名查即可。

### 7.8 CostCalculator

```kotlin
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheCreationInputTokens: Int = 0,  // Anthropic
    val cacheReadInputTokens: Int = 0,      // Anthropic
    val cachedPromptTokens: Int = 0,        // OpenAI
    val reasoningTokens: Int = 0            // OpenAI
)

data class CostBreakdown(
    val inputCostUsd: Double,
    val outputCostUsd: Double,
    val cacheWriteCostUsd: Double,
    val cacheReadCostUsd: Double,
    val totalCostUsd: Double,
    val cacheHitRate: Double?
)

class CostCalculator(
    private val pricingRegistry: ModelPricingRegistry,
    private val userDirectory: UserDirectory
) {
    suspend fun calculate(
        model: String,
        usage: TokenUsage,
        userGroupId: String
    ): CostBreakdown {
        val pricing = pricingRegistry.get(model)
        val groupMultiplier = userDirectory.findGroup(userGroupId)?.costMultiplier ?: 1.0

        // Input cost
        val inputCost = when (pricing.provider) {
            WireProtocol.ANTHROPIC_MESSAGES -> usage.promptTokens * pricing.inputCostPerMTok / 1_000_000
            WireProtocol.OPENAI_CHAT,
            WireProtocol.OPENAI_RESPONSES -> {
                val uncached = usage.promptTokens - usage.cachedPromptTokens
                val cached = usage.cachedPromptTokens
                val discount = pricing.cachedInputDiscount ?: 0.0
                (uncached * pricing.inputCostPerMTok / 1_000_000) +
                (cached * pricing.inputCostPerMTok * (1 - discount) / 1_000_000)
            }
        }

        // Output cost
        val outputCost = if (usage.reasoningTokens > 0 && pricing.reasoningOutputCostPerMTok != null) {
            val normal = usage.completionTokens - usage.reasoningTokens
            (normal * pricing.outputCostPerMTok / 1_000_000) +
            (usage.reasoningTokens * pricing.reasoningOutputCostPerMTok / 1_000_000)
        } else {
            usage.completionTokens * pricing.outputCostPerMTok / 1_000_000
        }

        // Cache cost (Anthropic only)
        val cacheWrite = usage.cacheCreationInputTokens *
            (pricing.cacheCreationCostPerMTok ?: pricing.inputCostPerMTok) / 1_000_000
        val cacheRead = usage.cacheReadInputTokens *
            (pricing.cacheReadCostPerMTok ?: pricing.inputCostPerMTok) / 1_000_000

        val totalCost = (inputCost + outputCost + cacheWrite + cacheRead) * groupMultiplier

        val cacheHitRate = when {
            usage.cacheReadInputTokens > 0 -> {
                val totalInput = usage.promptTokens + usage.cacheCreationInputTokens + usage.cacheReadInputTokens
                usage.cacheReadInputTokens.toDouble() / totalInput
            }
            usage.cachedPromptTokens > 0 -> usage.cachedPromptTokens.toDouble() / usage.promptTokens
            else -> null
        }

        return CostBreakdown(
            inputCostUsd = inputCost * groupMultiplier,
            outputCostUsd = outputCost * groupMultiplier,
            cacheWriteCostUsd = cacheWrite * groupMultiplier,
            cacheReadCostUsd = cacheRead * groupMultiplier,
            totalCostUsd = totalCost,
            cacheHitRate = cacheHitRate
        )
    }
}
```

### 7.9 REST API

挂载在 `/api/plugins/airelay/`：

| Method | Path | Description |
|--------|------|-------------|
| POST   | `/v1/chat/completions` | OpenAI Chat Completions 兼容（含 stream） |
| POST   | `/v1/responses`        | **OpenAI Responses API 兼容**（含 stream） |
| POST   | `/v1/messages`         | Anthropic Messages 兼容（含 stream）；普通请求走 IR 路径，Claude Code 类请求在 Anthropic→Anthropic 场景走 fidelity 转发 |
| GET    | `/v1/models`           | 合并所有 chain 的模型列表 |
| GET    | `/admin/pools`         | 查看所有 pool chain 配置 |
| GET    | `/admin/pools/{chainId}/health` | 查看号池实时健康状态 |
| POST   | `/admin/pools/{chainId}/keys/{keyId}/reset` | 手动重置某个 key 状态 |

**协议选择规则**：
- 入口协议由 **URL 路径** 决定（`/v1/chat/completions` → ChatCompletions 入参）
- 出口协议由 **匹配到的 PoolChain 中目标 provider 的 `protocol`** 决定
- 默认走 IR 做 transcoding
- 当入口为 `/v1/messages` 且请求被识别为 Claude Code fidelity 模式，并且上游也为 Anthropic Messages 时，保留原始请求/响应信封（query、关键 headers、body 字段、SSE 事件）直连转发，不再走同协议 JSON 重编码
- 客户端可以用 OpenAI Responses SDK 请求 → 命中 Anthropic 上游 → 返回 Responses 格式响应（完全无感知）

---

## 8. 模块 4: Risk Control Plugin

### 8.1 职责

- 提供 `RateLimitInterceptor`，被 AIRelayPlugin 引用
- 实现 Token Bucket 算法，支持多维度（IP / userId / keyId / model）
- 规则管理：内存配置，运行时可热更新
- 暴露 `RateLimitGate` 接口给其他插件

### 8.2 数据模型

**全内存，不入库**（demo 阶段）：

```kotlin
data class RateLimitRule(
    val ruleId: String,
    val name: String,
    val dimension: RateLimitDimension,
    val pathPattern: String,          // 支持 "/v1/*" 通配
    val methods: Set<String>,         // ["POST"]
    val capacity: Int,                // 桶容量
    val refillRatePerSec: Double,     // 每秒补充
    val priority: Int = 0,            // 多规则匹配时按优先级
    val enabled: Boolean = true
)

enum class RateLimitDimension { IP, USER, API_KEY, MODEL, GLOBAL }

data class BucketKey(
    val ruleId: String,
    val dimension: RateLimitDimension,
    val value: String
) {
    fun toCacheKey(): String = "$ruleId:$dimension:$value"
}

data class Bucket(
    val key: BucketKey,
    val capacity: Int,
    val refillRatePerSec: Double,
    @Volatile var tokens: Double,
    @Volatile var lastRefillMs: Long,
    val totalAcquired: AtomicLong = AtomicLong(0),
    val totalRejected: AtomicLong = AtomicLong(0)
)
```

### 8.3 共享接口

```kotlin
// keel-contract/ai/RateLimitGate.kt
interface RateLimitGate {
    suspend fun tryAcquire(context: RateLimitContext): RateLimitDecision
}

data class RateLimitContext(
    val ip: String?,
    val userId: String?,
    val keyId: String?,
    val model: String?,
    val path: String,
    val method: String
)

sealed interface RateLimitDecision {
    data class Allowed(
        val limit: Int,
        val remaining: Int,
        val resetAtMs: Long
    ) : RateLimitDecision

    data class Rejected(
        val reason: String,
        val limit: Int,
        val retryAfterSec: Long,
        val rule: String
    ) : RateLimitDecision
}
```

### 8.4 Token Bucket 实现

```kotlin
class TokenBucketEngine(
    private val rules: AtomicReference<List<RateLimitRule>>,
    private val maxBuckets: Int = 50_000
) : RateLimitGate {
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val cleanupCounter = AtomicLong(0)

    override suspend fun tryAcquire(context: RateLimitContext): RateLimitDecision {
        val matched = rules.get()
            .filter { it.enabled }
            .filter { matchPath(it.pathPattern, context.path) }
            .filter { context.method in it.methods }
            .sortedByDescending { it.priority }

        for (rule in matched) {
            val key = buildKey(rule, context) ?: continue
            val bucket = buckets.computeIfAbsent(key.toCacheKey()) {
                Bucket(key, rule.capacity, rule.refillRatePerSec,
                    rule.capacity.toDouble(), System.currentTimeMillis())
            }
            val decision = tryAcquireBucket(bucket, rule)
            if (decision is RateLimitDecision.Rejected) return decision
        }

        // 全部通过，返回最严格的剩余信息
        return matched.firstOrNull()?.let { rule ->
            val key = buildKey(rule, context)!!
            val bucket = buckets[key.toCacheKey()]!!
            RateLimitDecision.Allowed(
                limit = rule.capacity,
                remaining = bucket.tokens.toInt(),
                resetAtMs = bucket.lastRefillMs +
                    ((rule.capacity - bucket.tokens) / rule.refillRatePerSec * 1000).toLong()
            )
        } ?: RateLimitDecision.Allowed(Int.MAX_VALUE, Int.MAX_VALUE, 0)
    }

    private fun tryAcquireBucket(bucket: Bucket, rule: RateLimitRule): RateLimitDecision {
        synchronized(bucket) {
            val now = System.currentTimeMillis()
            val elapsed = (now - bucket.lastRefillMs) / 1000.0
            bucket.tokens = (bucket.tokens + elapsed * rule.refillRatePerSec)
                .coerceAtMost(rule.capacity.toDouble())
            bucket.lastRefillMs = now

            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                bucket.totalAcquired.incrementAndGet()
                RateLimitDecision.Allowed(
                    limit = rule.capacity,
                    remaining = bucket.tokens.toInt(),
                    resetAtMs = now + ((rule.capacity - bucket.tokens) / rule.refillRatePerSec * 1000).toLong()
                )
            } else {
                bucket.totalRejected.incrementAndGet()
                val secsUntilNextToken = ((1.0 - bucket.tokens) / rule.refillRatePerSec).toLong() + 1
                RateLimitDecision.Rejected(
                    reason = "rate_limited",
                    limit = rule.capacity,
                    retryAfterSec = secsUntilNextToken,
                    rule = rule.ruleId
                )
            }
        }
    }

    private fun buildKey(rule: RateLimitRule, ctx: RateLimitContext): BucketKey? {
        val value = when (rule.dimension) {
            RateLimitDimension.IP -> ctx.ip ?: return null
            RateLimitDimension.USER -> ctx.userId ?: return null
            RateLimitDimension.API_KEY -> ctx.keyId ?: return null
            RateLimitDimension.MODEL -> ctx.model ?: return null
            RateLimitDimension.GLOBAL -> "global"
        }
        return BucketKey(rule.ruleId, rule.dimension, value)
    }

    fun snapshot(): RateLimitSnapshot = RateLimitSnapshot(
        ruleCount = rules.get().size,
        bucketCount = buckets.size,
        topBuckets = buckets.values
            .sortedByDescending { it.totalRejected.get() }
            .take(20)
            .map { it.toView() }
    )

    fun updateRules(newRules: List<RateLimitRule>) = rules.set(newRules)
}
```

### 8.5 RateLimitInterceptor

```kotlin
class RateLimitInterceptor(
    private val gate: RateLimitGate
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val ctx = RateLimitContext(
            ip = extractIp(context),
            userId = context.attributes["userId"] as? String,
            keyId = context.attributes["keyId"] as? String,
            model = extractModelFromRequest(context),  // 需要 peek request body
            path = context.rawPath,
            method = context.method
        )

        return when (val decision = gate.tryAcquire(ctx)) {
            is RateLimitDecision.Allowed -> {
                val result = next()
                injectHeaders(result, decision)
            }
            is RateLimitDecision.Rejected -> KeelInterceptorResult.reject(
                status = 429,
                message = "Rate limit exceeded (rule: ${decision.rule})",
                headers = mapOf(
                    "X-RateLimit-Limit" to listOf(decision.limit.toString()),
                    "X-RateLimit-Remaining" to listOf("0"),
                    "Retry-After" to listOf(decision.retryAfterSec.toString())
                )
            )
        }
    }

    private fun extractIp(ctx: KeelRequestContext): String? {
        return ctx.requestHeaders["X-Forwarded-For"]?.firstOrNull()?.split(",")?.first()?.trim()
            ?: ctx.requestHeaders["X-Real-IP"]?.firstOrNull()
    }
}
```

### 8.6 REST API

挂载在 `/api/plugins/riskcontrol/`：

| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET    | `/v1/rules`         | admin | 规则列表 |
| POST   | `/v1/rules`         | admin | 新增规则 |
| PUT    | `/v1/rules/{id}`    | admin | 更新规则 |
| DELETE | `/v1/rules/{id}`    | admin | 删除规则 |
| GET    | `/v1/buckets`       | admin | 活跃桶 Top 20 |
| GET    | `/v1/snapshot`      | admin | 完整快照（被 obs 调用） |
| POST   | `/v1/buckets/{key}/reset` | admin | 重置桶 |
| POST   | `/v1/reset`         | admin | 全量重置 |

---

## 9. 模块 5: Observability Plugin

### 9.1 职责

扩展现有 `ObservabilityPlugin`，新增 **AI Gateway 专属 Tab**：

- **Cost Dashboard**：实时成本（按 user / model / chain）
- **Pool Chain Health**：每个 chain 的层状态、key 健康度
- **Rate Limit Dashboard**：实时桶状态、Top 拒绝
- **Usage Top Lists**：Top users、Top models、Top errors
- **Request Stream**：最近请求的实时流（SSE）

### 9.2 实现方式

**不新建插件**，在现有 `ObservabilityPlugin` 中：

1. 通过 `registerPanel(...)` 注册新面板 `ai-gateway`
2. 增加 SSE 端点 `/api/plugins/observability/ai-gateway`
3. 数据源通过 `kernelKoin` 调用：
   - `UsageRecorder` 提供历史聚合
   - `PoolChainManager.snapshot()` 提供 pool 状态
   - `TokenBucketEngine.snapshot()` 提供 rate limit 状态

### 9.3 SSE Snapshot 数据结构

```kotlin
@Serializable
data class AIGatewaySnapshot(
    val costSummary: CostSummary,
    val poolHealth: List<PoolChainHealth>,
    val rateLimitSnapshot: RateLimitSnapshot,
    val topUsers: List<UserCostSummary>,
    val topModels: List<ModelUsageSummary>,
    val recentRequests: List<RecentRequest>
)

@Serializable
data class CostSummary(
    val last1hUsd: Double,
    val last24hUsd: Double,
    val last7dUsd: Double,
    val totalRequests: Long,
    val avgLatencyMs: Long,
    val errorRate: Double
)

@Serializable
data class PoolChainHealth(
    val chainId: String,
    val modelAliases: List<String>,
    val levels: List<LevelHealth>
)

@Serializable
data class LevelHealth(
    val levelId: String,
    val levelIndex: Int,
    val provider: String,
    val healthyKeys: Int,
    val cooldownKeys: Int,
    val disabledKeys: Int,
    val keys: List<KeyHealthView>
)

@Serializable
data class KeyHealthView(
    val keyId: String,
    val status: String,
    val totalRequests: Long,
    val totalFailures: Long,
    val currentConcurrency: Int,
    val cooldownUntilMs: Long?,
    val lastError: String?
)
```

### 9.4 前端（沿用现有 obs UI 模式）

- 在 obs 现有 SPA 里加 `ai-gateway` tab
- 该 tab 订阅 `/api/plugins/observability/ai-gateway` SSE
- 用 Recharts 渲染时序图、用列表渲染 Top N

> **范围说明**：前端实现细节不属于本 spec，按现有 obs 风格扩展即可。

---

## 10. 跨模块协议 & 数据流

### 10.1 kernelKoin 共享接口清单

| 接口 | 实现方 | 消费方 |
|------|--------|--------|
| `UserDirectory` | account plugin | token, airelay |
| `ApiKeyVerifier` | token plugin | airelay |
| `UsageRecorder` | token plugin | airelay |
| `RateLimitGate` | riskcontrol plugin | airelay |
| `ModelPricingRegistry` | airelay plugin | （内部使用，可暴露给 obs） |
| `PoolChainSnapshotProvider` | airelay plugin | observability |
| `RateLimitSnapshotProvider` | riskcontrol plugin | observability |

### 10.2 注册时序

Kernel 启动时按依赖顺序：

```
1. account.onInit() → 注册 UserDirectory 到 kernelKoin
2. token.onInit()   → 注册 ApiKeyVerifier, UsageRecorder
3. riskcontrol.onInit() → 注册 RateLimitGate
4. airelay.onInit()    → 注册 PoolChainSnapshotProvider
5. observability.onInit() → 从 kernelKoin 拉取所有 snapshot providers
```

**注意**：Keel 没有插件启动顺序约束，所以 `onStart` 中获取接口要 **lazy**（用 `get()` 而非构造时注入）。

### 10.3 关键数据流：成功请求

```
[Client]
   │ POST /api/plugins/airelay/v1/chat/completions
   │ Authorization: Bearer sk-keel-xxx
   ↓
[GatewayInterceptor] 通过
   ↓
[ApiKeyAuthInterceptor]
   │ verifier.verify("sk-keel-xxx", ip) → VerifiedKey
   │ context.attributes["keyId"] = key.keyId
   │ context.attributes["userId"] = key.userId
   ↓
[RateLimitInterceptor]
   │ gate.tryAcquire(ctx) → Allowed
   │ inject X-RateLimit-* headers
   ↓
[Handler]
   │ chain = poolChainManager.resolveChain(model)
   │ # 预扣 ⇢ token.preAuthorize(keyId, estimatedCostUsd)
   │ response = poolChainManager.relay(chain, request)
   │ usage = parseUsage(response)
   │ cost = costCalculator.calculate(model, usage, userGroupId)
   │ # 结算 ⇢ token.settle(keyId, actualCostUsd, preAuthorizedUsd)
   │ usageRecorder.record(UsageRecordInput(...))   # 异步
   │ return response (with X-Cost-USD header)
   ↓
[Client]
```

### 10.4 错误处理矩阵

| 失败位置 | HTTP 返回 | 是否扣费 | 是否记录 usage |
|----------|-----------|----------|----------------|
| API Key 无效 | 401 | 否 | 否 |
| Key 过期/吊销 | 401 | 否 | 否 |
| 模型不允许 | 403 | 否 | 否 |
| 配额耗尽 | 402 | 否 | 是（拒绝原因） |
| 限流 | 429 | 否 | 是（拒绝原因） |
| 上游 4xx | 400-499 透传 | 否 | 是 |
| 全部 pool 失败 | 503 | 否 | 是 |
| 上游成功但解析失败 | 502 | 是（已发生上游消耗） | 是 |

---

## 11. 部署 & 配置

### 11.1 注册插件到 KeelSample.kt

```kotlin
fun main() = runKeel {
    plugin(AccountPlugin())
    plugin(TokenPlugin())
    plugin(RiskControlPlugin())
    plugin(AIRelayPlugin())   // 必须在 token & riskcontrol 之后注册
    plugin(ObservabilityPlugin())

    // 保留现有
    plugin(AuthSamplePlugin())
    plugin(ProductSamplePlugin())
    plugin(OrderSamplePlugin())

    enablePluginHotReload(false)
    server { globalKtorPlugin { install(CORS) { anyHost() } } }
    routing { staticResources("/", "static") }
}
```

### 11.2 配置文件

```
keel-samples/src/main/resources/
├── airelay/
│   ├── pools.json          # PoolChain 配置（用 ${ENV_VAR} 占位）
│   └── model_prices.json   # ModelPricing（参考 LiteLLM 格式）
├── riskcontrol/
│   └── default_rules.json  # 启动时加载的默认限流规则
└── account/
    └── default_groups.json # 默认用户组（free / pro / enterprise）
```

### 11.3 环境变量

```
JWT_SECRET=<32+ random bytes>
OPENAI_KEY_1=sk-...
OPENAI_KEY_2=sk-...
ANTHROPIC_KEY_1=sk-ant-...
AZURE_KEY=...
```

### 11.4 端口与路径

| 路径 | 用途 |
|------|------|
| `:8080/api/plugins/account/v1/*` | 账户系统 |
| `:8080/api/plugins/token/v1/*`   | Key 管理 |
| `:8080/api/plugins/airelay/v1/*` | AI API（**这是给客户端用的 base URL**） |
| `:8080/api/plugins/riskcontrol/v1/*` | 风控管理 |
| `:8080/api/plugins/observability/ui/` | 可观测性页面 |

**客户端使用示例**：

OpenAI Chat Completions（legacy）：
```python
from openai import OpenAI
client = OpenAI(
    api_key="sk-keel-xxx",
    base_url="http://localhost:8080/api/plugins/airelay/v1"
)
resp = client.chat.completions.create(model="gpt-4o", messages=[...])
```

OpenAI Responses API（推荐）：
```python
from openai import OpenAI
client = OpenAI(
    api_key="sk-keel-xxx",
    base_url="http://localhost:8080/api/plugins/airelay/v1"
)
resp = client.responses.create(
    model="gpt-5",
    instructions="You are a helpful assistant.",
    input="Hello",
    reasoning={"effort": "low"}
)
print(resp.output_text)
```

Anthropic Messages：
```python
import anthropic
client = anthropic.Anthropic(
    api_key="sk-keel-xxx",
    base_url="http://localhost:8080/api/plugins/airelay"
)
resp = client.messages.create(
    model="claude-sonnet-4-20250514",
    max_tokens=1024,
    messages=[{"role": "user", "content": "Hi"}]
)
```

**重要**：上述任意一种客户端，都可以被路由到任意协议的上游 — 平台内部走 IR transcoding（参见 §7.5）。例如：
- 用 OpenAI Responses SDK 请求 `claude-sonnet-4-20250514` → 命中 Anthropic 上游
- 用 Anthropic SDK 请求 `gpt-5` → 命中 OpenAI Responses 上游
- 用 OpenAI Chat SDK 请求 `gpt-5` → 命中 OpenAI Responses 上游（自动升级利用 reasoning 优势）

---

## 12. Roadmap & 验收标准

### Phase 1: MVP（核心通路）

**目标**：能用 curl 完成一次完整的 OpenAI 透传请求，计费正确

- [ ] `keel-contract` 中定义所有共享接口
- [ ] AccountPlugin：注册/登录/JWT（不含用户组管理 UI）
- [ ] TokenPlugin：创建 Key、verify、原子扣费、写 usage_records
- [ ] AIRelayPlugin：
  - [ ] 定义 `GatewayIR`（IrRequest / IrResponse / IrStreamEvent / IrItem / IrContentPart）
  - [ ] `OpenAIChatCodec`：Chat Completions ↔ IR 双向
  - [ ] **同协议透传 first**：客户端 Chat Completions → 上游 Chat Completions（验证 IR 设计无 bug）
  - [ ] ModelPricingRegistry
  - [ ] CostCalculator（含 OpenAI cached_tokens、reasoning_tokens）
  - [ ] 单 provider 单 key 配置即可
- [ ] 验收：
  - [ ] 注册用户 → 创建 Key → 用 OpenAI SDK 调用 `gpt-4o-mini` 成功
  - [ ] usage_records 表里有正确的 prompt/completion/cost
  - [ ] OpenAI Python SDK 直接 work

### Phase 2: 多协议互转 + 故障转移 + 限流

**目标**：三协议任意互转、流式、多级故障转移、限流、cache

- [ ] AIRelayPlugin codec 矩阵：
  - [ ] `OpenAIResponsesCodec`：Responses API ↔ IR（**核心新增**）
  - [ ] `AnthropicMessagesCodec`：Anthropic Messages ↔ IR
  - [ ] 同协议透传（3 个组合）
  - [ ] 跨协议互转（6 个组合，重点 6 组都要过测试）
- [ ] AIRelayPlugin 流式：
  - [ ] `OpenAIChatCodec.decodeStream / encodeStream`
  - [ ] `OpenAIResponsesCodec.decodeStream / encodeStream`（30+ 事件类型映射）
  - [ ] `AnthropicMessagesCodec.decodeStream / encodeStream`
  - [ ] Buffer-first-chunk 故障转移
- [ ] AIRelayPlugin 计费增强：
  - [ ] Anthropic `cache_creation_input_tokens` / `cache_read_input_tokens`
  - [ ] OpenAI Responses 的精确 reasoning_tokens 计费
- [ ] AIRelayPlugin 号池：
  - [ ] PoolChainManager（多 level、cooldown、retry-after）
- [ ] RiskControlPlugin：Token Bucket、多维度、Interceptor
- [ ] ObservabilityPlugin：AI Gateway tab
- [ ] 验收：
  - [ ] OpenAI Responses SDK 请求 → Anthropic 上游 → 正确返回 Responses 格式
  - [ ] Anthropic SDK 请求 → OpenAI Responses 上游 → 正确返回 Messages 格式
  - [ ] 流式中断电（kill 上游）能自动降级到 L2
  - [ ] 同一 user 触发限流返回 429 + Retry-After
  - [ ] Anthropic prompt caching 命中后成本正确折扣
  - [ ] Obs 面板能实时看到 pool 状态变化

### Phase 3: 增强

- [ ] Responses API 的 `previous_response_id` 服务端状态代理（参见 ADR-010）
- [ ] Response cache（Redis 可选）
- [ ] 用户组管理 admin UI（基于 obs）
- [ ] CSV 账单导出
- [ ] 多实例 Redis 共享 rate limit / cache
- [ ] OAuth (GitHub) 登录
- [ ] Embedding API 支持
- [ ] Responses API 的 hosted tools 代理（file_search / web_search / code_interpreter / MCP）

### 12.1 全局验收清单

文档实现完成时应满足：

- [ ] 五个 plugin 均通过 `./gradlew :keel-samples:test` 的测试
- [ ] OpenAI Chat Completions Python SDK 调用 work（含 streaming）
- [ ] **OpenAI Responses Python SDK 调用 work（含 streaming）**
- [ ] Anthropic Messages Python SDK 调用 work（含 streaming）
- [ ] **三协议任意互转**（3×3 = 9 组合）：每个组合都有测试
  - [ ] Chat → Chat（透传）
  - [ ] Chat → Responses
  - [ ] Chat → Anthropic
  - [ ] Responses → Chat
  - [ ] Responses → Responses（透传）
  - [ ] Responses → Anthropic
  - [ ] Anthropic → Chat
  - [ ] Anthropic → Responses
  - [ ] Anthropic → Anthropic（透传）
- [ ] kill 一个 L1 key 后请求自动降级到下一个 key
- [ ] kill L1 所有 key 后请求自动降级到 L2
- [ ] 限流触发返回 429 + 正确 headers
- [ ] cache_read tokens 按 10% 计费（Anthropic）
- [ ] cached_tokens 按 50% 计费（OpenAI Chat & Responses 都要测）
- [ ] reasoning_tokens 在 Responses 出口时单独计费
- [ ] usage_records 表数据完整可审计
- [ ] obs 面板能可视化所有关键状态

---

## 附录 A: 竞品对照

| 能力 | OneAPI | LiteLLM | OpenRouter | Portkey | **本项目** |
|------|--------|---------|------------|---------|------------|
| 多 Provider 支持 | 20+ | 100+ | 100+ | 200+ | 2 协议族 |
| 协议入口：Chat Completions | ✅ | ✅ | ✅ | ✅ | ✅ |
| 协议入口：**Responses API** | ⚠ 部分 | ✅ | ✅ | ✅ | ✅ |
| 协议入口：Anthropic Messages | ✅ | ✅ | ✅ | ✅ | ✅ |
| **三协议任意互转** | ⚠ 部分 | ✅ 统一 OpenAI | ⚠ 入口转 | ✅ | ✅ **基于 IR** |
| Reasoning tokens 计费 | ⚠ | ✅ | ✅ | ✅ | ✅ |
| Responses encrypted_content | ❌ | ✅ | ✅ | ✅ | ✅ Phase 2 |
| Responses previous_response_id | ❌ | ⚠ 受限 | ✅ | ✅ | ⚠ Phase 3 |
| 负载均衡 | ✅ 自动 | ✅ 5 种策略 | ✅ | ✅ | ✅ 3 种 + P-Level |
| 故障转移 | ✅ 重试 | ✅ Order-based | ✅ | ✅ | ✅ **P-Level 多层** |
| 限流 | ✅ IP+Token | ✅ RPM/TPM + Redis | ✅ | ✅ | ✅ 多维度 Token Bucket |
| 额度管理 | ✅ 用户组倍率 | ✅ 预算 + 临时增额 | ✅ Credits | ✅ | ✅ 临时增额 + 滚动 |
| 成本追踪 | ✅ 公式计费 | ✅ 自动 USD | ✅ | ✅ | ✅ Cache-aware 精确 |
| Response Cache | ✅ Redis | ✅ Redis | ✅ | ✅ Semantic | Phase 3 |
| 上游 Cache 透传 | ⚠ 部分 | ✅ | ✅ | ✅ | ✅ Day 1 |
| 流式转发 | ✅ | ✅ | ✅ | ✅ | ✅ Buffer-first-chunk |
| 虚拟 Key | ✅ Token | ✅ Virtual Keys | ✅ | ✅ | ✅ sk-keel-xxx |
| 多租户 | ✅ 用户组 | ✅ Team/User/Org | ✅ | ✅ Workspaces | ✅ User + Group |
| 管理后台 | ✅ Web UI | ✅ Swagger | ✅ | ✅ | ✅ 复用 Obs |
| 分布式 | ✅ Master-Slave | ✅ Redis | ✅ 云服务 | ✅ 云服务 | Phase 3 |
| 热重载 | ❌ | ❌ | N/A | N/A | ✅ **Keel 原生** |

**本项目独特优势**：
1. **Keel 插件化 + 热重载**：调整限流规则、号池配置无需重启
2. **强一致的模块边界**：靠 `keel-contract` 解耦，不会出现"all-in-one"的耦合
3. **Day 1 cache-aware**：从一开始就考虑上游 cache，避免后期重构计费
4. **IR-based 三协议互转**：新增协议只需写一个 Codec，不是改 N×N 矩阵

---

## 附录 B: OpenAI Chat Completions / OpenAI Responses / Anthropic Messages 协议参考

### B.1 OpenAI Chat Completions 请求

```json
POST /v1/chat/completions
{
  "model": "gpt-4o",
  "messages": [
    { "role": "system", "content": "You are helpful" },
    { "role": "user", "content": "Hi" }
  ],
  "max_tokens": 1024,
  "temperature": 0.7,
  "stream": false
}
```

响应 usage 字段：
```json
{
  "usage": {
    "prompt_tokens": 100,
    "completion_tokens": 50,
    "total_tokens": 150,
    "prompt_tokens_details": {
      "cached_tokens": 80
    },
    "completion_tokens_details": {
      "reasoning_tokens": 0
    }
  }
}
```

### B.2 OpenAI Responses API 请求（2025-03 GA）

```json
POST /v1/responses
{
  "model": "gpt-5",
  "instructions": "You are helpful",
  "input": [
    { "role": "user", "content": [{ "type": "input_text", "text": "Hi" }] }
  ],
  "max_output_tokens": 1024,
  "temperature": 0.7,
  "reasoning": { "effort": "low" },
  "text": {
    "format": { "type": "text" }
  },
  "tools": [],
  "stream": false,
  "store": false
}
```

`input` 也可以直接是字符串（简化形式）：
```json
{ "model": "gpt-5", "input": "Hi" }
```

响应结构（注意是 `output` array，不是 `choices`）：
```json
{
  "id": "resp_abc123",
  "object": "response",
  "model": "gpt-5",
  "status": "completed",
  "output": [
    {
      "type": "reasoning",
      "id": "rs_abc",
      "summary": [{"type": "summary_text", "text": "..."}],
      "encrypted_content": "..."
    },
    {
      "type": "message",
      "id": "msg_xyz",
      "role": "assistant",
      "content": [
        { "type": "output_text", "text": "Hello!" }
      ]
    }
  ],
  "usage": {
    "input_tokens": 100,
    "input_tokens_details": { "cached_tokens": 80 },
    "output_tokens": 50,
    "output_tokens_details": { "reasoning_tokens": 30 },
    "total_tokens": 150
  }
}
```

**关键结构差异**（vs Chat Completions）：
- `instructions`（顶层）替代 `messages[role=system]`
- `input` 替代 `messages`，且每条 message 的 `content` 是 typed parts 数组
- `output` 是 **typed item array**，每个 item 有 `type`（`message` / `reasoning` / `function_call` / `file_search_call` / `web_search_call` / `code_interpreter_call` / `mcp_call` / 等）
- `max_output_tokens` 替代 `max_tokens`
- `reasoning.effort` 替代 `reasoning_effort`
- `text.format` 替代 `response_format`
- 工具结果用单独的 item 类型 `function_call_output`，不是 `messages[role=tool]`
- `usage` 是 flat 结构，`input_tokens` / `output_tokens`（不是 `prompt_tokens` / `completion_tokens`）

### B.3 Anthropic Messages 请求

```json
POST /v1/messages
Headers: anthropic-version: 2023-06-01
{
  "model": "claude-sonnet-4-20250514",
  "max_tokens": 1024,
  "system": [
    {
      "type": "text",
      "text": "You are helpful",
      "cache_control": { "type": "ephemeral" }
    }
  ],
  "messages": [
    { "role": "user", "content": [{ "type": "text", "text": "Hi" }] }
  ],
  "stream": false,
  "thinking": { "type": "enabled", "budget_tokens": 1024 }
}
```

响应：
```json
{
  "id": "msg_abc",
  "type": "message",
  "role": "assistant",
  "content": [
    { "type": "thinking", "thinking": "...", "signature": "..." },
    { "type": "text", "text": "Hello!" }
  ],
  "model": "claude-sonnet-4-20250514",
  "stop_reason": "end_turn",
  "usage": {
    "input_tokens": 100,
    "cache_creation_input_tokens": 2000,
    "cache_read_input_tokens": 0,
    "output_tokens": 50
  }
}
```

### B.4 SSE 事件结构对比

**OpenAI Chat Completions**:
```
data: {"id":"...","choices":[{"delta":{"role":"assistant"}}]}

data: {"choices":[{"delta":{"content":"Hello"}}]}

data: {"choices":[{"delta":{"content":" world"}}]}

data: {"choices":[{"finish_reason":"stop"}],"usage":{...}}

data: [DONE]
```

**OpenAI Responses API**（命名事件，结构化）:
```
event: response.created
data: {"type":"response.created","response":{"id":"resp_abc",...}}

event: response.in_progress
data: {"type":"response.in_progress","response":{...}}

event: response.output_item.added
data: {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning",...}}

event: response.reasoning_summary_text.delta
data: {"type":"response.reasoning_summary_text.delta","delta":"thinking..."}

event: response.output_item.done
data: {"type":"response.output_item.done","output_index":0,"item":{...}}

event: response.output_item.added
data: {"type":"response.output_item.added","output_index":1,"item":{"type":"message",...}}

event: response.content_part.added
data: {"type":"response.content_part.added","output_index":1,"content_index":0,"part":{"type":"output_text","text":""}}

event: response.output_text.delta
data: {"type":"response.output_text.delta","output_index":1,"content_index":0,"delta":"Hello"}

event: response.output_text.done
data: {"type":"response.output_text.done","output_index":1,"text":"Hello world"}

event: response.content_part.done
data: ...

event: response.output_item.done
data: ...

event: response.completed
data: {"type":"response.completed","response":{...,"usage":{...}}}
```

**Anthropic Messages**:
```
event: message_start
data: {"type":"message_start","message":{"id":"...","usage":{"input_tokens":100,...}}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking",...}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"..."}}

event: content_block_stop
data: {"type":"content_block_stop","index":0}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}

event: content_block_stop
data: {"type":"content_block_stop","index":1}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":50,...}}

event: message_stop
data: {"type":"message_stop"}
```

**Codec 实现要点**：
- Chat Completions 的 stream 没有结构化"item"概念 → IR `ItemStart` 由首个 `delta.content` / `delta.tool_calls[]` 触发推断
- Responses 的 stream 有最细粒度的 lifecycle 事件 → IR ↔ Responses 几乎无损映射
- Anthropic 的 stream 以 `content_block_*` 为基本单元 → IR `IrItem` 与之天然对应

---

## 附录 C: 待办与已知风险

### 待办（设计阶段未完全决定）

- [ ] **预扣额度的精确度**：如何估算 streaming 请求的最大成本？保守按 max_tokens 上限？
- [ ] **多 key 的会话亲和性**：OpenAI Responses API 的 `encrypted_content` reasoning items 跨请求复用时，需要 key 亲和（LiteLLM 已遇到 `encrypted_content_affinity` 问题），是否需要 session sticky？
- [ ] **PoolChain 配置热加载**：从文件读取 vs 通过 admin API 修改 vs 数据库？
- [ ] **JWT 撤销**：被封禁用户的存量 JWT 如何即时失效？引入黑名单 or 短 TTL？
- [ ] **Responses → Chat Completions 降级时的 reasoning 处理**：丢弃 vs 序列化为 `<thinking>...</thinking>` 文本？目前选择"丢弃 + warning header"
- [ ] **跨协议 tool schema 转换**：Chat Completions 的 function 是 non-strict default，Responses 是 strict default；如何在 IR 中表达这个差异？

### 已知风险

1. **CallTimeout 与流式请求**：Keel 的 `callTimeoutMs` 默认 3s，远小于 AI 请求。必须在 PluginDescriptor 中显式设置（60s+）
2. **maxConcurrentCalls 限制**：默认 128，流式请求会长时间占用，可能不够，需评估并适当提高
3. **Anthropic cache 最小 token 数**：< 1024 tokens 不会被缓存，自动 cache_control 时要判断
4. **协议转换损失**：
   - OpenAI Chat tool_calls ↔ Anthropic tool_use 不是 100% 双向无损（参数 strictness 默认值不同）
   - Chat Completions ← Responses 时，reasoning items 默认丢弃（无法表达）
   - Responses → Chat Completions 时，hosted tools（file_search / web_search / code_interpreter / MCP）无法降级，必须拒绝或忽略
5. **Responses API stateful 模式的代理复杂性**（参见 ADR-010）：Phase 1 强制 `store: false`；要支持 `previous_response_id` 需要在我们这层做完整的会话历史缓存
6. **Responses `instructions` vs `messages[role=system]` 的语义微妙差异**：Responses 的 `instructions` 只对当前 turn 生效（不持久），与 Chat Completions 的 system message 不完全等价；跨协议转换时这点是有损的（IR 把两者统一为 `instructions`，转出时按目标协议序列化）
7. **Encrypted content 的 cross-protocol 传递**：Responses 的 `encrypted_content` 是 OpenAI 私有密文，无法转给 Anthropic 上游；遇到必须在 transcoding 时剥离并加 warning
8. **Responses tools 数量爆炸**：`file_search` / `web_search` / `code_interpreter` / `computer_use` / `mcp` / `image_generation` 这些"hosted tools"是 OpenAI 服务端执行的，本质无法 transcode 到 Anthropic（除非我们在 IR 层自己实现这些工具的执行）。Phase 1/2 不做，直接拒绝

---

**文档结束。**

实现时按 §4 的契约约束，按 §12 的 Roadmap 顺序推进。任何对架构的偏离请回到此文档更新对应 ADR。
