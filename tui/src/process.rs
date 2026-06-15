//! Process and filesystem helpers.

use std::env;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::thread;

use anyhow::Result;
use nix::{
    errno::Errno,
    sys::signal::{killpg, Signal},
    unistd::Pid,
};

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

/// Read `reader` line-by-line and forward each line to a callback.
pub fn spawn_reader<R, F>(reader: R, mut on_line: F)
where
    R: std::io::Read + Send + 'static,
    F: FnMut(Result<String>) + Send + 'static,
{
    thread::spawn(move || {
        let reader = BufReader::new(reader);
        for line in reader.lines() {
            match line {
                Ok(line) => on_line(Ok(line)),
                Err(err) => {
                    on_line(Err(anyhow::anyhow!("Read error: {err}")));
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
