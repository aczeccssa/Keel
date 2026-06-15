//! Child process runtime execution.

use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Context, Result};
use command_group::{CommandGroup, GroupChild};
use nix::sys::signal::Signal;
use serde::Serialize;

use crate::config::{McpConfig, RunnerProfile, DEFAULT_STOP_TIMEOUT_MS, MAX_LOG_LINES};
use crate::process;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum LogKind {
    System,
    Stdout,
    Stderr,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct LogEntry {
    pub kind: LogKind,
    pub text: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum LifecycleState {
    Idle,
    Starting,
    Running,
    Stopping,
    Restarting,
    Failed,
    Exited,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum McpServerState {
    Disabled,
    Starting,
    Running,
}

#[derive(Debug, Clone, Serialize)]
pub struct McpSnapshot {
    pub enabled: bool,
    pub host: String,
    pub port: u16,
    pub path: String,
    pub auth_enabled: bool,
    pub server_state: McpServerState,
}

#[derive(Debug, Clone, Serialize)]
pub struct RuntimeSnapshot {
    pub lifecycle: LifecycleState,
    pub active_profile: String,
    pub pid: Option<u32>,
    pub pgid: Option<u32>,
    pub last_command: Option<String>,
    pub last_error: Option<String>,
    pub last_exit_status: Option<String>,
    pub queue_depth: usize,
    pub busy: bool,
    pub log_line_count: usize,
    pub config_path: PathBuf,
    pub config_loaded_at_epoch_ms: Option<u128>,
    pub last_result: Option<String>,
    pub mcp: McpSnapshot,
    pub logs: VecDeque<LogEntry>,
}

impl RuntimeSnapshot {
    pub fn new(active_profile: String, config_path: PathBuf, mcp_config: McpConfig) -> Self {
        Self {
            lifecycle: LifecycleState::Idle,
            active_profile,
            pid: None,
            pgid: None,
            last_command: None,
            last_error: None,
            last_exit_status: None,
            queue_depth: 0,
            busy: false,
            log_line_count: 0,
            config_path,
            config_loaded_at_epoch_ms: None,
            last_result: None,
            mcp: McpSnapshot {
                enabled: mcp_config.enabled,
                host: mcp_config.host,
                port: mcp_config.port,
                path: mcp_config.path,
                auth_enabled: mcp_config.auth.enabled,
                server_state: if mcp_config.enabled {
                    McpServerState::Starting
                } else {
                    McpServerState::Disabled
                },
            },
            logs: VecDeque::with_capacity(MAX_LOG_LINES),
        }
    }
}

#[derive(Default)]
pub struct ProcessRuntime {
    child: Option<GroupChild>,
}

pub struct SnapshotWriter {
    inner: Arc<Mutex<RuntimeSnapshot>>,
}

impl SnapshotWriter {
    pub fn new(inner: Arc<Mutex<RuntimeSnapshot>>) -> Self {
        Self { inner }
    }

    pub fn update<F>(&self, mutator: F)
    where
        F: FnOnce(&mut RuntimeSnapshot),
    {
        let mut snapshot = self.inner.lock().expect("snapshot lock");
        mutator(&mut snapshot);
    }

    pub fn push_log(&self, kind: LogKind, text: impl Into<String>) {
        self.update(|snapshot| {
            if snapshot.logs.len() >= MAX_LOG_LINES {
                snapshot.logs.pop_front();
            }
            snapshot.logs.push_back(LogEntry {
                kind,
                text: text.into(),
            });
            snapshot.log_line_count = snapshot.logs.len();
        });
    }
}

impl ProcessRuntime {
    pub fn start(
        &mut self,
        repo_root: &Path,
        profile: &RunnerProfile,
        snapshot: &SnapshotWriter,
    ) -> Result<()> {
        if self.child.is_some() {
            snapshot.push_log(LogKind::System, "Process already running");
            return Ok(());
        }

        snapshot.update(|state| {
            state.lifecycle = LifecycleState::Starting;
            state.last_error = None;
        });

        let cwd = resolve_working_dir(repo_root, &profile.cwd);
        let command_string = format!("{} {}", profile.program, profile.args.join(" "));
        snapshot.push_log(LogKind::System, format!("Starting: {command_string}"));
        snapshot.push_log(LogKind::System, format!("Working dir: {}", cwd.display()));

        let mut command = Command::new(&profile.program);
        command
            .args(&profile.args)
            .current_dir(&cwd)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .envs(profile.env.clone());

        let mut child = command
            .group_spawn()
            .with_context(|| format!("Failed to spawn command: {command_string}"))?;

        let pgid = child.id();
        let (pid, stdout, stderr) = {
            let inner = child.inner();
            let pid = inner.id();
            let stdout = inner.stdout.take();
            let stderr = inner.stderr.take();
            (pid, stdout, stderr)
        };

        if let Some(stdout) = stdout {
            let writer = SnapshotWriter::new(snapshot.inner.clone());
            process::spawn_reader(stdout, move |line| match line {
                Ok(line) => writer.push_log(LogKind::Stdout, line),
                Err(err) => writer.push_log(LogKind::System, err.to_string()),
            });
        }

        if let Some(stderr) = stderr {
            let writer = SnapshotWriter::new(snapshot.inner.clone());
            process::spawn_reader(stderr, move |line| match line {
                Ok(line) => writer.push_log(LogKind::Stderr, line),
                Err(err) => writer.push_log(LogKind::System, err.to_string()),
            });
        }

        snapshot.update(|state| {
            state.lifecycle = LifecycleState::Running;
            state.pid = Some(pid);
            state.pgid = Some(pgid);
            state.last_result = Some(format!("Running (PID {pid}, PGID {pgid})"));
        });
        snapshot.push_log(LogKind::System, format!("Running (PID {pid}, PGID {pgid})"));

        self.child = Some(child);
        Ok(())
    }

    pub fn stop(&mut self, timeout: Duration, snapshot: &SnapshotWriter) -> Result<()> {
        let Some(mut child) = self.child.take() else {
            snapshot.update(|state| {
                state.lifecycle = LifecycleState::Idle;
                state.pid = None;
                state.pgid = None;
                state.last_result = Some("Already stopped".to_string());
            });
            return Ok(());
        };

        let pgid = child.id();
        snapshot.update(|state| state.lifecycle = LifecycleState::Stopping);
        snapshot.push_log(LogKind::System, format!("Stopping PGID {pgid} ..."));
        process::send_signal(pgid, Signal::SIGTERM)?;
        let deadline = Instant::now() + timeout;

        let exit_status = loop {
            match child.try_wait().context("Failed to wait for child")? {
                Some(status) => break status,
                None if Instant::now() < deadline => {
                    std::thread::sleep(Duration::from_millis(100));
                }
                None => {
                    snapshot.push_log(LogKind::System, "SIGTERM timed out, sending SIGKILL");
                    process::send_signal(pgid, Signal::SIGKILL)?;
                    break child.wait().context("Failed to wait after SIGKILL")?;
                }
            }
        };

        snapshot.update(|state| {
            state.lifecycle = LifecycleState::Idle;
            state.pid = None;
            state.pgid = None;
            state.last_exit_status = Some(exit_status.to_string());
            state.last_result = Some(format!("Stopped (exit: {exit_status})"));
        });
        snapshot.push_log(LogKind::System, format!("Stopped (exit: {exit_status})"));
        Ok(())
    }

    pub fn restart(
        &mut self,
        repo_root: &Path,
        profile: &RunnerProfile,
        timeout: Duration,
        snapshot: &SnapshotWriter,
    ) -> Result<()> {
        snapshot.update(|state| state.lifecycle = LifecycleState::Restarting);
        if self.child.is_some() {
            self.stop(timeout, snapshot)?;
        }
        self.start(repo_root, profile, snapshot)
    }

    pub fn poll_exit(&mut self, snapshot: &SnapshotWriter) -> Result<()> {
        let Some(child) = self.child.as_mut() else {
            return Ok(());
        };

        if let Some(status) = child.try_wait().context("Failed to check child status")? {
            self.child = None;
            snapshot.update(|state| {
                state.lifecycle = LifecycleState::Exited;
                state.pid = None;
                state.pgid = None;
                state.last_exit_status = Some(status.to_string());
                state.last_result = Some(format!("Process exited (exit: {status})"));
            });
            snapshot.push_log(LogKind::System, format!("Process exited (exit: {status})"));
        }
        Ok(())
    }
}

pub fn stop_timeout_for(profile: &RunnerProfile) -> Duration {
    Duration::from_millis(profile.stop_timeout_ms.max(DEFAULT_STOP_TIMEOUT_MS))
}

fn resolve_working_dir(repo_root: &Path, cwd: &str) -> PathBuf {
    let candidate = PathBuf::from(cwd);
    if candidate.is_absolute() {
        candidate
    } else {
        repo_root.join(candidate)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stop_without_child_is_noop() {
        let mut runtime = ProcessRuntime::default();
        let snapshot = Arc::new(Mutex::new(RuntimeSnapshot::new(
            "sample-dev".to_string(),
            PathBuf::from("keel-runner.yaml"),
            crate::config::McpConfig::default_local(),
        )));
        let writer = SnapshotWriter::new(snapshot);

        let result = runtime.stop(Duration::from_millis(10), &writer);
        assert!(result.is_ok());
    }

    #[test]
    fn stop_timeout_uses_profile_value() {
        let profile = RunnerProfile {
            program: "./gradlew".to_string(),
            args: vec![],
            cwd: ".".to_string(),
            env: Default::default(),
            stop_timeout_ms: 12_345,
        };
        assert_eq!(stop_timeout_for(&profile), Duration::from_millis(12_345));
    }
}
