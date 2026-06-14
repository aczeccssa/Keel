# TUI MCP Control Core Design

## Goal

Build a unified control core inside `keel-sample-launcher` so the launcher exposes two consistent operator surfaces:

- an interactive TUI for human control
- an MCP/function-calling control surface for coding agents

The first implementation scope is launcher control and startup configuration management only. It does not attempt to fix or depend on Keel dev hot reload.

## Context

The current Rust launcher is a foreground TUI wrapper around one child process. It directly owns process lifecycle and log streaming inside UI-oriented state.

Current behavior is good enough for manual development but not for agent control:

- the control path is keyboard-driven
- startup command and args are compiled into Rust constants
- there is no stable programmatic control surface
- TUI behavior and future agent behavior would diverge if both implemented lifecycle logic separately

This design treats those as product and architecture problems, not as missing commands.

## Locked Decisions

- the solution lives in `tui`
- `keel-sample-launcher` remains the single host process
- TUI and MCP are both first-class external capabilities
- both capabilities must use the same internal event and command model
- launcher control is the design center
- application runtime config and hot reload are out of scope
- configuration is project-specific and stored in YAML or JSON, not TOML
- write operations execute sequentially
- TUI-originated write operations have priority over MCP-originated write operations
- if a write operation is already executing, later write operations wait
- no write operation may preempt another one once execution starts
- profile switching does not implicitly restart the app
- config changes and app lifecycle actions are explicit and separate

## Non-Goals

- no redesign of Keel dev reload
- no attempt to hot-apply arbitrary Keel application settings
- no multi-project generic abstraction in phase one
- no distributed or remote control service
- no requirement to attach to an already-running external launcher process in phase one
- no shell-like arbitrary command execution surface for agents

## Desired Outcomes

After implementation, both a person using the TUI and an agent using MCP should be able to:

- inspect launcher status
- inspect the active profile and available profiles
- start the sample app
- stop the sample app
- restart the sample app
- switch the active profile
- update startup configuration fields in a profile
- inspect recent logs

All of those actions must produce the same state transitions, validation rules, and error semantics regardless of whether the request came from TUI or MCP.

## Architecture

### Overview

The launcher is restructured into one core subsystem and two adapters:

1. `control core`
2. `tui adapter`
3. `mcp adapter`

Only the control core may mutate runtime state or control the child process.

The TUI adapter becomes a client of the core. The MCP adapter also becomes a client of the core. Neither adapter owns process state.

### Core Responsibilities

The control core owns:

- config file loading and persistence
- active profile selection
- validation of requested config changes
- write-command scheduling and prioritization
- process lifecycle execution
- log capture and retention
- shared runtime snapshot generation
- event fan-out to subscribers

### Adapter Responsibilities

The TUI adapter owns:

- keyboard handling
- rendering
- subscribing to shared runtime state
- presenting queue and status information

The MCP adapter owns:

- exposing structured tools/functions
- translating MCP requests into core commands
- waiting for command completion or returning a structured timeout/error

## Configuration Model

### Storage

Phase one uses one project-local YAML file. Recommended path:

- `tui/keel-runner.yaml`

JSON is acceptable in the data model, but YAML is the primary design target.

### File Shape

The file stores launcher profiles and defaults. Representative shape:

```yaml
version: 1
default_profile: sample-dev
auto_start: true
profiles:
  sample-dev:
    program: ./gradlew
    args:
      - :keel-samples:run
    cwd: .
    env:
      KEEL_ENV: development
    stop_timeout_ms: 3000
  sample-prod:
    program: ./gradlew
    args:
      - :keel-samples:run
      - --args=--production
    cwd: .
    env:
      KEEL_ENV: production
    stop_timeout_ms: 3000
```

### Scope of Editable Fields

Phase one allows editing only launcher-startup fields:

- `program`
- `args`
- `cwd`
- `env`
- `stop_timeout_ms`
- `default_profile`
- `auto_start`

This keeps the product focused on startup command control while still leaving room for future project reuse.

### Runtime vs Persisted State

The core keeps a distinct in-memory runtime snapshot:

- current process state
- active profile name
- resolved config currently used for lifecycle actions
- queue state
- logs
- last errors

Profile edits persist to disk. A profile update does not implicitly restart the app. Restart is a separate action.

## Command Model

### Read Commands

Read commands never mutate state and do not enter the write queue:

- `GetStatus`
- `GetRecentLogs`
- `ListProfiles`
- `GetProfile`
- `GetActiveProfile`

These return immediately from the current shared snapshot.

### Write Commands

Write commands mutate state or process lifecycle and must enter the scheduler:

- `Start`
- `Stop`
- `Restart`
- `SwitchProfile`
- `UpdateProfile`
- `SetDefaultProfile`
- `ShutdownLauncher`

Each request carries:

- source: `Tui` or `Mcp`
- request id
- enqueue timestamp
- action payload

## Scheduling and Priority Semantics

### Rules

- only one write command may execute at a time
- if a write command is executing, later write commands wait
- TUI write commands outrank MCP write commands when both are pending
- within the same source priority, commands execute in arrival order
- already-running commands are never interrupted by later commands
- read commands remain available while write commands execute

### Practical Meaning

If TUI requests `Restart` while an MCP `Restart` is still queued, the TUI request should execute first.

If an MCP `Stop` arrives while a TUI `Restart` is already executing, the MCP `Stop` waits until the `Restart` finishes.

This matches the requested semantics:

- TUI priority
- in-progress behavior waits
- no mid-flight cancellation

## Process State Machine

