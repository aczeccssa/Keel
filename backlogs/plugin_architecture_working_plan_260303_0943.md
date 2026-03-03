# Plugin Architecture Working Plan

## Summary

This plan replaces the current split plugin architecture with one public plugin model and one manager that supports two runtime modes:

- `IN_PROCESS`
- `EXTERNAL_JVM`

`EXTERNAL_JVM` means a separate JVM process communicating with the kernel over Unix domain sockets. It does not mean another thread inside the kernel JVM.

This revision also tightens the UDS design so kernel to sub-JVM communication cannot stall behind a single blocking request path. The transport must support structured request/response plus controlled runtime events, with explicit anti-blocking rules, stronger liveness truth, bounded memory behavior, and lane isolation that is independent from normal business invokes.

## Output File

Use a filename matching the current backlog naming style:

`/Users/a/Documents/GitHub/OpenSource/Keel/backlogs/plugin_architecture_working_plan_260303_0943.md`

This follows the existing lowercase underscore plus timestamp style already present in `backlogs`.

## Decisions Locked

- migration strategy: immediate cutover
- external mode: separate JVM process only
- reload scope: production-supported lifecycle
- backlog filename style: lowercase underscore with timestamp suffix
- UDS transport must avoid information blockage between kernel and sub-JVM
- kernel status must be derived from kernel-observed truth, not plugin self-report alone
- low-priority logs may be dropped under pressure, lifecycle and failure events may not
- external plugin termination authority belongs to the kernel process supervisor

## Target Outcomes

After implementation:

- there is exactly one plugin API
- all plugins use one lifecycle, one config schema, one manager, and one system route model
- both runtime modes behave the same from the kernel’s perspective
- plugin reload, dispose, replace, stop, and start are real lifecycle operations
- plugin DI supports both kernel-global dependencies and plugin-private scope dependencies
- UDS transport supports request/response and runtime signaling without head-of-line blocking
- the kernel can distinguish `RUNNING`, `DEGRADED`, `UNREACHABLE`, and process-dead states from real supervisor signals
- large external payload handling is explicit instead of relying on one raw frame path

## Public API and Interface Changes

### Unified plugin API

Create one public contract:

```kotlin
interface KeelPlugin {
    val descriptor: PluginDescriptor

    suspend fun onInit(context: PluginInitContext) {}
    suspend fun onStart(context: PluginRuntimeContext) {}
    suspend fun onStop(context: PluginRuntimeContext) {}
    suspend fun onDispose(context: PluginRuntimeContext) {}
    fun modules(): List<Module> = emptyList()
    fun endpoints(): List<PluginEndpointDefinition<*, *>>
}
```

### Endpoint execution policy

Plugin-level timeout defaults remain, but every endpoint can override them.

```kotlin
data class EndpointExecutionPolicy(
    val timeoutMs: Long? = null,
    val maxPayloadBytes: Long? = null,
    val allowChunkedTransfer: Boolean = false
)
```

```kotlin
data class PluginEndpointDefinition<Req : Any, Res : Any>(
    val endpointId: String,
    val method: HttpMethod,
    val path: String,
    val requestType: KType?,
    val responseType: KType,
    val executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
    val handler: suspend PluginRequestContext.(Req?) -> PluginResult<Res>
)
```

Rules:

- `PluginConfig.callTimeoutMs` is the plugin default only
- `EndpointExecutionPolicy.timeoutMs` overrides the plugin default when present
- endpoints with large request or response payloads must declare payload policy explicitly
- external mode must reject oversized payloads with `413` when chunking is disabled

### Runtime mode model

```kotlin
enum class PluginRuntimeMode {
    IN_PROCESS,
    EXTERNAL_JVM
}
```

### Lifecycle model

```kotlin
enum class PluginLifecycleState {
    REGISTERED,
    INITIALIZING,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    DISPOSING,
    DISPOSED,
    FAILED
}
```

```kotlin
enum class PluginHealthState {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNREACHABLE
}
```

### Unified config

