# keel-samples 前端构建产物资源管道改造规范

> 本文档是一份**自包含的执行规范**：仅凭本仓库当前状态 + 本文档，即可 100% 正确完成改造，无需查阅其他任何资料。
>
> 改造范围**严格限定在 `keel-samples` 模块内**。不触碰 `.claude/`、`keel-core`、`keel-contract`、`keel-test-suite`、`keel-openapi-*`、根 `build.gradle.kts`、`settings.gradle.kts`、CI、Docker 等任何其他模块或基础设施。

---

## 0. 一句话目标

把 React/Vite 前端构建产物从**污染 sourceSet**（被声明为资源源目录 + check-in 到 `src/main/resources/ui/`）改为**在 `processResources` 构建阶段注入**（`from(...)`），并彻底从 git 中删除 `src/main/resources/ui/` 下的两个构建产物目录 `ai-gateway-ui/`、`customer-portal-ui/`。

---

## 1. 现状与问题诊断

### 1.1 当前的污染机制

`keel-samples/build.gradle.kts` 第 10–14 行：

```kotlin
val generatedFrontendResources = layout.buildDirectory.dir("generated-resources/frontend")

sourceSets {
    named("main") {
        resources.setSrcDirs(listOf(generatedFrontendResources, "src/main/resources"))
    }
    ...
}
```

问题：`resources.setSrcDirs(...)` 把**构建产物目录** `build/generated-resources/frontend` 直接声明为 `main` sourceSet 的资源源目录之一。这违反 Gradle 约定——sourceSet 应只包含**源码**，构建产物应在构建任务阶段（`processResources`）注入。

### 1.2 同时存在的 check-in fallback 副本

`keel-samples/src/main/resources/ui/` 下有 4 个子目录，角色不同：

| 子目录 | 是否对应 `frontend/apps/*` | 内容形态 | 本次处理 |
|---|---|---|---|
| `ai-gateway-ui/` | ✅ `frontend/apps/ai-gateway` | Vite 构建产物（`index.html` + `assets/index-<hash>.js/.css`） | **删除** |
| `customer-portal-ui/` | ✅ `frontend/apps/customer-portal` | 旧版未打包遗留资产（`index.html` + `js/` + `css/`） | **删除** |
| `observability-ui/` | ❌ 无对应 frontend app | 手写源资源（`index.html` + `js/` + `css/` + `favicon.svg`） | **保留** |
| `authsample-ui/` | ❌ 无对应 frontend app | 手写源资源（单文件 74KB 内联 `index.html`） | **保留** |

`ai-gateway-ui/` 与 `customer-portal-ui/` 是构建产物冒充源码的污染；`observability-ui/` 与 `authsample-ui/` 是没有对应 Vite 工程的**真正手写源资源**，被运行时 `basePackage` 直接引用，必须保留。

### 1.3 双份存在的隐患

同一套文件同时存在于：
- `src/main/resources/ui/<app>-ui/`（check-in 的 fallback）
- `build/generated-resources/frontend/ui/<app>-ui/`（Gradle 构建产物）

靠 `setSrcDirs` 列表顺序 + `DuplicatesStrategy.EXCLUDE` 隐式决定 generated 优先。一旦顺序写错或策略改为 `INCLUDE`，可能跑的是过期 fallback；且 Vite hash 后缀文件每次构建产生无意义 git diff。

---

## 2. 设计决策：为什么用 `processResources.from()` 而非 KSP

用户提出"前端 build 结果应该用 KSP 或别的办法影响 build 结果，而不是影响 sourceSet"。

- **KSP（Kotlin Symbol Processing）** 用于 Kotlin 符号处理与代码生成，不适合静态资源打包，排除。
- **正确的 Gradle 机制**：`processResources` 是 `ProcessResources`（继承 `Copy`）任务，其职责就是把资源源 + 额外内容合并到 `build/resources/main/`。通过 `from(generatedFrontendResources)` 在构建阶段注入前端产物，**既影响最终 build 结果（产物进入 classpath），又不修改 sourceSet 定义**。这正是"别的办法"的标准答案。

