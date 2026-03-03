# 📝 快速开发框架 PRD：Modular Monolith Kernel (K2 Edition)

## 1. 项目愿景 (Project Vision)

构建一个基于 Ktor 3.4.0 的进程内微服务架构（模块化单体）。通过插件化实现业务隔离，支持在不重启 JVM
的情况下逻辑启停插件。该框架定位为“快速开发”，开发者应能在 5 分钟内通过继承基类上线一个功能完备的独立业务模块。

## 2. 核心技术栈 (Technology Stack Constraints)

**AI 助手必须严格遵守以下版本，禁止擅自降级：**

* **Language:** Kotlin `2.3.0` (K2 Compiler)
* **Web Framework:** Ktor `3.4.0` (Netty)
* **Dependency Injection:** Koin `4.0.0`+ (利用其 Scope 特性)
* **Database ORM:** JetBrains Exposed `0.61.0`
* **Serialization:** `kotlinx.serialization` (JSON)
* **DateTime:** `kotlinx.datetime 0.6.2` (禁止使用 `java.time.*`)
* **Documentation:** Dokka `2.1.0`
* **Build System:** Gradle Kotlin DSL (多模块配置)

---

## 3. 核心架构设计 (Architecture Principles)

### 3.1 物理隔离层

#### 模块目录结构

```
keel/
├── buildSrc/ (或 Version Catalog)       # 管理 Kotlin 2.3.0, Ktor 3.4.0 等全局版本
├── keel-core/                          # 【核心内核】
├── keel-contract/                      # 【契约接口】
├── keel-exposed-starter/               # 【数据支撑】
├── keel-test-suite/                    # 【测试工具】
├── plugins/                            # 【预置业务插件目录】
│   ├── keel-plugin-admin/              # Admin 管理插件
│   ├── keel-plugin-auth/               # 鉴权插件
│   └── keel-plugin-eventbus/           # 事件总线实现
└── keel-samples/                       # 示例代码
```

#### 模块职责

1. **`:keel-core`**: 框架发动机。包含 Ktor 引擎配置、Koin 全局容器、插件加载器、全局拦截器。
2. **`:keel-contract`**: 核心契约。存放所有插件共用的 `Interface`、`DTO` 和 `Event` 类。**禁止包含业务实现逻辑。**
3. **`:keel-exposed-starter`**: 数据支撑。提供 Exposed 连接池、`PluginTable` 基类、自动建表逻辑等数据库基础设施。
4. **`:keel-test-suite`**: 测试工具。提供插件单元测试基类、集成测试 Mock 工具。
5. **`:plugins:*`**: 业务插件层。例如 `:plugins:keel-plugin-auth`。每个插件是一个独立的子模块。
6. **`:keel-samples`**: 示例代码。包含演示插件开发流程的 Sample 代码。

* **依赖方向限制:** `Plugin -> keel-core` & `Plugin -> keel-contract`。
* **严禁依赖:** `Plugin A -> Plugin B`。

### 3.2 插件生命周期 (Life Cycle)

每个插件必须实现 `KPlugin` 抽象接口，由内核按顺序调度：

1. **`onInit`**: 插件加载，初始化配置，向内核注册 Exposed `Table`。
2. **`onInstall`**: 创建属于该插件的私有 `Koin Scope`，注册内部服务。
3. **`onEnable`**: 激活路由。内核将插件路由挂载至 `/api/plugins/{pluginId}/`。
4. **`onDisable`**: 切断流量，销毁 `Koin Scope`，释放内存。

---

## 4. 关键功能规约 (Key Specifications)

### 4.1 动态路由网关 (Gateway Interceptor)

* **设计模式**: **逻辑开关优于物理注销**。
* **实现**: 内核注册一个全局 `onCall` 拦截器。它根据请求路径解析出 `pluginId`，并查询 `PluginManager` 状态。
* **行为**: 若插件状态为 `DISABLED` 或 `ERROR`，拦截器直接中断请求并返回 `503 Service Unavailable`。

### 4.2 数据库隔离与事务 (Database & Transaction)

* **表前缀强制**: 所有 Exposed Table 必须通过 `PluginTable(name, pluginId)` 定义，自动生成 `${pluginId}_${name}` 格式的物理表名。
* **隔离原则**: 严禁跨插件 SQL Join。
* **跨插件一致性**: 插件 A 事务完成后，通过 `EventBus` 发送异步消息，插件 B 在自己的事务中处理。

### 4.3 插件间通信 (Inter-Plugin Communication)

* **同步调用**: 仅限于调用定义在 `shared-contracts` 中的接口。内核通过 Koin 获取实例，若目标插件未启用，则抛出异常或返回
  Null。
* **异步调用 (推荐)**: 核心内置基于 `MutableSharedFlow` 的 `EventBus`。

```kotlin
// 强制使用 kotlinx.datetime 处理时间戳
data class PluginEvent(val id: String, val timestamp: Instant)

```

---

## 5. 开发阶段路线图 (Implementation Roadmap)

### Phase 1: 基础设施 (Kernel Setup)

* [ ] 配置根项目 `build.gradle.kts` 锁定全局版本。
* [ ] 实现 `KPlugin` 抽象类与 `PluginManager` 状态机。
* [ ] 集成 Koin，实现基于 `pluginId` 的 `Scope` 自动创建与销毁逻辑。

### Phase 2: 动态网络层 (Routing & Gateway)

* [ ] 实现 Ktor 全局拦截器，执行插件状态预检。
* [ ] 编写路由包装器，实现自动前缀分发：`/api/plugins/{pluginId}/*`。

### Phase 3: 隔离数据层 (Data Isolation)

* [ ] 配置 Exposed 连接池。
* [ ] 实现 `PluginTable` 基类，确保 `kotlinx-datetime` 0.6.2 的兼容性映射。
* [ ] 编写自动建表逻辑（由内核在插件启动阶段触发）。

### Phase 4: 预置管理插件 (Admin Plugin)

* [ ] 开发 `admin-plugin`，提供可视化的接口用于监控各插件 CPU/内存占用（基于 Micrometer）及手动启停开关。

---

## 6. 🚫 严禁事项 (Red Lines)

1. **禁止** 使用 `java.time.*`，AI 必须使用 `kotlinx.datetime` 的 `toKotlinInstant()` 等扩展函数。
2. **禁止** 在插件中定义没有前缀的物理表名。
3. **禁止** 在 `core-kernel` 中直接 `import` 任何具体插件包。
4. **禁止** 绕过拦截器直接在 `routing {}` 中硬编码路径。