```kotlin
@Serializable
data class PluginConfig(
    val pluginId: String,
    val enabled: Boolean = true,
    val runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS,
    val startupTimeoutMs: Long = 5000,
    val callTimeoutMs: Long = 3000,
    val stopTimeoutMs: Long = 3000,
    val healthCheckIntervalMs: Long = 10000,
    val maxConcurrentCalls: Int = 128,
    val eventLogRingBufferSize: Int = 4096,
    val criticalEventQueueSize: Int = 256,
    val reload: ReloadConfig = ReloadConfig(),
    val settings: JsonObject = buildJsonObject {}
)
```

```kotlin
@Serializable
data class ReloadConfig(
    val watchEnabled: Boolean = false,
    val debounceMs: Long = 500,
    val replaceOnArtifactChange: Boolean = true,
    val reloadOnConfigChange: Boolean = true
)
```

### DI contracts

```kotlin
interface PluginInitContext {
    val pluginId: String
    val config: PluginConfig
    val kernelKoin: Koin
}

interface PluginRuntimeContext {
    val pluginId: String
    val config: PluginConfig
    val kernelKoin: Koin
    val privateScope: Scope
}
```

Leak-safety rules:

- no plugin-private object may be stored in global Koin singletons or global registries
- any plugin registration into global callback registries must return a teardown handle
- `onDispose` must unregister all global listeners, hooks, and callback handles created by the plugin
- manager dispose logic must run teardown before closing the private scope
- the kernel must provide leak-detection tests for stale private scope retention across reload

## UDS Conversation Design

## Why this needs special design

The current code uses one blocking request path over UDS. That is too narrow for a production plugin runtime because:

- a long-running invoke can delay visibility into plugin state
- health and shutdown messages can be blocked behind normal traffic
- logs, warnings, and runtime events from the sub-JVM have no first-class path back to the kernel
- one stuck channel can create false "healthy but silent" behavior

The target design must prevent information blockage between the kernel and the plugin sub-JVM.

## Required transport model

The kernel is the control-plane master. The sub-JVM is the managed runtime. Communication must be logically split into two planes, but physically isolated into separate sockets so one blocked lane cannot hide state changes:

- `invoke channel`
- `admin/status channel`
- `event channel`

All channels use UDS.

### Invoke channel

Purpose:

- invoke

Behavior:

- request/response
- correlated with `requestId`
- bounded timeout per request
- concurrent handling allowed
- may use multiple concurrent sessions or a channel distributor
- no admin, health, shutdown, or runtime notifications on this channel

### Admin/status channel

Purpose:

- handshake
- health probe
- shutdown
- reload coordination
- backpressure signals
- drain-state inspection

Behavior:

- request/response
- independent from invoke channel load
- bounded timeout per request
- must remain responsive even while invoke lanes are saturated
- supervisor-grade commands always bypass invoke queue accounting

### Event channel

Purpose:

- plugin lifecycle events
- structured logs
- warnings
- failure reports
- drain-progress notifications
- readiness notifications

Behavior:

- sub-JVM initiated stream to kernel
- one-way event delivery with optional ack policy for critical event types
- buffered and non-blocking from the admin/status and invoke paths’ perspective
- drop policy allowed only for low-priority log events, never for lifecycle or failure events
- event write pressure must feed back into the sub-JVM runtime state

## UDS protocol objects

Required control-plane messages:

- `HandshakeRequest`
- `HandshakeResponse`
- `InvokeRequest`
- `InvokeResponse`
- `HealthRequest`
- `HealthResponse`
- `ShutdownRequest`
- `ShutdownResponse`
- `ReloadPrepareRequest`
- `ReloadPrepareResponse`

Required event-plane messages:

- `PluginReadyEvent`
- `PluginStoppingEvent`
- `PluginDisposedEvent`
- `PluginFailureEvent`
- `PluginLogEvent`
- `PluginBackpressureEvent`
- `PluginDrainCompleteEvent`
- `PluginProcessExitedEvent`
- `PluginEventQueueOverflowEvent`

Every message must carry:

- `protocolVersion`
- `pluginId`
- `generation`
- `timestamp`
- `messageId`

Control responses must also carry:

- `correlationId`

Kernel truth model:

- plugin self-reported health is advisory only
- kernel-observed `Process` or `ProcessHandle` liveness is authoritative for child-process existence
- admin/status handshake success is authoritative for control-plane reachability
- event heartbeat freshness is authoritative for event-plane health
- final plugin health is derived from supervisor state plus both channels, not from any single channel alone

## Anti-blocking rules

These are mandatory:

- one blocked invoke must not block health checks
- one blocked invoke must not block shutdown
- one blocked invoke must not block failure reporting
- event delivery must not depend on waiting for the admin/status channel to become idle
- admin/status requests must have per-request timeout enforcement
- event channel writes must be bounded by queue policy
- process death detection must not depend on UDS traffic
- kernel must observe child process exit directly and immediately transition plugin status
- kernel must mark plugin `DEGRADED` if event channel is disconnected but control still works
- kernel must mark plugin `UNREACHABLE` if control channel is unavailable

## Queueing and flow control

Kernel side:

- maintain a bounded per-plugin invoke semaphore sized by `maxConcurrentCalls`
- reject excess calls with `503` once the plugin queue budget is exhausted
- keep admin/status messages for `health`, `shutdown`, and `reload` outside normal invoke capacity accounting
- support invoke distribution across multiple concurrent UDS sessions per plugin
- maintain per-plugin lane metrics for invoke depth, event backlog, health latency, and admin/status latency

Sub-JVM side:

- maintain separate executors and dispatchers for:
  - invoke handling
  - admin/status handling
  - event emission
- dispatchers must not share the same bounded business executor
- CPU-heavy invoke work must not be scheduled onto the admin/status executor
- event emission must never run inline on the invoke response path
- critical lifecycle events must bypass low-priority log buffering
- use a ring buffer for log events and drop oldest low-priority entries when full
- use an independent critical-event queue for lifecycle and failure events
- if the critical-event queue reaches capacity, the plugin must emit a final overflow signal if possible and the kernel must downgrade or fail the plugin generation

Priority order:

1. `shutdown`
2. `health`
3. `failure/lifecycle`
4. `invoke`
5. `logs`

Large payload rules:

- `IN_PROCESS` mode must not serialize through UDS and must use direct handler invocation
- `EXTERNAL_JVM` mode must enforce `maxPayloadBytes` before frame write
- payloads over the normal frame threshold must use chunked transfer or explicit spool-file transfer when `allowChunkedTransfer` is enabled
- if an endpoint does not opt into chunking and exceeds payload policy, the kernel returns `413`

## Timeout and failure rules

- handshake timeout uses `startupTimeoutMs`
- invoke timeout uses plugin default `callTimeoutMs` unless endpoint policy overrides it
- graceful stop timeout uses `stopTimeoutMs`
- control request timeout failure marks health as `DEGRADED` on first occurrence
- repeated control timeout or broken socket marks health as `UNREACHABLE`
- malformed UDS frame closes the affected session and emits structured failure event
- malformed event frame must not crash the kernel process
- malformed control frame must not crash the plugin host process
- event log overflow drops logs only from the log ring buffer and increments a dropped-log counter
- critical-event queue overflow is treated as a fatal runtime integrity failure for that plugin generation
- when graceful shutdown exceeds `stopTimeoutMs`, the kernel supervisor must terminate the child process at the OS process level
- internal `exitProcess()` is advisory only and not sufficient as the final enforcement path

## Conversation sequencing rules

### Startup

1. kernel starts sub-JVM
2. kernel records the child `Process` and `ProcessHandle`
3. sub-JVM opens event channel and sends `PluginReadyEvent` only after runtime initialization
4. kernel opens admin/status handshake
5. plugin returns descriptor, version, runtime mode, endpoint inventory, and generation
6. kernel validates metadata
7. kernel marks plugin `RUNNING`

### Real-status synchronization