终态：`main` sourceSet 的资源源恢复为默认的 `src/main/resources`（只含手写源资源）；前端构建产物仅在 `processResources` 阶段合并进 `build/resources/main/ui/<app>-ui/`。

---

## 3. 目标终态

改造完成后必须满足以下全部条件：

1. `keel-samples/build.gradle.kts` 中**不再有** `resources.setSrcDirs(...)` 对 `main` sourceSet 的覆写；`main` 资源源恢复为默认 `src/main/resources`。
2. `keel-samples/build.gradle.kts` 的 `tasks.processResources` 中新增 `from(generatedFrontendResources)`，保留 `dependsOn(syncCustomerPortalFrontend, syncAiGatewayFrontend)` 与 `duplicatesStrategy = DuplicatesStrategy.EXCLUDE`。
3. `keel-samples/src/main/resources/ui/` 下**仅剩** `observability-ui/` 与 `authsample-ui/` 两个目录；`ai-gateway-ui/` 与 `customer-portal-ui/`（含全部内容）从 git 与工作区中删除。
4. 新建 `keel-samples/.gitignore`，防御性地排除上述两个已删目录路径，防止后续误提交构建产物。
5. 两个 `legacyParity.test.ts`（其比较目标正是被删除的 `src/main/resources/ui/<app>-ui/`）被删除。
6. 运行时行为不变：`basePackage = "ui/ai-gateway-ui"` / `"ui/customer-portal-ui"` 仍能从 classpath 解析到最新 Vite 构建产物。
7. `keel-samples` 之外零改动。

---

## 4. 逐文件改动清单

### 4.1 `keel-samples/build.gradle.kts`

#### 改动 A：注释 + sourceSet 块（约第 8–22 行）

**BEFORE：**

```kotlin
// Generated React/Vite bundles are staged here and added to the main resources
// classpath ahead of checked-in fallback assets.
val generatedFrontendResources = layout.buildDirectory.dir("generated-resources/frontend")

sourceSets {
    named("main") {
        resources.setSrcDirs(listOf(generatedFrontendResources, "src/main/resources"))
    }

    create("tools") {
        kotlin.srcDir("src/tools/kotlin")
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath
    }
}
```

**AFTER：**

```kotlin
// React/Vite bundles are staged here by the sync*Frontend tasks and merged into the
// main resources at the processResources stage (see tasks.processResources below).
// They are NEVER declared as a resource source directory — build output stays out of
// the source set. The classpath location ui/<app>-ui/ is produced solely at build time.
val generatedFrontendResources = layout.buildDirectory.dir("generated-resources/frontend")

sourceSets {
    // The "main" source set is intentionally left at its defaults (src/main/kotlin,
    // src/main/resources). Frontend build artifacts are NOT added as a resource srcDir;
    // they are injected via processResources.from(...) so the source set is never
    // polluted by generated bundles.
    create("tools") {
        kotlin.srcDir("src/tools/kotlin")
        compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
        runtimeClasspath += sourceSets["main"].output + sourceSets["main"].runtimeClasspath
    }
}
```

要点：删除整个 `named("main") { resources.setSrcDirs(...) }` 块。`main` sourceSet 随之恢复 Kotlin JVM 插件默认值（`src/main/kotlin` + `src/main/resources`）。`tools` sourceSet 块保持原样。

#### 改动 B：`processResources` 任务（约第 131–134 行）

**BEFORE：**

