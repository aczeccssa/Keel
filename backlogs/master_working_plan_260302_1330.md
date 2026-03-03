# Keel Framework Master Working Plan

## Project Overview
- **Project Name**: Keel - Modular Monolith Kernel
- **Version**: K2 Edition (Kotlin 2.3.0)
- **Core Technology**: Ktor 3.4.0, Koin 4.0.0+, Exposed 0.61.0, kotlinx.datetime 0.6.2

## Phase 1: Infrastructure Setup (Kernel Setup)

### 1.1 Gradle Multi-Module Configuration
- [ ] Configure `settings.gradle.kts` with all required modules:
  - `:keel-core` - Framework engine
  - `:keel-contract` - Common interfaces, DTOs, Events
  - `:keel-exposed-starter` - Database infrastructure
  - `:keel-test-suite` - Testing tools
  - `:plugins:keel-plugin-admin` - Admin plugin
  - `:plugins:keel-plugin-auth` - Auth plugin
  - `:plugins:keel-plugin-eventbus` - Event bus
- [ ] Configure version catalog in `gradle/libs.versions.toml`
- [ ] Set up root `build.gradle.kts` with global versions

### 1.2 KPlugin Interface & PluginManager
- [ ] Create `KPlugin` abstract class in `keel-core`
  - `abstract val pluginId: String`
  - `abstract val version: String`
  - `abstract suspend fun onInit(context: PluginInitContext)`
  - `abstract suspend fun onInstall(scope: KoinScope)`
  - `abstract suspend fun onEnable(routing: Routing)`
  - `abstract suspend fun onDisable()`
- [ ] Implement `PluginState` enum (INIT, INSTALLED, ENABLED, DISABLED, ERROR)
- [ ] Implement `PluginManager` with state machine logic

### 1.3 Koin Integration
- [ ] Set up global KoinApplication in `keel-core`
- [ ] Implement `PluginScopeManager` for creating/destroying plugin scopes
- [ ] Configure scope lifecycle binding to plugin states

---

## Phase 2: Dynamic Network Layer (Routing & Gateway)

### 2.1 Gateway Interceptor
- [ ] Implement global `GatewayInterceptor` (Ktor Interceptor)
- [ ] Add plugin status pre-check logic in interceptor
- [ ] Return 503 for disabled/error plugins
- [ ] Implement path parsing for `/api/plugins/{pluginId}/*`

### 2.2 Route Management
- [ ] Create `PluginRouteWrapper` for automatic prefix routing
- [ ] Implement dynamic route mounting in PluginManager
- [ ] Add route prefix validation

---

## Phase 3: Data Isolation Layer

### 3.1 Exposed Integration
- [ ] Configure Exposed connection pool in `keel-exposed-starter`
- [ ] Implement `PluginTable` base class with prefix enforcement
- [ ] Ensure `kotlinx.datetime` 0.6.2 compatibility (NO java.time.*)

### 3.2 Auto Schema Generation
- [ ] Implement table auto-creation logic triggered on plugin install
- [ ] Add migration support framework

---

## Phase 4: Admin Plugin

### 4.1 Admin Plugin Implementation
- [ ] Create `keel-plugin-admin` module
- [ ] Implement plugin status monitoring endpoints
- [ ] Add manual enable/disable toggle API
- [ ] Integrate Micrometer for CPU/memory metrics

---

## Phase 5: Business Plugins

### 5.1 Auth Plugin (JWT/RBAC)
- [ ] Create `keel-plugin-auth` module
- [ ] Implement JWT token generation/validation
- [ ] Implement RBAC authorization
- [ ] Create auth tables using PluginTable

### 5.2 User Plugin
- [ ] Create `keel-plugin-user` module
- [ ] Implement user CRUD operations
- [ ] Define user-related DTOs in contract

### 5.3 EventBus Plugin
- [ ] Create `keel-plugin-eventbus` module
- [ ] Implement EventBus using MutableSharedFlow
- [ ] Add event subscription/publishing APIs

---

## Phase 6: Hot Reload

### 6.1 Configuration Hot Reload
- [ ] Implement StateFlow-based config management
- [ ] Add directory watcher for config changes
- [ ] Implement reload trigger for plugin configs
- [ ] Add development mode detection

---

## Critical Constraints

### Version Compliance (NON-NEGOTIABLE)
- Kotlin: 2.3.0 (K2 Compiler)
- Ktor: 3.4.0
- Koin: 4.0.0+
- Exposed: 0.61.0
- kotlinx.datetime: 0.6.2
- Dokka: 2.1.0

### Architecture Rules
- **ALLOWED**: Plugin -> keel-core, Plugin -> keel-contract
- **FORBIDDEN**: Plugin A -> Plugin B, any plugin importing another plugin's packages

### Database Rules
- All Tables MUST use `PluginTable(name, pluginId)`
- Physical table names: `${pluginId}_${name}` format
- NO cross-plugin SQL joins

