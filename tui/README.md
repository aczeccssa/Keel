# keel-sample TUI Launcher

A TUI launcher for monitoring and managing the `keel-sample` process, built with `ratatui` + `crossterm`.

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
- Status bar shows PID / PGID / line count / follow state
- Graceful shutdown: `SIGTERM` → wait up to 3s → `SIGKILL`

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

## Tunables

Top of `src/main.rs`:

- `PROC_PROGRAM` — launch program (default `./gradlew`)
- `PROC_ARGS` — launch arguments (default `[":keel-sample:run"]`)
- `MAX_LOG_LINES` — max log lines kept (default 2000)
- `STOP_TIMEOUT` — graceful shutdown timeout (default 3s)
- `POLL_INTERVAL` — event poll interval (default 50ms)