```kotlin
tasks.processResources {
    dependsOn(syncCustomerPortalFrontend, syncAiGatewayFrontend)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

**AFTER：**

```kotlin
tasks.processResources {
    dependsOn(syncCustomerPortalFrontend, syncAiGatewayFrontend)
    // Inject the generated frontend bundles into the classpath at build time.
    // src/main/resources stays the sole resource source dir; generated content is
    // merged here, never declared as a source directory.
    from(generatedFrontendResources)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

要点：仅新增一行 `from(generatedFrontendResources)`。其余（`dependsOn`、`duplicatesStrategy`）保持不变。`from()` 会把 `build/generated-resources/frontend/` 的内容按相对路径合并进 `build/resources/main/`，即 `ui/ai-gateway-ui/index.html` 等落到位，运行时 `basePackage` 可正常解析。

#### 不动的部分

以下任务/配置**保持原样**，不要改：
- `installFrontendDependencies`、`buildCustomerPortalFrontend`、`buildAiGatewayFrontend`（npm 构建）
- `syncCustomerPortalFrontend`、`syncAiGatewayFrontend`（仍把 `dist` 同步到 `build/generated-resources/frontend/ui/<app>-ui/`，去向不变）
- `buildSampleFrontends`、`exportH2Data`、`aiRelayBenchmark*`
- `application`、`dependencies`、`kotlin { jvmToolchain(23) }`

### 4.2 删除 `keel-samples/src/main/resources/ui/ai-gateway-ui/`（整目录）

git 跟踪的文件（HEAD）：
```
keel-samples/src/main/resources/ui/ai-gateway-ui/assets/index-DzEstDH8.css
keel-samples/src/main/resources/ui/ai-gateway-ui/assets/index-EGQNgzsu.js
keel-samples/src/main/resources/ui/ai-gateway-ui/index.html
```

> 注意：当前工作区在此目录下已有未提交改动（`index.html` 被改、`index-EGQNgzsu.js` 被删、`index-xtm7kBgD.js` 新增未跟踪）。这些改动本就是构建产物噪音，删除整目录时一并清除，符合预期。

### 4.3 删除 `keel-samples/src/main/resources/ui/customer-portal-ui/`（整目录）

git 跟踪的文件（HEAD）：
```
keel-samples/src/main/resources/ui/customer-portal-ui/css/style.css
keel-samples/src/main/resources/ui/customer-portal-ui/index.html
keel-samples/src/main/resources/ui/customer-portal-ui/js/api.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/app.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/auth.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/PanelBilling.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/PanelDashboard.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/PanelKeys.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/PanelLogin.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/PanelPricing.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/base/KeelElement.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/shared/KeelChart.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/shared/KeelDataTable.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/components/shared/KeelStatGrid.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/config.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/state.js
keel-samples/src/main/resources/ui/customer-portal-ui/js/utils.js
```

### 4.4 新建 `keel-samples/.gitignore`

当前 `keel-samples/` 下**没有** `.gitignore`（已确认）。新建，内容如下（路径相对于 `keel-samples/`，前导 `/` 锚定到模块根）：

```gitignore
# Frontend (React/Vite) build artifacts must never be committed to source resources.
# They are produced at build time under build/generated-resources/frontend/ and merged
# into the classpath by the processResources task. Do not check them into src/main/resources.
# See keel-samples/build.gradle.kts and keel-samples/docs/frontend-resource-pipeline-spec.md.
/src/main/resources/ui/ai-gateway-ui/
/src/main/resources/ui/customer-portal-ui/
```

### 4.5 删除两个 `legacyParity.test.ts`

```
keel-samples/frontend/apps/ai-gateway/src/legacyParity.test.ts
keel-samples/frontend/apps/customer-portal/src/legacyParity.test.ts
```

**删除理由（必须理解，不要试图保留或改写）：**

这两个测试的核心断言是把 `frontend/apps/<app>/src/legacy/`（前端源）与 `keel-samples/src/main/resources/ui/<app>-ui/`（后端静态副本）做逐文件内容相等比较。其前提是"后端单独服务一份未打包的 legacy 副本，且必须与前端 src/legacy 保持一致"。

本次改造**正是要删除这个后端静态副本**——后端不再服务未打包 legacy 文件，改为服务 Vite 构建产物（`assets/index-<hash>.{js,css}`，文件布局与 `js/`+`css/` 完全不同）。因此：

- 比较目标 `src/main/resources/ui/<app>-ui/` 被删除后，测试中 `readdirSync(sourceRoot)` 会直接 `ENOENT` 失败。
- 即便改指向 `dist/` 或 `build/generated-resources/...`，Vite 产物是 `assets/index-<hash>.{js,css}` 单文件 bundle，与 `src/legacy` 的 `js/app.js`、`css/style.css` 多文件结构不可逐文件比较，断言语义已不成立。
- 该迁移 parity 守卫的前提已结构性消失，**唯一正确做法是删除**。不要改写、不要指向其他路径。

## 5. 执行步骤（按顺序）

以下命令均从当前 worktree 的**仓库根目录**执行：

`/Users/a/Documents/GitHub/OpenSource/Keel/.claude/worktrees/condescending-wing-549f3d`

```bash
# 1) 删除两个构建产物目录（-f 用于丢弃已知的构建产物噪音）
git rm -r -f keel-samples/src/main/resources/ui/ai-gateway-ui
git rm -r -f keel-samples/src/main/resources/ui/customer-portal-ui

# 2) 清除工作区残留的未跟踪文件（如 index-xtm7kBgD.js）与空目录
rm -rf keel-samples/src/main/resources/ui/ai-gateway-ui \
       keel-samples/src/main/resources/ui/customer-portal-ui

# 3) 删除两个 legacy parity 测试
git rm keel-samples/frontend/apps/ai-gateway/src/legacyParity.test.ts
git rm keel-samples/frontend/apps/customer-portal/src/legacyParity.test.ts

# 4) 编辑 keel-samples/build.gradle.kts（改动 A、B，见 4.1）
#    新建 keel-samples/.gitignore（见 4.4）
#    —— 用编辑器按 4.1 / 4.4 的 BEFORE/AFTER 精确替换
```

---

## 6. 验证（每步都必须通过）

```bash
cd /Users/a/Documents/GitHub/OpenSource/Keel/.claude/worktrees/condescending-wing-549f3d

# 6.1 sourceSet 不再被覆写
grep -n "setSrcDirs" keel-samples/build.gradle.kts
# 期望：无输出

# 6.2 processResources 注入生成产物
grep -n "from(generatedFrontendResources)" keel-samples/build.gradle.kts
# 期望：命中一行

# 6.3 src/main/resources/ui 仅剩两个手写源目录
ls keel-samples/src/main/resources/ui/
# 期望：仅 authsample-ui  observability-ui

# 6.4 gitignore 生效（防御性验证）
touch keel-samples/src/main/resources/ui/ai-gateway-ui/x 2>/dev/null || \
  mkdir -p keel-samples/src/main/resources/ui/ai-gateway-ui && \
  touch keel-samples/src/main/resources/ui/ai-gateway-ui/x
git check-ignore keel-samples/src/main/resources/ui/ai-gateway-ui/x
# 期望：打印出该路径（表示被忽略）
rm -rf keel-samples/src/main/resources/ui/ai-gateway-ui   # 清理临时验证文件

# 6.5 构建前端产物
./gradlew :keel-samples:buildSampleFrontends
# 期望：BUILD SUCCESSFUL
ls keel-samples/build/generated-resources/frontend/ui/ai-gateway-ui/index.html \
   keel-samples/build/generated-resources/frontend/ui/customer-portal-ui/index.html
# 期望：两个文件都存在

# 6.6 processResources 把产物合并进 classpath
./gradlew :keel-samples:processResources
ls keel-samples/build/resources/main/ui/
# 期望：包含 ai-gateway-ui  authsample-ui  customer-portal-ui  observability-ui 四者

# 6.7 运行时端点服务最新构建产物
./gradlew :keel-samples:run &
# 等待启动后：
curl -s http://localhost:8080/api/plugins/airelay/ui/ | grep -o 'assets/index-[^"]*\.js'
# 期望：打印出当前 hash 的 js 文件名（与 build 产物一致）
# 停止：kill %1 或对应进程

# 6.8 前端测试（parity 测试已删，其余应通过）
cd keel-samples/frontend && npm run test:run && cd -
# 期望：通过（不再有 legacyParity 用例）

# 6.9 全量测试（keel-test-suite 通过 classpath 取资源）
./gradlew test
# 期望：BUILD SUCCESSFUL

# 6.10 改动范围确认：本次改造仅涉及 keel-samples
git status --short
# 对照执行前的工作区状态，确认本次改造只新增以下路径的改动。
# 执行前已存在的其他未提交改动不属于本次改造，不应删除或覆盖：
#   keel-samples/.gitignore                 (新增)
#   keel-samples/build.gradle.kts           (修改)
#   keel-samples/src/main/resources/ui/ai-gateway-ui/*       (删除)
#   keel-samples/src/main/resources/ui/customer-portal-ui/*  (删除)
#   keel-samples/frontend/apps/ai-gateway/src/legacyParity.test.ts       (删除)
#   keel-samples/frontend/apps/customer-portal/src/legacyParity.test.ts  (删除)
```

---

## 7. 明确不需要改动的项（防止误改）

| 项 | 状态 | 说明 |
|---|---|---|
| `keel-samples/src/main/resources/ui/observability-ui/` | 保留 | 手写源资源，运行时 `basePackage="ui/observability-ui"` 引用，无对应 Vite 工程 |
| `keel-samples/src/main/resources/ui/authsample-ui/` | 保留 | 手写源资源，`AuthSamplePlugin` `staticResources("/showcase", "ui/authsample-ui", ...)` 引用 |
| `AIRelayPlugin.kt:811` `basePackage = "ui/ai-gateway-ui"` | 不动 | 运行时从 classpath 解析，不依赖文件系统源路径 |
| `CustomerPortalPlugin.kt:332` `basePackage = "ui/customer-portal-ui"` | 不动 | 同上 |
| `KeelSample.kt:92` `basePackage = "ui/observability-ui"` | 不动 | observability 手写资源保留 |
| `ObservabilityPlugin.kt:160` | 不动 | 同上 |
| `sync*Frontend` / `build*Frontend` / `installFrontendDependencies` 任务 | 不动 | 仍向 `build/generated-resources/frontend/ui/<app>-ui/` 输出，去向不变 |
| `RUN_AND_VERIFY.md` 第 34–37 行 | 不动 | 其描述的产物路径 `build/generated-resources/frontend/ui/<app>-ui` 改造前后完全一致 |
| `frontend/package.json` 的 `build:customer` / `build:ai-gateway` 脚本 | 不动 | npm workspace 名，与 Gradle 资源路径正交 |
| `settings.gradle.kts` / 根 `build.gradle.kts` / 其他模块 `build.gradle.kts` | 不动 | 扫描确认无相关引用 |
| `.github/` CI、Dockerfile、shell 脚本 | 不动 | 扫描确认无相关引用 |
| `docs/superpowers/{plans,specs,specs-result}/2026-06-*` 历史文档 | 不动 | 历史设计记录，叙述性，不影响运行/构建；如需可事后标注"实现已变更"，但非本次必需 |

---

## 8. 前置条件与风险

1. **Node.js + npm 现为 keel-samples 的硬性构建前置**。删除 check-in fallback 后，`./gradlew :keel-samples:run` / `:processResources` / `test` 均会触发 `npm install` + `npm run build`。无 Node 环境将构建失败（这是有意的：宁可显式失败，也不服务过期 fallback）。
2. 全新 clone 后首次构建会执行 `npm install`（较慢），属正常。
3. 若 CI 环境无 Node，需在 CI 安装 Node（当前 CI 未引用这些路径，但若未来加入 keel-samples 构建须保证 Node 可用）。
4. `duplicatesStrategy = EXCLUDE` 在改造后已非必需（`src/main/resources` 与 generated 不再有重叠目录），但**保留**作为防御，无副作用。

---

## 9. 回滚方案

改动全部局部化于单次提交，回滚即 `git revert <commit>`，被删除的 `*-ui/` 目录与 parity 测试将从历史完整恢复。无需任何手动恢复操作。

---

## 10. 完成定义（Definition of Done）

以下全部为真，即视为改造完成：

- [ ] §4.1 改动 A、B 已应用，`grep setSrcDirs` 无输出，`grep from(generatedFrontendResources)` 命中
- [ ] §4.2、§4.3 两个目录已从 git 与工作区删除
- [ ] §4.4 `keel-samples/.gitignore` 已创建且 `git check-ignore` 验证生效
- [ ] §4.5 两个 `legacyParity.test.ts` 已删除
- [ ] §6 全部验证步骤通过
- [ ] §6.10 对照执行前的工作区状态，本次改造没有在 `keel-samples` 之外引入新改动