1. process exit watcher runs independently from UDS channels
2. admin/status heartbeat runs independently from invoke load
3. event heartbeat freshness is tracked independently from both process and admin/status state
4. manager derives:
   - `RUNNING` when process alive, admin/status healthy, event healthy
   - `DEGRADED` when process alive and admin/status healthy but event unhealthy
   - `UNREACHABLE` when process alive but admin/status unhealthy
   - `FAILED` when process exited unexpectedly or runtime integrity is broken

### Stop

1. kernel marks plugin `STOPPING`
2. kernel stops accepting new invokes
3. kernel sends `ShutdownRequest`
4. sub-JVM emits `PluginStoppingEvent`
5. in-flight invokes drain within timeout
6. sub-JVM emits `PluginDrainCompleteEvent`
7. if process does not exit by `stopTimeoutMs`, kernel sends OS-level termination and escalates to force kill
8. kernel marks plugin `STOPPED`

### Reload or replace

1. kernel marks plugin `STOPPING`
2. kernel stops new traffic
3. kernel drains in-flight work
4. kernel disposes private scope
5. kernel reloads artifact or config
6. kernel restarts sub-JVM
7. new generation performs handshake
8. old generation sockets are closed
9. kernel marks new generation `RUNNING`

## Important architecture changes

## 1. Replace both plugin APIs with one API

Remove:

- `KPlugin`
- `KeelPluginV2`

Keep only:

- `KeelPlugin`

## 2. Replace both managers with one manager

Remove:

- `PluginManager`
- `HybridPluginManager`

Create:

- `UnifiedPluginManager`

Responsibilities:

- registration
- config validation
- lifecycle state
- private scope lifecycle
- runtime mode dispatch
- external JVM supervision
- UDS session management
- process-handle liveness supervision
- health state
- reload and replace orchestration

## 3. Replace path-only gating with dispatch-aware routing

Current interceptor-only behavior is insufficient.

Target behavior:

- unknown plugin id returns `404`
- disposed plugin returns `404`
- stopped plugin returns `503`
- failed plugin returns `503`
- request decode and error mapping are identical across runtime modes

## 4. Replace "IPC" terminology with "UDS" terminology

Rename package and types so transport naming matches implementation reality.

Examples:

- `com.keel.ipc.runtime` -> `com.keel.uds.runtime`
- `PluginIpcProtocol` -> `PluginUdsProtocol`
- `PluginFrameCodec` -> `PluginUdsFrameCodec`

## 5. Support global and private Koin scopes

Rules:

- root Koin remains kernel-owned
- each plugin instance has one private scope
- reload and replace destroy and recreate private scope
- plugins may read kernel-global services
- plugins may register their own private modules
- private services must not survive generation changes
- plugins must not leak private objects into global singletons, global caches, or global event listeners
- every global registration path must support explicit unregister during plugin dispose

## 6. Support hot dispose and reload as real lifecycle operations

File-watch is only a trigger.

The actual work must use the same manager operations as manual lifecycle actions:

- `start`
- `stop`
- `dispose`
- `reload`
- `replace`

## Implementation Phases and TODOs

## Phase 1: Type and contract unification

- [ ] Introduce `KeelPlugin`
- [ ] Introduce unified descriptor, config, lifecycle, and health types
- [ ] Introduce unified init and runtime contexts
- [ ] Define strict config validation rules
- [ ] Define generation model for reload and replace
- [ ] Introduce endpoint execution policy with timeout and payload override support
- [ ] Freeze all new references to `KPlugin` and `KeelPluginV2`

Acceptance criteria:

- one target plugin model is defined
- new runtime mode and lifecycle enums are defined
- config shape is decision complete and testable

## Phase 2: UDS protocol redesign

- [ ] Split transport design into control channel and event channel
- [ ] Refine the physical layout into invoke, admin/status, and event UDS sockets
- [ ] Define message schemas for both channels
- [ ] Add `protocolVersion`, `messageId`, `correlationId`, and `generation`
- [ ] Define queueing and flow-control policy
- [ ] Add kernel-side process liveness watcher that is independent of UDS
- [ ] Add log ring buffer and critical-event queue policy
- [ ] Add large-payload handling policy and frame-size limits
- [ ] Define timeout behavior and socket failure behavior
- [ ] Rename transport package and type names from `ipc` to `uds`

