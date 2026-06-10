# Keel Frontend Shadcn Redesign Design

Date: 2026-06-11
Branch: `claude/extract-keel-samples-frontends`

## Goal

把 `keel-samples/frontend` 下的全部前端 UI 完整迁移为 React + shadcn/ui 实现，在保留现有业务功能、API 合约、存储键、导航语义和后端静态挂载方式不变的前提下，彻底移除两个应用对 `src/legacy/js/app.js` 和 `src/legacy/css/style.css` 的运行时依赖。

## Current Context

当前 `keel-samples/frontend` 是一个 npm workspace，包含：

- `packages/ui`：轻量共享 UI 包，当前不是 shadcn 基础设施
- `apps/customer-portal`
- `apps/ai-gateway`

当前真正对外提供的页面入口仍然是两个应用各自的 `index.html`，它们直接挂载：

- `customer-portal`: `<customer-app></customer-app>` + `/src/legacy/js/app.js`
- `ai-gateway`: `<ai-proxy-app></ai-proxy-app>` + `/src/legacy/js/app.js`

React 面板和共享 UI 目前只覆盖了很薄的一层脚手架，远未达到 legacy 功能等价：

- `customer-portal` legacy 包含登录/注册切换、credits dashboard、redeem code、ledger 筛选、usage 记录、create/delete key、connect snippet、多客户端代码片段、pricing tiers 等
- `ai-gateway` legacy 包含 live dashboard、usage filters、groups CRUD、users/group management、customers detail modal、redemption codes 管理、details modal、筛选、轮询/SSE fallback、富表格和多处行动流

因此本次不能把任务定义为“样式升级”。它本质上是一次 **完整功能迁移 + shadcn UI 重建**。

## Approved Approach

采用“**共享层先行 + 双应用并行重建 + 统一验证收口**”。

原因：

1. `big-bang` 一次性整体替换风险过高，容易复现此前重构后整体回退的历史。
2. 分应用串行虽然风险更小，但容易让两个 app 的设计系统和交互抽象逐步漂移。
3. 先建立共享 shadcn 基础层，再并行迁移两个 app，可以同时保证视觉一致性和迁移效率，并把真正的回归风险集中在可验证的行为层。

## Architecture

### Shared UI Layer

`packages/ui` 重建为 Keel 定制的 shadcn/ui 共享层，负责：

- shadcn primitives 和 Radix-based building blocks
- 主题 tokens 和 CSS variables
- `cn()`、variant helpers、theme helpers
- `AppShell`
- 统一按钮、输入框、表格、tabs、dialog、sheet、dropdown、badge、alert、skeleton、tooltip、scroll area 等基础组件
- 统一的 `EmptyState`、`ErrorState`、`LoadingState`
- 可复用的面板级容器、section header、stat tiles、data cards、toolbar filters
- 品牌资产、logo、主题切换

该层只收纳真实跨应用复用能力，不吸收业务流程或产品专有数据变换逻辑。

### App Ownership

#### `apps/customer-portal`

负责完整迁移并重建：

- marketing / landing 入口
- login / register flow
- dashboard
- billing / credits
- pricing
- keys
- redeem flow
- connect snippets / copy actions
- usage records

#### `apps/ai-gateway`

负责完整迁移并重建：

- marketing / landing 入口
- admin login / register flow
- dashboard
- usage
- providers / channels
- groups
- keys
- pools
- pricing
- rate limits
- users
- customers
- redemption codes

### Runtime and Integration Constraints

以下行为保持不变：

- API 路径不变
- auth 模型不变
- localStorage 主题键 `keel-theme-pref` 不变
- hash / tab 导航语义不变，除非现有 React 侧只是未完成脚手架且与 legacy 行为冲突
- Vite dev 代理 `/api -> http://localhost:8080`
- 后端静态挂载 URL 不变
- 构建仍需兼容后端静态资源集成模式

### Legacy Removal Criterion

只有当 React + shadcn 版本为某个页面提供了完整功能等价能力，且验证通过后，才允许移除对应 legacy 入口依赖。最终完成时：

- `apps/customer-portal/index.html` 不再引用 `/src/legacy/css/style.css` 或 `/src/legacy/js/app.js`
- `apps/ai-gateway/index.html` 不再引用 `/src/legacy/css/style.css` 或 `/src/legacy/js/app.js`

## Functional Parity Strategy

### Rule 1: Migrate Behavior Before Deleting Legacy

