# keel-samples TUI Launcher

A TUI launcher for monitoring and managing the `keel-samples` process, built with `ratatui` + `crossterm`.

The launcher now has one shared control core and two operator surfaces:

- the interactive TUI
- a local Streamable HTTP MCP service

## Features

- **`Ctrl+R`** — stop and restart the current process
- **`Ctrl+P`** — stop the current process
- **`Ctrl+C`** — exit the program
- **`↑/↓/PgUp/PgDn`** — scroll log output
- **`Home/End`** — jump to top / bottom
- **`f`** — toggle tail-follow mode
- **`l`** — clear log output
- **`q / Esc`** — exit

## Additional Features

- `stdout` / `stderr` streams colored differently (white / red)
- System messages shown in cyan
- Auto-detects project root by searching upward for `gradlew`
- Status bar shows lifecycle state / PID / PGID / active profile / queue status
- Graceful shutdown: `SIGTERM` → wait up to 3s → `SIGKILL`
- Startup profiles are persisted in `tui/keel-runner.yaml`
- TUI write commands take priority over queued MCP write requests
- Launching the TUI also starts a local HTTP MCP server

## Dependencies

```toml
[dependencies]
anyhow = "1"
command-group = "5.0.1"
crossterm = "0.29"
nix = { version = "0.30", features = ["signal", "process"] }
ratatui = { version = "0.26", features = ["crossterm"] }
```

Note: `ratatui` is pinned to `0.26` for Rust 1.87 compatibility.

## Run

```bash
cd tui
make build
./target/release/keel-sample-launcher
```

Or with Gradle (requires Rust toolchain):

```bash
cd tui
cargo run
```

On first run the launcher creates `tui/keel-runner.yaml` if it does not already exist.

## Config

Launcher startup settings live in:

```bash
tui/keel-runner.yaml
```

Representative shape:

```yaml
version: 1
default_profile: sample-dev
auto_start: true
mcp:
  enabled: true
  host: 127.0.0.1
  port: 8765
  path: /mcp
  auth:
    enabled: false
    bearer_token: null
profiles:
  sample-dev:
    program: ./gradlew
    args:
      - :keel-samples:run
    cwd: .
    env:
      KEEL_ENV: development
    stop_timeout_ms: 3000
```

Profile changes update launcher configuration only. They do not automatically restart the app.

## HTTP MCP

When the TUI launcher is running, it also starts:

```bash
GET  http://127.0.0.1:8765/healthz
POST http://127.0.0.1:8765/mcp
```

The health endpoint returns `200 OK` when the embedded HTTP server is alive.

The MCP server exposes these tools:

- `runner_status`
- `runner_get_logs`
- `runner_list_profiles`
- `runner_get_profile`
- `runner_switch_profile`
- `runner_update_profile`
- `runner_set_default_profile`
- `runner_start`
- `runner_stop`
- `runner_restart`
- `runner_shutdown`

If `mcp.auth.enabled` is `true`, clients must send:

```bash
Authorization: Bearer <token>
```

## Runtime Semantics

- TUI and MCP calls share the same lifecycle logic and runtime snapshot
- Only one write action runs at a time
- TUI write actions outrank queued MCP write actions
- If one write action is already executing, later write actions wait
- Switching the active profile does not restart the running app