Acceptance criteria:

- protocol supports invoke plus runtime signaling without sharing one blocking path
- all lifecycle-critical messages have a transport path independent of invoke traffic
- kernel plugin status is derived from process liveness plus channel health, not from one channel only

## Phase 3: Unified manager and kernel integration

- [ ] Implement `UnifiedPluginManager`
- [ ] Replace plugin registration in `Kernel`
- [ ] Remove legacy registration overloads
- [ ] Move runtime supervision and state ownership into unified manager
- [ ] Add lifecycle mutex per plugin
- [ ] Add generation tracking per plugin
- [ ] Add child process PID or `ProcessHandle` tracking and direct kill capability

Acceptance criteria:

- kernel has exactly one plugin registry
- all plugins are started and stopped through one manager

## Phase 4: Routing and request dispatch redesign

- [ ] Replace `GatewayInterceptor` semantics with dispatch-aware routing
- [ ] Preserve static route registration for known plugin descriptors
- [ ] Differentiate `404` unknown from `503` unavailable
- [ ] Align decode and error handling for in-process and external mode
- [ ] Add bounded concurrency and queue overflow behavior
- [ ] Apply endpoint-level timeout and payload policy overrides during dispatch

Acceptance criteria:

- unknown plugin routes return `404`
- blocked or failed plugin routes return `503`
- body validation is identical across runtime modes

## Phase 5: External JVM host redesign

- [ ] Introduce external plugin host bootstrap
- [ ] Make host consume explicit `config-path`
- [ ] Add startup handshake validation
- [ ] Add separate event sender path
- [ ] Add process-exit observation and health transitions
- [ ] Add dedicated executors for invoke, admin/status, and event lanes
- [ ] Replace blocking sleeps with suspend-safe waits
- [ ] Harden malformed frame handling
- [ ] Add OS-level terminate and force-kill escalation path

Acceptance criteria:

- sub-JVM can emit logs and lifecycle events without waiting behind invokes
- kernel cannot hang indefinitely on invoke, health, or shutdown
- CPU-bound invoke work cannot starve shutdown or health handling inside the sub-JVM

## Phase 6: DI redesign

- [ ] Implement root Koin plus private plugin scope model
- [ ] Load plugin modules into private scope
- [ ] Recreate private scope on reload and replace
- [ ] Expose global and private resolvers to runtime contexts
- [ ] Add isolation tests across multiple plugins
- [ ] Add teardown registry for unregistering global callbacks and listeners
- [ ] Add leak detection tests for stale private scope retention

Acceptance criteria:

- plugin can use shared kernel services
- plugin can define private services
- private state resets across generation changes
- disposed plugins do not leave globally retained references to old private-scope objects

## Phase 7: Reload, dispose, and replace lifecycle

- [ ] Implement `start`, `stop`, `dispose`, `reload`, `replace`
- [ ] Add inflight request drain policy
- [ ] Add file-watch trigger adapter to call lifecycle APIs
- [ ] Add artifact change detection for replace
- [ ] Add config change detection for reload
- [ ] Keep failed plugin registered for diagnostics
- [ ] Define kill escalation from graceful stop to OS-level force kill

Acceptance criteria:

- plugin lifecycle mutations are serialized
- file-watch and manual reload use the same code path
- no scope or process leak remains after reload
- hung external plugin invokes cannot create zombie child processes after stop timeout

## Phase 8: Discovery and artifact loading

- [ ] Refactor loader to discover only unified plugin artifacts
- [ ] Remove all APIs returning `KPlugin`
- [ ] Support same artifact under either runtime mode based on config
- [ ] Track last-modified or checksum for replace decisions

Acceptance criteria:

- one discovery pipeline serves both runtime modes
- legacy loader semantics are removed

## Phase 9: System routes and observability

