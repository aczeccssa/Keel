# Keel Samples

示例应用模块，展示如何使用 Keel 框架构建插件化后端系统。

## 目录结构

```
keel-samples/
├── src/
│   ├── main/
│   │   ├── kotlin/com/keel/samples/
│   │   │   ├── KeelSample.kt              # 应用入口
│   │   │   ├── helloworld/                 # Hello World 示例插件
│   │   │   ├── dbdemo/                     # 数据库示例插件
│   │   │   ├── ordersample/                # 订单示例插件
│   │   │   ├── productsample/              # 商品示例插件
│   │   │   ├── authsample/                 # 认证示例插件
│   │   │   ├── observability/              # 可观测性插件
│   │   │   ├── commerce/                   # 电商目录
│   │   │   └── aigateway/                  # AI 网关插件
│   │   │       ├── airelay/                # AI Relay（渠道/模型/定价管理）
│   │   │       ├── token/                  # Token 管理
│   │   │       ├── customerportal/         # 客户门户
│   │   │       └── account/                # 账户管理
│   │   └── resources/ui/                   # 前端 UI 资源
│   └── tools/                              # 工具脚本
│       └── kotlin/
│           └── ExportH2DataTask.kt         # H2 数据导出工具
└── build.gradle.kts
```

## 示例插件

| 插件 | 说明 |
|------|------|
| `helloworld` | 最简单的插件示例，展示基本路由和 DTO |
| `dbdemo` | 数据库操作示例，使用 Exposed ORM |
| `ordersample` | 订单 CRUD，展示 Repository 模式 |
| `productsample` | 商品管理，展示 Upsert 操作 |
| `authsample` | 认证授权示例 |
| `observability` | 可观测性仪表盘 |
| `aigateway/airelay` | AI 网关中继，管理渠道、模型、定价 |

## 运行

```bash
# 从项目根目录
./gradlew :keel-samples:run

# 或使用 application 插件
./gradlew :keel-samples:installDist
./keel-samples/build/install/keel-samples/bin/keel-samples
```

应用启动后访问：
- AI Gateway Console: `http://localhost:8080/api/plugins/airelay/ui/`
- Customer Portal: `http://localhost:8080/api/plugins/customer-portal/ui/`
- API 网关: `http://localhost:8080/api/plugins/{pluginId}`
- Swagger UI: `http://localhost:8080/swagger-ui`

## Sample frontends

`keel-samples` includes two React + Vite frontends:

- AI Gateway Console: `/api/plugins/airelay/ui/`
- Customer Portal: `/api/plugins/customer-portal/ui/`

Build them directly with:

```bash
./gradlew :keel-samples:buildSampleFrontends
```

They are also built automatically as part of `:keel-samples:processResources`, so `./gradlew :keel-samples:run` serves the latest compiled bundles.

## 工具任务

### exportH2Data

导出 AI Relay 配置数据（渠道、模型、定价）为 CSV 文件。

**前置条件**：启动 H2 TCP 服务器

```bash
# 找到 H2 jar 路径
H2_JAR=$(find ~/.gradle/caches -name "h2-*.jar" | head -1)

# 启动 TCP 服务器
java -cp "$H2_JAR" org.h2.tools.Server -tcp -tcpPort 9092
```

**运行导出**：

```bash
# 使用默认数据库路径 (~/.keel/keel-data/)
./gradlew :keel-samples:exportH2Data

# 指定数据库路径
./gradlew :keel-samples:exportH2Data -PdbFolder=/path/to/db/
```

**输出文件**：`keel-samples/build/exports/`

| 文件 | 内容 |
|------|------|
| `airelay_channel.csv` | 渠道配置（含加密密钥） |
| `airelay_group.csv` | 路由组 |
| `airelay_channel_membership.csv` | 渠道-组关系 |
| `airelay_model.csv` | 模型映射（含 credit_multiplier） |
| `airelay_model_pricing.csv` | 独立定价（含 credit_multiplier） |
| `airelay_model_pricing_tier.csv` | 分层定价 |
| `airelay_group_alias.csv` | 组别名（含 credit_multiplier） |

**不导出的数据**（启动时自动创建）：
- Account 用户（admin@example.com）
- Account 用户组（free/pro/enterprise）
- 调用日志和 Token 记录

## 项目依赖

- `keel-core` - 框架核心
- `keel-contract` - 接口契约
- `keel-exposed-starter` - 数据库 ORM 支持
- `keel-openapi-annotations` - OpenAPI 注解
- `keel-openapi-runtime` - OpenAPI 运行时

## 技术栈

- Kotlin / JVM 23
- Ktor (HTTP 服务器)
- Exposed (ORM)
- H2 Database (嵌入式数据库)
- Koin (依赖注入)
- kotlinx.serialization (JSON 序列化)