The launcher state must be explicit rather than represented by a single boolean.

Recommended states:

- `Idle`
- `Starting`
- `Running`
- `Stopping`
- `Restarting`
- `Failed`
- `Exited`

The runtime snapshot should also include:

- `active_profile`
- `pid`
- `pgid`
- `last_command`
- `last_error`
- `last_exit_status`
- `queue_depth`
- `busy`
- `log_line_count`
- `config_path`
- `config_loaded_at`

This snapshot is the single source of truth for both TUI rendering and MCP responses.

## Event Model

The control core publishes structured events for adapters and tests. Recommended event families:

- config events
- queue events
- lifecycle events
- log events
- error events

Representative examples:

- `ConfigLoaded`
- `ProfileUpdated`
- `ProfileSwitched`
- `CommandQueued`
- `CommandStarted`
- `CommandCompleted`
- `StateChanged`
- `ProcessOutput`
- `ProcessExited`
- `CommandFailed`

The event model is important because it prevents TUI and MCP from inferring behavior separately from raw process state.

## TUI Design

The TUI remains an interactive operator view, but stops being the lifecycle owner.

### TUI Inputs

Current key bindings continue conceptually:

- `Ctrl+R` requests `Restart`
- `Ctrl+P` requests `Stop`
- startup may request `Start` when `auto_start` is enabled

### TUI Output Additions

The TUI should display:

- current lifecycle state
- active profile name
- PID and PGID
- whether a write command is executing
- current queue depth
- last command result
- recent system errors

This makes queued and waiting behavior visible instead of implicit.

## MCP Design

Phase one MCP should expose explicit launcher tools instead of a generic shell tool.

Recommended tools:

- `runner_status`
- `runner_get_logs`
- `runner_list_profiles`
- `runner_get_profile`
- `runner_switch_profile`
- `runner_update_profile`
- `runner_start`
- `runner_stop`
- `runner_restart`

### MCP Response Shape

Responses should be structured and state-oriented, for example including:

- `ok`
- `message`
- `request_id`
- `state_before`
- `state_after`
- `active_profile`
- `queued`
- `queue_depth`
- `last_error`

This avoids fragile text parsing by agents.

## Validation and Persistence Rules

Before persisting a profile update, the core validates:

- `program` is non-empty
- `args` entries are strings
- `cwd` is non-empty and resolves relative to the project root
- `stop_timeout_ms` is positive
- environment keys are non-empty strings

Persistence should use write-to-temp plus atomic replace semantics so an interrupted write does not corrupt the config file.

If persistence fails:

- no in-memory persisted config view is advanced
- the active runtime snapshot remains unchanged
- the command returns a structured failure

## Error Handling

### Config Errors

Malformed config files or invalid updates should:

- surface clearly in TUI system logs
- return structured validation errors through MCP
- not crash the launcher process

### Process Errors

Spawn failures, signal failures, and unexpected child exit should:

- transition state to `Failed` or `Exited`
- preserve logs and last error details
- keep the launcher and MCP surface alive

### Queue Errors

If a queued command becomes invalid before execution, for example switching to a deleted profile, it should fail at execution time with an explicit command result instead of silently dropping.

## Testing Strategy

### Core Unit Tests

Test the scheduler and state machine independently from TUI:

- TUI write requests outrank queued MCP write requests
- write requests execute one at a time
- read requests do not block on active writes
- `SwitchProfile` does not restart the process
- `Restart` uses the active profile at execution time

### Config Tests

- YAML round-trip parsing
- invalid field validation
- atomic persistence behavior
- profile selection and default resolution

### Runtime Tests

- start launches a child process
- stop sends the correct signal sequence
- restart performs stop then start
- stdout and stderr lines are captured
- abnormal child exit updates snapshot state correctly

### Adapter Tests

- TUI key handling maps to the expected core requests
- MCP tools return structured responses based on the shared snapshot

## File Structure Direction

The current `tui` code mixes UI state and process control. Phase one should split responsibilities into focused modules.

Recommended structure:

- `tui/src/control/`
  - command types
  - queue/scheduler
  - shared runtime snapshot
  - event broadcaster
- `tui/src/config/`
  - file schema
  - validation
  - load/save logic
- `tui/src/runtime/`
  - process spawn/stop/restart execution
  - signal handling
  - output reader wiring
- `tui/src/mcp/`
  - MCP tool definitions
  - request/response translation
- `tui/src/ui/` or retained `ui.rs`
  - rendering
  - TUI command dispatch

`app.rs` should stop owning the child process directly. It should instead become a view model over the shared runtime snapshot and command sender.

## Migration Strategy

Phase one implementation should proceed in this order:

1. introduce config file model
2. extract process lifecycle into a runtime module
3. add control core with a serial write scheduler
4. migrate TUI to consume the control core
5. add MCP adapter on top of the same core

This order keeps behavior measurable and avoids building MCP on top of unstable UI-owned state.

## Acceptance Criteria

The design is considered satisfied when:

- the launcher can be started normally through TUI
- the same launcher process exposes MCP tools for lifecycle and profile control
- TUI and MCP actions converge on the same status model
- TUI-originated write actions take priority over queued MCP write actions
- queued write actions wait while another write action is in progress
- startup command parameters are editable through persisted YAML config
- config update and process restart are distinct operations
- the launcher remains alive when the child process fails

## Deferred Work

The following are intentionally deferred:

- generic multi-project templates
- remote control over network
- live attach to an arbitrary existing launcher process
- application-level config mutation
- dev hot reload integration
- advanced queue cancellation policies