### Routing Rules
- All routes MUST go through Gateway Interceptor
- NO hardcoded paths bypassing interceptor

### Prohibited
- NO `java.time.*` - Use `kotlinx.datetime` only
- NO unprefixed table names
- NO direct plugin imports in core-kernel

---

## Verification Checklist

### Build Verification
- [ ] `./gradlew build` passes
- [ ] `./gradlew :keel-core:compileKotlin` passes
- [ ] All module compilations pass

### Functional Verification
- [ ] Dynamic enable/disable works via API
- [ ] Gateway interceptor correctly blocks disabled plugins
- [ ] Plugin prefix enforced on database tables

---

## File Structure Target

```
keel/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   └── libs.versions.toml
├── keel-core/
│   └── src/main/kotlin/com/keel/kernel/
├── keel-contract/
│   └── src/main/kotlin/com/keel/contract/
├── keel-exposed-starter/
│   └── src/main/kotlin/com/keel/db/
├── keel-test-suite/
│   └── src/main/kotlin/com/keel/test/
├── plugins/
│   ├── keel-plugin-admin/
│   ├── keel-plugin-auth/
│   ├── keel-plugin-eventbus/
│   └── keel-plugin-user/
└── backlogs/
    └── master_working_plan.md
```

## 2026-03-02 Code Review & Stabilization Plan

### Baseline Comparison (Keel vs Plain Ktor + Exposed)
- **Convenience**: Keel adds plugin lifecycle, hot reload hooks, unified API responses, and system routes. This is more convenient than wiring Ktor + Exposed manually.
- **Tradeoff**: Current correctness gaps (hot reload, logging severity, stubbed CRUD) reduce production stability.

### Key Findings (from code review)
1. **ConfigHotReloader watch loop blocks additional watchers**  
   File: `keel-core/src/main/kotlin/com/keel/kernel/config/ConfigHotReloader.kt`  
   Impact: Only one watched directory reliably emits events.

2. **Config/plugin path matching fails with relative paths**  
   File: `keel-core/src/main/kotlin/com/keel/kernel/config/ConfigHotReloader.kt`  
   Impact: Events drop because absolute paths never startWith relative strings.

3. **CRUD delete extension is a stub**  
   File: `keel-exposed-starter/src/main/kotlin/com/keel/db/repository/CrudRepository.kt`  
   Impact: Callers get false negatives; behavior is misleading.

4. **Error logs emitted as info (core logger)**  
   File: `keel-core/src/main/kotlin/com/keel/kernel/logging/KeelLoggerService.kt`  
   Impact: Severity classification broken; alerting/filters unreliable.

5. **Error logs emitted as info (DB logger)**  
   File: `keel-exposed-starter/src/main/kotlin/com/keel/db/logging/DbScopeLogger.kt`  
   Impact: Same as above for DB operations.

6. **GlobalScope used for hot reload jobs**  
   File: `keel-core/src/main/kotlin/com/keel/kernel/config/Kernel.kt`  
   Impact: No lifecycle supervision; possible leaks on shutdown.

### Stabilization Plan (Phased TODOs)

#### Phase 1 — Correctness & Stability (must-fix)
- [ ] Fix hot-reload watcher loop to service all WatchServices (one coroutine per watcher or poll with timeout).
- [ ] Normalize config/plugin directories to absolute paths and compare with Path.startsWith.
- [ ] Replace GlobalScope in hot-reload with a Kernel- or reloader-owned CoroutineScope and cancel on shutdown.
- [ ] Use error-level logging for error messages in both KeelLoggerService and DbScopeLogger.

#### Phase 2 — API & Developer Experience
- [ ] Remove `CrudRepository.delete(entity)` or make it fail fast with NotImplementedError.
- [ ] If retained, implement delete in terms of a required ID extractor or standard entity contract.
- [ ] Add tests for hot-reloader behavior: multi-watch, relative/absolute path handling.

#### Phase 3 — Production Hardening
- [ ] Add tests for plugin loader lifecycle (load/unload/reload).
- [ ] Add structured logging fields (pluginId, version, action) for operator clarity.
- [ ] Implement PluginConfig loading (currently TODO) with explicit enabled/disabled state.

### Success Criteria
- Hot reload works for both config and plugin directories simultaneously.
- Relative and absolute config paths are handled correctly.
- Error logs are classified at error level in both core and DB loggers.
- CRUD extension no longer returns false by default.
- Tests cover hot reload and plugin loader lifecycle behaviors.
- Keel remains more convenient than plain Ktor + Exposed with stable production behavior.

### Tests to Add / Run
- ConfigHotReloader:
  - Multi-watch behavior emits events from both directories.
  - Relative path config dir still matches emitted events.
- Plugin loader lifecycle:
  - Load/unload/reload transitions and classloader cleanup.
- Logging:
  - Error logs use error-level sinks for both core and DB loggers.
