//! Process lifecycle: spawning, signal handling, and output reading.

use std::env;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::sync::mpsc::Sender;
use std::thread;

use anyhow::Result;
use nix::{
    errno::Errno,
    sys::signal::{killpg, Signal},
    unistd::Pid,
};

use crate::app::{AppEvent, LogKind};

/// Walk upward from `cwd` and `exe` parent directories looking for `gradlew`.
pub fn detect_project_root() -> PathBuf {
    let mut candidates = Vec::new();

    if let Ok(cwd) = env::current_dir() {
        candidates.push(cwd);
    }

    if let Ok(exe) = env::current_exe() {
        if let Some(parent) = exe.parent() {
            candidates.push(parent.to_path_buf());
        }
    }

    for base in candidates {
        for ancestor in base.ancestors() {
            if ancestor.join("gradlew").exists() {
                return ancestor.to_path_buf();
            }
        }
    }

    env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

/// Read `reader` line-by-line and forward each line to the main thread.
pub fn spawn_reader<R>(reader: R, tx: Sender<AppEvent>, kind: LogKind)
where
    R: std::io::Read + Send + 'static,
{
    thread::spawn(move || {
        let reader = BufReader::new(reader);
        for line in reader.lines() {
            match line {
                Ok(line) => {
                    let _ = tx.send(AppEvent::Output { kind, line });
                }
                Err(err) => {
                    let _ = tx.send(AppEvent::Output {
                        kind: LogKind::System,
                        line: format!("Read error: {err}"),
                    });
                    break;
                }
            }
        }
    });
}

/// Send a Unix signal to a process group.
pub fn send_signal(pgid: u32, signal: Signal) -> Result<()> {
    match killpg(Pid::from_raw(pgid as i32), signal) {
        Ok(()) | Err(Errno::ESRCH) => Ok(()),
        Err(err) => Err(anyhow::anyhow!(
            "Failed to send {:?} to PGID {pgid}: {err}",
            signal,
        )),
    }
}
