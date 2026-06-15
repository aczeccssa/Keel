//! Unified launcher control core.

use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, bail, Context, Result};
use serde::Serialize;

use crate::config::{ConfigStore, McpConfig, ProfilePatch, RunnerProfile, CONFIG_FILE_NAME};
use crate::runtime::{
    stop_timeout_for, LifecycleState, McpServerState, ProcessRuntime, RuntimeSnapshot,
    SnapshotWriter,
};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandSource {
    Tui,
    Mcp,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WriteCommand {
    Start,
    Stop,
    Restart,
    SwitchProfile {
        profile: String,
    },
    UpdateProfile {
        profile: String,
        patch: ProfilePatch,
    },
    SetDefaultProfile {
        profile: String,
    },
    ShutdownLauncher,
}

#[derive(Debug, Clone, Serialize)]
pub struct CommandResult {
    pub ok: bool,
    pub request_id: u64,
    pub action: String,
    pub message: String,
    pub state_before: LifecycleState,
    pub state_after: LifecycleState,
    pub active_profile: String,
    pub queue_depth: usize,
    pub last_error: Option<String>,
}

struct PendingWrite {
    request_id: u64,
    command: WriteCommand,
    responder: mpsc::Sender<CommandResult>,
}

struct ControlState {
    repo_root: PathBuf,
    config: ConfigStore,
    active_profile: String,
    runtime: ProcessRuntime,
    tui_queue: VecDeque<PendingWrite>,
    mcp_queue: VecDeque<PendingWrite>,
    busy: bool,
    shutdown_requested: bool,
}

pub struct ControlCore {
    state: Arc<Mutex<ControlState>>,
    snapshot: Arc<Mutex<RuntimeSnapshot>>,
    next_request_id: AtomicU64,
}

#[derive(Clone)]
pub struct ControlHandle {
    core: Arc<ControlCore>,
}

impl ControlCore {
    pub fn new(repo_root: PathBuf) -> Result<Arc<Self>> {
        let config_path = repo_root.join("tui").join(CONFIG_FILE_NAME);
        let config = ConfigStore::load_or_create(config_path.clone())?;
        let active_profile = config.default_profile().to_string();
        let snapshot = Arc::new(Mutex::new(RuntimeSnapshot::new(
            active_profile.clone(),
            config_path,
            config.mcp(),
        )));
        {
            let loaded_at = config
                .loaded_at()
                .duration_since(UNIX_EPOCH)
                .ok()
                .map(|v| v.as_millis());
            let mut state = snapshot.lock().expect("snapshot lock");
            state.config_loaded_at_epoch_ms = loaded_at;
        }

        let core = Arc::new(Self {
            state: Arc::new(Mutex::new(ControlState {
                repo_root,
                config,
                active_profile: active_profile.clone(),
                runtime: ProcessRuntime::default(),
                tui_queue: VecDeque::new(),
                mcp_queue: VecDeque::new(),
                busy: false,
                shutdown_requested: false,
            })),
            snapshot,
            next_request_id: AtomicU64::new(1),
        });

        Ok(core)
    }

    pub fn handle(self: &Arc<Self>) -> ControlHandle {
        ControlHandle { core: self.clone() }
    }

    pub fn tick(&self) -> Result<()> {
        let writer = SnapshotWriter::new(self.snapshot.clone());
        let mut state = self.state.lock().expect("control state lock");
        state.runtime.poll_exit(&writer)?;

        if state.busy {
            return Ok(());
        }

        let next = state
            .tui_queue
            .pop_front()
            .or_else(|| state.mcp_queue.pop_front());
        let Some(pending) = next else {
            self.refresh_queue_depths(&mut state);
            return Ok(());
        };

        state.busy = true;
        self.refresh_queue_depths(&mut state);
        let state_before = self.snapshot.lock().expect("snapshot lock").lifecycle;
        drop(writer);
        drop(state);

        let execution = self.execute(pending.request_id, pending.command.clone());
        let snapshot = self.snapshot.lock().expect("snapshot lock").clone();
        let _ = pending.responder.send(CommandResult {
            ok: execution.is_ok(),
            request_id: pending.request_id,
            action: format!("{:?}", pending.command),
            message: execution.unwrap_or_else(|err| err.to_string()),
            state_before,
            state_after: snapshot.lifecycle,
            active_profile: snapshot.active_profile,
            queue_depth: snapshot.queue_depth,
            last_error: snapshot.last_error,
        });

        let mut state = self.state.lock().expect("control state lock");
        state.busy = false;
        self.refresh_queue_depths(&mut state);
        Ok(())
    }

    fn execute(&self, request_id: u64, command: WriteCommand) -> Result<String> {
        let writer = SnapshotWriter::new(self.snapshot.clone());
        writer.update(|snapshot| {
            snapshot.busy = true;
            snapshot.last_command = Some(format!("{}:{command:?}", request_id));
            snapshot.last_error = None;
        });

        let result = self.execute_inner(command.clone(), &writer);
        writer.update(|snapshot| {
            snapshot.busy = false;
            if let Err(err) = &result {
                snapshot.last_error = Some(err.to_string());
                snapshot.last_result = Some(format!("Command failed: {err}"));
                if snapshot.lifecycle != LifecycleState::Running {
                    snapshot.lifecycle = LifecycleState::Failed;
                }
            }
        });

        result
    }

    fn execute_inner(&self, command: WriteCommand, writer: &SnapshotWriter) -> Result<String> {
        let mut state = self.state.lock().expect("control state lock");
        match command {
            WriteCommand::Start => {
                let profile = state.active_profile()?;
                let repo_root = state.repo_root.clone();
                state.runtime.start(&repo_root, &profile, writer)?;
                Ok(format!("Started profile {}", self.active_profile_name()))
            }
            WriteCommand::Stop => {
                let profile = state.active_profile()?;
                state.runtime.stop(stop_timeout_for(&profile), writer)?;
                Ok("Stopped process".to_string())
            }
            WriteCommand::Restart => {
                let profile = state.active_profile()?;
                let repo_root = state.repo_root.clone();
                state
                    .runtime
                    .restart(&repo_root, &profile, stop_timeout_for(&profile), writer)?;
                Ok(format!("Restarted profile {}", self.active_profile_name()))
            }
            WriteCommand::SwitchProfile { profile } => {
                if state.config.profile(&profile).is_none() {
                    bail!("Profile '{profile}' not found");
                }
                state.active_profile = profile.clone();
                writer.update(|snapshot| {
                    snapshot.active_profile = profile.clone();
                    snapshot.last_result = Some(format!("Switched active profile to {profile}"));
                });
                Ok(format!("Switched active profile to {profile}"))
            }
            WriteCommand::UpdateProfile { profile, patch } => {
                state.config.update_profile(&profile, patch)?;
                self.update_config_loaded_at(writer, state.config.loaded_at());
                Ok(format!("Updated profile {profile}"))
            }
            WriteCommand::SetDefaultProfile { profile } => {
                state.config.set_default_profile(&profile)?;
                self.update_config_loaded_at(writer, state.config.loaded_at());
                Ok(format!("Set default profile to {profile}"))
            }
            WriteCommand::ShutdownLauncher => {
                state.shutdown_requested = true;
                writer.update(|snapshot| {
                    snapshot.last_result = Some("Launcher shutdown requested".to_string());
                });
                Ok("Launcher shutdown requested".to_string())
            }
        }
    }

    fn update_config_loaded_at(&self, writer: &SnapshotWriter, loaded_at: SystemTime) {
        writer.update(|snapshot| {
            snapshot.config_loaded_at_epoch_ms = loaded_at
                .duration_since(UNIX_EPOCH)
                .ok()
                .map(|v| v.as_millis());
        });
    }

    fn refresh_queue_depths(&self, state: &mut ControlState) {
        let queue_depth = state.tui_queue.len() + state.mcp_queue.len();
        let busy = state.busy;
        let mut snapshot = self.snapshot.lock().expect("snapshot lock");
        snapshot.queue_depth = queue_depth;
        snapshot.busy = busy;
    }

    pub fn should_shutdown(&self) -> bool {
        self.state
            .lock()
            .expect("control state lock")
            .shutdown_requested
    }

    fn active_profile_name(&self) -> String {
        self.snapshot
            .lock()
            .expect("snapshot lock")
            .active_profile
            .clone()
    }
}

impl ControlHandle {
    pub fn snapshot(&self) -> RuntimeSnapshot {
        self.core.snapshot.lock().expect("snapshot lock").clone()
    }

    pub fn queue_write(&self, source: CommandSource, command: WriteCommand) -> Result<u64> {
        let request_id = self.core.next_request_id.fetch_add(1, Ordering::SeqCst);
        let (tx, _rx) = mpsc::channel();
        let mut state = self.core.state.lock().expect("control state lock");
        let pending = PendingWrite {
            request_id,
            command,
            responder: tx,
        };
        match source {
            CommandSource::Tui => state.tui_queue.push_back(pending),
            CommandSource::Mcp => state.mcp_queue.push_back(pending),
        }
        self.core.refresh_queue_depths(&mut state);
        Ok(request_id)
    }

    pub fn execute_write(
        &self,
        source: CommandSource,
        command: WriteCommand,
        timeout: Duration,
    ) -> Result<CommandResult> {
        let request_id = self.core.next_request_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = mpsc::channel();
        {
            let mut state = self.core.state.lock().expect("control state lock");
            let pending = PendingWrite {
                request_id,
                command,
                responder: tx,
            };
            match source {
                CommandSource::Tui => state.tui_queue.push_back(pending),
                CommandSource::Mcp => state.mcp_queue.push_back(pending),
            }
            self.core.refresh_queue_depths(&mut state);
        }

        rx.recv_timeout(timeout)
            .map_err(|_| anyhow!("Timed out waiting for command completion"))
    }

    pub fn list_profiles(&self) -> Vec<String> {
        self.core
            .state
            .lock()
            .expect("control state lock")
            .config
            .profile_names()
    }

    pub fn get_profile(&self, name: &str) -> Result<RunnerProfile> {
        self.core
            .state
            .lock()
            .expect("control state lock")
            .config
            .profile(name)
            .with_context(|| format!("Profile '{name}' not found"))
    }

    pub fn auto_start_enabled(&self) -> bool {
        self.core
            .state
            .lock()
            .expect("control state lock")
            .config
            .file()
            .auto_start
    }

    pub fn mcp_config(&self) -> McpConfig {
        self.core
            .state
            .lock()
            .expect("control state lock")
            .config
            .mcp()
    }

    pub fn set_mcp_server_state(&self, server_state: McpServerState) {
        let mut snapshot = self.core.snapshot.lock().expect("snapshot lock");
        snapshot.mcp.server_state = server_state;
    }

    pub fn tick(&self) -> Result<()> {
        self.core.tick()
    }

    pub fn should_shutdown(&self) -> bool {
        self.core.should_shutdown()
    }
}

impl ControlState {
    fn active_profile(&self) -> Result<RunnerProfile> {
        self.config
            .profile(&self.active_profile)
            .ok_or_else(|| anyhow!("Active profile '{}' not found", self.active_profile))
    }
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;

    fn temp_repo_root() -> PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("keel-launcher-test-{unique}"));
        fs::create_dir_all(root.join("tui")).expect("create tui dir");
        fs::write(root.join("gradlew"), "#!/bin/sh\n").expect("write gradlew marker");
        root
    }

    #[test]
    fn gives_tui_priority_over_queued_mcp_commands() {
        let repo_root = temp_repo_root();
        let core = ControlCore::new(repo_root).expect("core");
        let handle = core.handle();

        handle
            .queue_write(CommandSource::Mcp, WriteCommand::Restart)
            .unwrap();
        handle
            .queue_write(CommandSource::Tui, WriteCommand::Stop)
            .unwrap();

        let state = core.state.lock().expect("state");
        assert!(state.tui_queue.front().is_some());
    }

    #[test]
    fn switch_profile_updates_snapshot_without_restarting() {
        let repo_root = temp_repo_root();
        let core = ControlCore::new(repo_root).expect("core");
        let handle = core.handle();
        handle
            .queue_write(
                CommandSource::Mcp,
                WriteCommand::SwitchProfile {
                    profile: "sample-dev".to_string(),
                },
            )
            .expect("queued");

        core.tick().expect("tick succeeds");
        assert_eq!(handle.snapshot().active_profile, "sample-dev");
        assert!(handle.snapshot().pid.is_none());
    }
}