- [ ] Replace current plugin DTOs with lifecycle-aware DTOs
- [ ] Expose runtime mode, lifecycle state, health state, generation, and last failure
- [ ] Expose process-liveness, admin/status-channel health, event-channel health, dropped-log count, and event queue pressure
- [ ] Add `start`, `stop`, `dispose`, `reload`, `replace`, and `health` APIs
- [ ] Add structured logs containing plugin id, mode, generation, and action
- [ ] Surface event-channel degradation separately from full unreachability

Acceptance criteria:

- operators can distinguish stopped, degraded, failed, and unreachable states
- system routes reflect actual runtime state, not inferred state
- operator-facing diagnostics expose whether failure came from process death, admin/status timeout, event degradation, queue overflow, or forced kill

## Phase 10: OpenAPI alignment and cleanup

- [ ] Keep one operation registration path for plugin endpoints
- [ ] Cache OpenAPI build and invalidate on topology changes
- [ ] Remove old system route variants
- [ ] Delete all legacy plugin architecture code
- [ ] Add regression checks to prevent reintroduction

Acceptance criteria:

- one documented plugin topology exists
- no V1 or V2 legacy symbols remain in production code

## Tests and scenarios

## Unit tests

- config rejects mismatched `pluginId`
- config rejects unsupported runtime mode
- endpoint timeout override supersedes plugin default timeout
- oversized external payload returns `413` when chunking is disabled
- unknown plugin returns `404`
- stopped plugin returns `503`
- missing body returns `400` in both runtime modes
- private scope is recreated on reload
- global scope remains stable across reload
- invoke concurrency limit returns `503` when exhausted
- event channel disconnect marks `DEGRADED`
- control channel disconnect marks `UNREACHABLE`
- process exit marks plugin failed even if both UDS channels were previously healthy
- log ring buffer drops low-priority logs without OOM
- critical-event queue overflow marks plugin failed
- malformed UDS frame is handled without kernel crash
- control timeout does not block failure event delivery
- explicit `config-path` is consumed by sub-JVM host

## Integration tests

- sample hello plugin works in `IN_PROCESS`
- sample hello plugin works in `EXTERNAL_JVM`
- sample dbdemo plugin works in `IN_PROCESS`
- sample dbdemo plugin works in `EXTERNAL_JVM`
- health probe succeeds while long invoke is running
- shutdown succeeds while invoke traffic exists
- shutdown succeeds while CPU-heavy invoke traffic exists
- plugin failure event is delivered while invoke load exists
- process death is reflected immediately without waiting for a heartbeat timeout
- config change triggers reload
- artifact change triggers replace
- OpenAPI stays valid after reload and replace

## Operational scenarios

- plugin starts with invalid config while another plugin starts successfully
- long-running invoke does not block health checks
- long-running invoke does not block shutdown request
- log burst does not block control-plane requests
- event channel drops low-priority logs under pressure but preserves failure events
- sub-JVM crashes during request handling
- plugin reload is requested twice rapidly
- artifact change and config change happen close together
- stop timeout expires and process is force-killed
- stop timeout force-kill removes the child process from the OS process table
- event channel disconnects but control still responds
- control channel stalls while event channel still reports failure
- CPU-heavy invoke saturation does not starve admin/status handling
- teardown removes plugin-owned global callbacks so old scopes can be garbage-collected

## Assumptions and defaults

- UDS is the only external transport in scope
- kernel is the control-plane authority
- sub-JVM host is the managed runtime
- no long-term compatibility layer for `KPlugin` or `KeelPluginV2`
- route prefix remains `/api/plugins/{pluginId}`
- sample plugins are in scope for full migration
- event channel is required for production external mode
- low-priority logs may be dropped under pressure, lifecycle and failure events may not
- per-endpoint timeout and payload policy override plugin defaults where declared
- final kill authority for `EXTERNAL_JVM` stop timeout is the kernel supervisor via OS process termination

## Deliverable

This plan is stored in:

`/Users/a/Documents/GitHub/OpenSource/Keel/backlogs/plugin_architecture_working_plan_260303_0943.md`
