# Keel

<p align="center">
  <img src="assets/brand/keel%20logo%203x.png" alt="Keel logo" width="180" />
</p>

<p align="center">
  <strong>A Ktor-based modular monolith kernel for building pluginized backend systems on a single JVM.</strong>
</p>

<p align="center">
  Keel lets you package business modules as plugins, route them through one gateway, manage lifecycle at runtime, and keep API contracts discoverable through generated OpenAPI docs.
</p>

## Why Keel

Most backend systems do not need the operational cost of microservices, but they do outgrow a flat monolith quickly. Keel is aimed at that middle ground:

- Keep one deployable runtime and one gateway.
- Split business capabilities into explicit plugin boundaries.
- Enable and disable modules at runtime.
- Support both in-process plugins and isolated plugin JVMs.
- Expose typed APIs and aggregate them into one OpenAPI surface.

This makes Keel suitable for internal platforms, modular SaaS backends, plugin-based products, and systems that need strong module boundaries without fragmenting deployment too early.

## What You Get

- Unified plugin gateway under `/api/plugins/{pluginId}`.
- Runtime plugin lifecycle management: register, start, stop, dispose, reload, replace, discover.
- Dual runtime modes: `IN_PROCESS` and `EXTERNAL_JVM`.
- Development hot reload for plugin source modules (enabled per plugin via `plugin(..., hotReloadEnabled = true)`).
- Typed route DSL for plugin APIs and system APIs.
- OpenAPI 3.1 aggregation with Swagger UI.
- Koin-based dependency injection.
- Exposed starter for plugin-scoped tables, repository helpers, audit fields, and soft delete patterns.
- Built-in observability primitives with logs, topology, traces, flows, and SSE streaming support.
- Sample application with `helloworld`, `dbdemo`, and `observability` plugins.

## Project Status

Keel is an open-source project under active development. The repository already contains:

- Core kernel runtime and plugin manager.
- OpenAPI annotation, processor, and runtime modules.
- UDS runtime support for plugin communication.
- Sample plugins and integration-style tests.

The public API and module boundaries are visible and usable today, but you should still treat the project as evolving infrastructure rather than a frozen platform.

## Architecture

Keel keeps a single host application as the kernel. Plugins contribute routes, lifecycle hooks, dependency modules, static resources, and optional isolated runtime behavior.

```text
Client
  |
  v
Keel Kernel (Ktor + Gateway + System APIs + OpenAPI + Observability)
  |
  +-- /api/plugins/helloworld
  +-- /api/plugins/dbdemo
  +-- /api/plugins/observability
  |
  +-- in-process plugins
  |
  +-- external JVM plugins
```

At startup, the kernel:

1. Builds the Ktor application and shared infrastructure.
2. Registers plugins and mounts their routes.
3. Exposes system endpoints under `/api/_system`.
4. Starts enabled plugins and observability services.
5. Watches development module directories and applies hot reload rules in development mode.

## Repository Layout

```text
keel-core                Kernel runtime, routing, lifecycle, gateway, observability
keel-contract            Shared DTOs and response contracts
keel-exposed-starter     Database integration and repository/table helpers
keel-openapi-annotations OpenAPI annotations used by plugin/system APIs
keel-openapi-processor   KSP processor for OpenAPI fragments
keel-openapi-runtime     OpenAPI aggregation and Swagger UI routes
keel-uds-runtime         UDS protocol/runtime support for plugin isolation
keel-test-suite          Kernel, routing, DB, config, loader, and contract tests
keel-samples             Runnable sample app and sample plugins
assets/brand             Project brand assets and logo materials
```

## Quick Start

### Requirements

- JDK `23`
- macOS, Linux, or another environment that can run the Gradle wrapper

### Run the sample app

```bash
./gradlew :keel-samples:run
```

Default port is `8080`.

After startup, these endpoints are useful:

- `http://localhost:8080/`
- `http://localhost:8080/api/plugins/helloworld`
- `http://localhost:8080/api/plugins/helloworld/version`
- `http://localhost:8080/api/plugins/dbdemo/notes`
- `http://localhost:8080/api/plugins/observability/topology`
- `http://localhost:8080/api/plugins/observability/ui`
- `http://localhost:8080/api/_system/health`
- `http://localhost:8080/api/_system/plugins`
- `http://localhost:8080/api/_system/docs/`
- `http://localhost:8080/api/_system/docs/openapi.json`

### Build and test

```bash
./gradlew build
./gradlew test
./gradlew dokka
```

### CI/CD

Keel uses GitHub Actions for CI, GitHub Packages releases, and Dokka docs.

CI (Linux) runs:

```bash
./gradlew build dokkaGenerate
```

Code quality runs in a dedicated Qodana workflow on `pull_request` and `main`.

Release (tag `v*`) publishes artifacts to GitHub Packages and creates a GitHub Release.

Required GitHub secrets:

- `QODANA_TOKEN` (recommended for Qodana Cloud integration)

Docs are published to GitHub Pages from `dokkaGenerateHtml`.

## Configuration

Keel supports both system properties and environment variables.

| Purpose | System property | Environment variable | Default |
| --- | --- | --- | --- |
| Runtime environment | `-Dkeel.env=development` | `KEEL_ENV=development` | `production` |
| Server port | `-Dkeel.port=8080` | `KEEL_PORT=8080` | `8080` |

Default directories:

- Config directory: `config/`
- Plugin artifact directory: `plugins/`

Hot reload behavior:

- Development mode enables config watching by default.
- Plugin directory watching can be toggled through `runKernel { enablePluginHotReload(true | false) }`.

### Dev HotReload Trigger Rules

Dev HotReload is source-oriented and only applies to plugins registered with `hotReloadEnabled = true`.

Will trigger hot reload attempt:

- Files under `src/main/**` in watched plugin modules
- Files under `src/commonMain/**` in watched plugin modules
- Files under `src/main/resources/**` or `src/commonMain/resources/**` in watched plugin modules

Will NOT trigger hot reload attempt:

- Changes under `build/**` (compiled artifacts and intermediate outputs)
- Changes in paths that cannot be classified as plugin source/resource changes

Will be marked as `RESTART_REQUIRED` (no hot swap):

- `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
- Kernel module source changes (for example in `keel-core`)

Notes:

- Default watch scope is caller module + recursive Gradle `project(...)` dependencies.
- Calling `watchDirectories(...)` overrides the default watch scope.
- Endpoint topology changes (method/path set changes) are not hot-swapped and are treated as restart-required.
- Manual dev reload API: `POST /api/_system/hotreload/reload/{pluginId}`.
- Legacy `pluginSource(...)` remains available for compatibility but is deprecated.

## Developer Experience

Keel is designed around a typed plugin API instead of raw route wiring. A plugin provides:

- A `PluginDescriptor`
- Lifecycle hooks such as `onInit`, `onStart`, `onStop`, `onDispose`
- Optional Koin modules
- Typed endpoint definitions

Minimal example:

```kotlin
class MyPlugin : KeelPlugin {
    override val descriptor = PluginDescriptor(
        pluginId = "myplugin",
        version = "1.0.0",
        displayName = "My Plugin"
    )

