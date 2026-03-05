# Dev Hot Reload Working Plan

## Summary

This plan defines the real development-time hot reload model for Keel.

The target is not config reload and not artifact directory polling as the primary developer workflow.
The target is:

- watch the current application module
- watch all recursive `project(...)` dependency modules
- detect source and resource changes during development
- rebuild only what is necessary
- update plugin behavior without restarting the kernel process when the changed code belongs to a reloadable plugin boundary

This plan explicitly rejects `plugins/` and `config/plugins/` as the core design center for development hot reload.
Those directories may still exist for separate production or packaging workflows, but they are not the main dev-loop abstraction.

## Output File

Use backlog filename style:

`/Users/a/.codex/worktrees/994d/Keel/backlogs/dev_hot_reload_working_plan_260305_0047.md`

## Decisions Locked

- hot reload scope: development mode only
- implementation scope: rewrite the existing hot reload mechanism only
- compatibility guardrail: do not break any upper-layer development conventions outside hot reload
- default watch scope: caller module plus recursive `project(...)` dependency modules
- user may append extra watch directories manually
- plugin definition source: may live in `src/` of the current module or any project dependency module
- plugin config is not the center of the hot reload design
- kernel process restart is not acceptable for normal plugin code edits in the dev loop
- source-level hot reload requires plugin runtime isolation from kernel-loaded classes
- kernel code changes are not hot reloadable in the same JVM and require restart

## Problem Statement

The current in-process registration model:

```kotlin
runKernel {
    plugin(MyPlugin())
}
```

loads plugin classes into the same JVM/classloader context as the kernel process.
Once that happens, watching files is not enough.
New source code cannot be safely applied unless the framework has a way to:

1. rebuild changed code
2. load the rebuilt plugin classes into a fresh isolated runtime boundary
3. switch request dispatch from the old generation to the new generation
4. dispose the old generation cleanly

Without that, file watching only produces notifications, not real hot reload.

## True Target Outcomes

After implementation:

- editing a plugin source file under a watched module causes the framework to rebuild that plugin
- the kernel process keeps running
- requests begin flowing to the new plugin generation without restarting the whole app
- old plugin generation is stopped and disposed after in-flight requests drain
- recursive project dependency watching works by default
- users can append extra watch directories when needed
- kernel code changes are clearly detected and explicitly reported as restart-required
- the framework can distinguish "hot reloaded", "reloadable but build failed", and "restart required"

## Non-Goals

- no promise of hot reloading arbitrary kernel internals in the same JVM
- no promise of JVMTI bytecode replacement or debugger-style live patching
- no promise of preserving all in-memory plugin state across source reloads
- no dependence on external IDE-specific hot swap features
- no changes to unrelated upper-layer development APIs, conventions, or lifecycle contracts
- no behavioral regression for existing non-hot-reload development workflows

## Reloadability Boundaries

### Safe to hot reload

- plugin implementation classes
- plugin route handlers
- plugin-local DTOs and serializers if loaded through the plugin runtime boundary
- plugin-local resources
- plugin-local DI modules

### Usually restart required

- kernel classes in `keel-core`
- routing or dispatch code already loaded into the kernel JVM
- shared API contracts used directly by both kernel and plugin classloaders, unless classloader rules are explicitly designed for them
- Gradle build logic changes
- dependency graph changes that alter the kernel runtime classpath itself

### Must be surfaced clearly to the developer

When a watched change is not reloadable, dev mode must say so explicitly in logs and system status:

- `RELOADED`
- `RELOAD_FAILED`
- `RESTART_REQUIRED`

## Required Architecture Change

### Core conclusion

Real source-level hot reload requires plugin isolation.

That means the framework cannot rely only on eagerly instantiated plugin objects created in the kernel JVM.
The framework needs a dev runtime that can recreate plugin generations from rebuilt module output.

### Recommended runtime model

Use one of these as the implementation base:

1. per-plugin classloader isolation inside the kernel process
2. per-plugin external JVM isolation, even in dev mode

For Keel, the safer path is:

- keep the kernel process stable
- treat reloadable plugins as isolated generations with their own classloader boundary
- allow both `IN_PROCESS_ISOLATED` and `EXTERNAL_JVM` style dev execution if needed later

The minimum viable implementation is fresh classloader per plugin generation.

## Public API Direction

The current convenience API can remain for simple usage, but dev hot reload needs a deferred plugin definition model.

### New conceptual model

```kotlin
data class PluginDevelopmentSource(
    val pluginId: String,
    val owningModulePath: String,
    val implementationClassName: String
)
```

```kotlin
interface PluginDevRuntime {
    suspend fun rebuild(modulePath: String): BuildResult
    suspend fun loadGeneration(source: PluginDevelopmentSource): KeelPlugin
    suspend fun replaceGeneration(pluginId: String): ReloadResult
}
```

### Kernel registration direction

Support registering plugin sources, not only prebuilt instances.

Example direction:

```kotlin
runKernel {
    pluginSource(
        pluginId = "helloworld",
        implementationClassName = "com.keel.samples.helloworld.HelloWorldPlugin"
    )
}
```

The current `plugin(MyPlugin())` path can remain as a convenience path, but it should be documented as:

- simple runtime path
- not source-hot-reloadable unless wrapped by the dev runtime

## Watch Strategy

### Default strategy

Framework defaults:

1. detect the caller module
2. parse recursive `project(...)` dependencies
3. watch all resolved module roots

### Manual extension

Users may append extra directories:

```kotlin
runKernel {
    watchDirectories("some/extra/path", "../another-shared-module")
}
```

### What to watch under each module

- `src/main/kotlin`
- `src/main/resources`
- `src/commonMain` if present
- module `build.gradle.kts`
- optional generated source roots only if they are part of plugin compilation