每个 legacy 页面先抽取“行为清单”，再逐项在 React 中重建，不允许只做标题/表格级别的占位实现。

### Rule 2: UI Shell May Change, Semantics May Not

允许变化：

- 视觉风格
- 容器结构
- 组件表面语言
- modal/sheet/dialog 呈现方式
- 布局层次

不允许擅自变化：

- 字段职责
- API 调用顺序
- 错误分支
- 按钮作用
- 复制/提交/删除/刷新语义
- 筛选逻辑
- 自动刷新逻辑
- 主题键和 auth 存储语义

### Rule 3: Functional Panels Must Reach Legacy Depth

必须补齐的典型差距包括但不限于：

- `customer-portal` 的 redeem、ledger filters、usage table、key creation/deletion、client snippets、pricing tier detail
- `ai-gateway` 的 users/groups 管理、customer detail modal、redemption code operations、usage filters、live updates、rich dashboard cards、table row actions

## Shadcn Mapping

共享组件映射策略：

- app shell / sidebar / nav: shadcn primitives + custom `AppShell`
- forms: `Form`-style composition with `Label`, `Input`, `Select`, `Textarea`, `Button`
- dialogs and action flows: `Dialog` / `Sheet`
- tabbed code snippets and secondary navigation: `Tabs`
- data tables: shared `DataTable` built on semantic table primitives
- row actions: `DropdownMenu`, inline buttons, `Badge`
- filters and dense toolbars: `Input`, `Select`, `Button`, `Tooltip`
- empty/loading/error: `Skeleton`, `Alert`, bespoke empty state blocks
- metric panels and stat groups: custom shared dashboard primitives on top of shadcn surfaces

## Visual System

### Design Direction

使用 Keel 定制的 premium SaaS 风格，而不是 shadcn 默认外观：

- 浅色模式优先，冷白和柔和中性灰为主
- 深色模式为一等公民，但基于同一几何系统重绘
- 主体字体采用更有性格的 sans-serif，避免 Inter 默认感
- telemetry、IDs、token、snippets 使用精致 monospace
- 减少通用三栏卡片 AI 风格，更多使用层次化 sections、宽面板、数据分区

### Color and Surface Constraints

- 不再沿用当前 legacy 的工业黄褐色体系
- 不使用 AI 紫/霓虹蓝默认风格
- 使用单一 restrained accent
- 大面使用 near-white / cool-neutral surfaces
- card 阴影克制、边框轻、focus/ring 明确

### Motion

仅在不改变业务交互语义的前提下引入轻量运动：

- hover / active tactile feedback
- skeleton shimmer
- dialog / sheet / dropdown transitions
- theme transition restraint

不在首轮实现中混入高风险的复杂滚动编排或与业务无关的炫技动效。

## Testing and Verification

“100% 功能点不变” 的验收使用四层证据链：

### 1. Unit and Contract Tests

- API clients
- auth state
- navigation/hash logic
- theme helpers
- shared UI behavioral utilities

### 2. Panel Behavior Tests

每个 migrated panel 必须覆盖真实用户动作，而不是只断言标题存在。重点包括：

- login/register mode switches
- create/update/delete flows
- redeem flows
- filters / search / tabs
- modal / dialog open-close
- copy snippet flows
- loading / empty / error states
- live refresh or polling behavior where applicable

### 3. Browser Regression

通过浏览器自动化验证关键路径：

- unauthenticated entry
- sign in / sign up
- route or tab switching
- key management
- redemption flows
- admin management flows
- responsive shell behavior
- theme persistence

### 4. Backend-Served Integration

必须验证：

- Vite dev server 版本
- 后端静态挂载版本

最终证据要求包含构建、测试、浏览器验证和入口文件替换结果。

## Done Criteria

以下条件全部满足才算完成：

1. `frontend` 下全部 UI 使用 React + shadcn 共享层承载。
2. 两个 app 的 runtime 入口不再依赖 legacy JS/CSS app entry。
3. `packages/ui` 已成为 Keel 定制 shadcn 基础层。
4. `customer-portal` 和 `ai-gateway` 的所有 legacy 业务功能都在 React 中重建。
5. 现有 API contract、auth 语义、theme key、后端静态挂载 URL 保持兼容。
6. 四层验证证据链全部通过。

## Non-Goals

本次不做：

- 后端 API 协议改造
- 插件 URL 或挂载点改名
- auth 模型重构
- 为了省事保留 legacy app 作为混合运行时
- 引入与任务无关的大型动画或 3D 运行时