    override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
        get<String>("/ping") {
            PluginResult(body = "pong")
        }
    }
}
```

Register the plugin in the kernel:

```kotlin
fun main() {
    runKernel(port = 8080) {
        plugin(
            plugin = MyPlugin(),
            enabled = true,
            hotReloadEnabled = true
        )
    }
}
```

## System APIs

Keel exposes system management endpoints under `/api/_system`.

### Core endpoints

- `GET /api/_system/health`
- `GET /api/_system/plugins`
- `GET /api/_system/plugins/{pluginId}`
- `GET /api/_system/plugins/{pluginId}/health`
- `POST /api/_system/plugins/{pluginId}/start`
- `POST /api/_system/plugins/{pluginId}/stop`
- `POST /api/_system/plugins/{pluginId}/dispose`
- `POST /api/_system/plugins/{pluginId}/reload`
- `POST /api/_system/plugins/{pluginId}/replace`
- `POST /api/_system/plugins/discover`
- `GET /api/_system/hotreload/status`
- `GET /api/_system/hotreload/events` (SSE)
- `POST /api/_system/hotreload/reload/{pluginId}`

### Documentation endpoints

- `GET /api/_system/docs`
- `GET /api/_system/docs/openapi.json`

## Included Samples

The sample application demonstrates three plugin styles already present in the repository:

- `helloworld`: minimal plugin endpoints and typed responses
- `dbdemo`: Exposed-backed CRUD-style plugin module
- `observability`: topology, traces, flows, UI assets, and streaming-oriented behavior

These samples are the best starting point if you want to understand how Keel is intended to be used in practice.

## Performance & Stress Test Report

Keel's core framework components have been systematically benchmarked under high-concurrency conditions to validate architectural performance and stability. All tests reside in `keel-test-suite/src/test/kotlin/com/keel/test/perf/` and can be run with:

```bash
./gradlew :keel-test-suite:test --tests "com.keel.test.perf.*"
```

Additional HTTP load testing scripts (k6) are available in `scripts/`.

### Test Environment

- **Platform**: macOS, JDK 23
- **Database**: H2 in-memory (via HikariCP connection pool)
- **Test harness**: JUnit 5 + kotlinx.coroutines

---

### 1. Plugin Lifecycle Manager (`UnifiedPluginManager`)

The plugin lifecycle state machine uses per-plugin `Mutex` locks and `ConcurrentHashMap` for plugin registry, enabling independent plugin operations to run fully in parallel.

| Benchmark | Result | Throughput |
|---|---|---|
| Register 200 plugins | 6 ms | 33,333 ops/sec |
| Concurrent start 100 plugins | 3 ms | 33,333 ops/sec |
| 50 start/stop cycles (sequential) | 3 ms | 16,667 cycles/sec |
| 50 concurrent lifecycle ops on same plugin | 4 ms | Mutex contention minimal |
| 30 reload cycles (stop → dispose → start) | 9 ms | 3,333 reloads/sec |
| 100K dispatch lookups across 50 plugins | 7 ms | **14.3M ops/sec** |

**Key finding**: Per-plugin locking ensures zero cross-plugin contention. Dispatch disposition lookup from `ConcurrentHashMap` averages **22 ns/op**, confirming negligible overhead on the hot request path.

---

### 2. Gateway Interceptor (`GatewayInterceptor`)

The gateway uses a pre-compiled regex to extract `pluginId` from request paths, then resolves dispatch disposition via `ConcurrentHashMap` lookup.

| Benchmark | Iterations | Avg Latency |
|---|---|---|
| `resolveDispatchDisposition` lookup | 500,000 | **22 ns/op** |
| Regex `extractPluginId` | 1,000,000 | **102 ns/op** |
| Combined path parsing + dispatch resolution | 500,000 | **127 ns/op** |
| Non-plugin path short-circuit | 1,000,000 | **46 ns/op** |

**Key finding**: The full gateway interception path (regex + lookup) adds only **~127 ns** per request. Non-plugin system paths (`/api/_system/*`) short-circuit in 46 ns. At 100K QPS, total gateway overhead would be ~12.7 ms/sec — effectively free.

---

### 3. Event Bus (`DefaultKeelEventBus`)

The event bus uses `MutableSharedFlow(extraBufferCapacity = 64)` for inter-plugin communication with typed subscription support.

| Benchmark | Published | Throughput | Delivery Rate |
|---|---|---|---|
| Single producer (50K events) | 50,000 | 35,893 events/sec | 99.99% |
| Multi-producer (10 × 5K events) | 50,000 | **287,356 events/sec** | 100% |
| Typed subscription (2 event types) | 4,000 | — | 100% (both types) |
| Buffer pressure test (10K burst) | 10,000 | — | 99.93% (~0.07% drop) |

**Key finding**: Multi-producer throughput scales well to **287K events/sec**. Under extreme buffer pressure (burst publish without consumer backpressure), a ~0.07% drop rate is observed due to `extraBufferCapacity = 64`. For zero-loss guarantees, consider increasing `extraBufferCapacity` or switching to `Channel` with `UNLIMITED` capacity for critical event paths.

---

### 4. Plugin Scope Manager (`PluginScopeManager` / Koin)

Each plugin gets an isolated `KoinApplication` with a private scope, created and destroyed during lifecycle transitions.

| Benchmark | Result | Throughput |
|---|---|---|
| 200 rapid create/close cycles | 15 ms | 13,333 cycles/sec |
| Concurrent creation of 100 scopes | 5 ms | 20,000 scopes/sec |
| Heavy DI graph (50 beans/scope) × 50 cycles | 13 ms | 3,846 cycles/sec |
| 100 scope re-creations (simulating reload) | 7 ms | 14,286 re-creations/sec |

**Key finding**: Koin scope isolation is lightweight. Even with 50 beans per scope, create/close throughput exceeds 3,800 cycles/sec. Plugin reload (which involves scope destruction and recreation) completes in under 0.1 ms per cycle.

---

### 5. Database Layer (`DatabaseFactory` + Exposed + HikariCP)

Tests use H2 in-memory database with `PluginTable` prefix isolation, validating connection pool queuing behavior under contention.

| Benchmark | Config | Throughput |
|---|---|---|
| Sequential insert (5K rows) | pool=5 | 5,359 inserts/sec |
| Concurrent insert (20 coroutines) | pool=5 | **13,378 inserts/sec** |
| Mixed read/write (5W + 10R, 200 ops each) | pool=10 | **36,585 ops/sec** |
| Connection pool exhaustion (20× concurrency) | pool=2 | 28,571 inserts/sec |
| Transaction with retry (100 ops) | pool=3 | 20,000 ops/sec |

**Key finding**: HikariCP gracefully handles 10× pool oversubscription without any failed requests — connections queue rather than reject. Mixed read/write throughput reaches 36K ops/sec with a pool of 10. The `PluginTable` prefix naming imposes no measurable overhead.

---

### Summary

| Component | Hot-Path Latency | Concurrency Safety | Bottleneck Risk |
|---|---|---|---|
| Plugin Manager | 22 ns dispatch lookup | Per-plugin Mutex, no global lock | ✅ Low |
| Gateway Interceptor | 127 ns full path | Stateless, lock-free | ✅ Low |
| Event Bus | < 1 ms publish | Lock-free SharedFlow | ⚠️ Buffer overflow under extreme burst |
| Koin Scope | < 0.1 ms create/close | ConcurrentHashMap | ✅ Low |
| Database (H2) | ~ 0.07 ms/insert | HikariCP pool queuing | ⚠️ Pool size tuning required for production DBs |

## Tech Stack

- Kotlin `2.3.0`
- Ktor `3.4.0`
- Koin `4.0.0`
- Exposed `0.61.0`
- kotlinx.serialization `1.8.0`
- kotlinx.datetime `0.6.2`
- kotlinx.coroutines `1.9.0`
- OpenTelemetry `1.38.0`
- Dokka `2.1.0`

## Open Source

Keel is released as open-source software under the MIT License.

What this means in practice:

- You can use it commercially.
- You can modify and redistribute it.
- You must retain the license notice.
- It is provided without warranty.

See [LICENSE](LICENSE) for the full text.

## Contributing

Issues and pull requests are welcome.

When contributing, keep these constraints in mind:

- Preserve clear plugin boundaries.
- Avoid cross-plugin coupling.
- Prefer typed APIs over ad hoc route behavior.
- Add or update tests when changing lifecycle, routing, OpenAPI, or database behavior.

If you are evaluating Keel for real use, opening an issue with your use case is useful signal. It helps shape the kernel surface area more effectively than generic feature requests.

## Brand Assets

Project logo assets live in [assets/brand](assets/brand).

- Source SVG: [assets/brand/keel-logo.svg](assets/brand/keel-logo.svg)
- PNG exports: `keel logo 1x.png`, `keel logo 2x.png`, `keel logo 3x.png`

## License

MIT. See [LICENSE](LICENSE).