### What watch events mean

- source/resource changes in plugin-owned modules: trigger rebuild and hot replacement attempt
- source/resource changes in kernel-owned modules: mark `RESTART_REQUIRED`
- build logic changes: mark `RESTART_REQUIRED`

## Ownership Model

The framework must know which plugin belongs to which module and which classpath roots.

This needs explicit metadata in dev mode:

```kotlin
data class PluginOwnership(
    val pluginId: String,
    val owningModulePath: String,
    val dependentModulePaths: Set<String>
)
```

Rules:

- every reloadable plugin must have one owning module
- changes in the owning module trigger rebuild of that module
- changes in transitive dependency modules trigger rebuild of all affected owning plugins

## Build and Reload Pipeline

## Phase A: Change detection

- file watcher receives a module change
- classify change by module ownership
- determine affected plugin set

## Phase B: Incremental build

- invoke Gradle for the smallest safe target
- prefer module-local compile tasks, for example `:keel-samples:classes`
- capture success or failure

If build fails:

- keep old plugin generation serving traffic
- surface `RELOAD_FAILED`
- expose compiler/build error summary in logs

## Phase C: Generation loading

- create a fresh classloader using rebuilt outputs and plugin module runtime dependencies
- instantiate plugin class by name
- validate descriptor and routes
- create new plugin generation

## Phase D: Atomic switch

- stop dispatch to the old generation
- drain in-flight requests
- start the new generation
- switch routing/dispatch to the new generation
- dispose old generation

## Dispatch Model Changes

Routes should not close over one static plugin instance forever.
They should resolve the current plugin generation dynamically by `pluginId`.

That means the dispatch path should look conceptually like:

```kotlin
request -> pluginId -> current generation handle -> endpoint handler
```

not:

```kotlin
request -> route closure bound to one original plugin instance
```

This is a critical prerequisite for real hot replacement.

## State and Lifecycle Rules

- old generation continues serving in-flight work until drained or timeout reached
- new generation must complete `onInit` and `onStart` before becoming active
- if new generation startup fails, keep old generation alive
- plugin-private DI scope must be recreated per generation
- plugin-local static resources must resolve against the new generation classloader

## Failure Rules

- build failure: no switch, old generation remains active
- class loading failure: no switch, old generation remains active
- route validation failure: no switch, old generation remains active
- startup lifecycle failure: rollback to old generation
- repeated failures should be throttled to avoid rebuild storms

## Required Code Changes

## 1. Watch resolution

- keep recursive module resolution
- separate module watching from artifact/config watching
- attach ownership metadata to watch results

## 2. Plugin registration model

- add source-based plugin registration for dev runtime
- preserve instance-based registration as non-reloadable convenience mode

## 3. Generation-aware dispatch

- routing closures must resolve active generation dynamically
- current manager state must hold multiple generations during transition windows

## 4. Dev build executor

- add a small Gradle invocation layer
- map changed module to build task
- collect build output and failure summary

## 5. Fresh classloader loading

- construct per-generation classloader
- load plugin implementation by class name
- isolate plugin-local classes and resources

## 6. Observability

- expose dev reload events
- surface affected module, plugin, build duration, success/failure, and reload reason

## Implementation Phases

## Phase 1: Formalize reload boundaries

- define reloadable vs restart-required module classes
- add ownership metadata and state enums
- document instance-based plugin registration as non-reloadable

## Phase 2: Recursive module watch foundation

- finalize module resolver
- watch caller module plus recursive project dependencies
- support appended user directories
- emit structured module change events

## Phase 3: Generation-aware plugin dispatch

- remove route closures that bind to one plugin instance forever
- route through active generation lookup
- support drain and swap behavior

## Phase 4: Dev build executor

- implement Gradle task selection from module ownership
- capture build output
- gate reload on successful compile

## Phase 5: Fresh classloader generation loading

- implement per-generation classloader creation
- instantiate plugin from rebuilt outputs
- rebuild DI scope and static resource bindings

## Phase 6: Rollback and stability rules

- keep old generation during failed replacement
- add throttling and debounce
- add clear restart-required reporting

## Verification Plan

## Unit tests

- recursive module resolution includes transitive `project(...)` dependencies
- ownership mapping from changed module to affected plugin set
- non-reloadable kernel module changes return `RESTART_REQUIRED`
- generation switch keeps old generation when new one fails
- existing non-hot-reload plugin registration and startup behavior remains unchanged

## Integration tests

- edit plugin handler source, rebuild, and verify response changes without kernel restart
- edit plugin-local DTO and verify new serialization shape after reload
- break plugin build and verify old generation still serves traffic
- edit kernel source and verify restart-required status is emitted instead of fake reload
- run existing non-hot-reload dev flow and verify no regression in upper-layer conventions

## Manual scenarios

1. run sample app in `development`
2. modify a plugin source file under `keel-samples`
3. observe Gradle rebuild
4. verify endpoint response changes without restarting the kernel process
5. modify a dependency module used by one plugin
6. verify only affected plugin generations are replaced
7. modify `keel-core`
8. verify framework reports restart required

## Open Questions

- should dev hot reload use isolated classloaders only, or should external JVM mode also be supported as a first-class dev path?
- do we want plugin source registration to be explicit, or inferred from annotations and classpath scanning?
- how much shared API surface is allowed between kernel and plugin classloaders before type identity becomes unsafe?
- should static resource changes trigger immediate resource generation swap without full plugin rebuild?

## Deliverable

The deliverable is not "a watcher".

The deliverable is a dev runtime where plugin source edits inside watched modules are rebuilt and swapped into live request handling without restarting the kernel process, while non-reloadable changes are explicitly reported as restart-required.

Hard boundary: this work rewrites only the hot reload mechanism and must not break any development conventions outside that scope.
