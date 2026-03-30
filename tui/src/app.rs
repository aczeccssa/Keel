//! Data models: log entry types and application state.

use std::{
    collections::VecDeque,
    path::PathBuf,
    process::{Command, Stdio},
    sync::mpsc::{self, Receiver},
    thread,
    time::{Duration, Instant},
};

use anyhow::{Context, Result};
use command_group::{CommandGroup, GroupChild};
use nix::{
    sys::signal::Signal,
};

use crate::config::PROC_PROGRAM;
use crate::config::PROC_ARGS;
use crate::config::MAX_LOG_LINES;
use crate::config::STOP_TIMEOUT;

#[derive(Debug, Clone, Copy)]
pub enum LogKind {
    System,
    Stdout,
    Stderr,
}

#[derive(Debug, Clone)]
pub struct LogEntry {
    pub kind: LogKind,
    pub text: String,
}

impl LogEntry {
    pub fn to_line(&self) -> ratatui::text::Line<'static> {
        use ratatui::prelude::Color;
        use ratatui::prelude::Line;
        use ratatui::prelude::Span;
        use ratatui::prelude::Style;

        let style = match self.kind {
            LogKind::System => Style::default().fg(Color::Cyan),
            LogKind::Stdout => Style::default().fg(Color::White),
            LogKind::Stderr => Style::default().fg(Color::LightRed),
        };
        Line::from(Span::styled(self.text.clone(), style))
    }
}

#[derive(Debug)]
pub enum AppEvent {
    Output { kind: LogKind, line: String },
}

pub struct App {
    pub root_dir: PathBuf,
    proc: Option<GroupChild>,
    rx: Option<Receiver<AppEvent>>,
    pub running: bool,
    pub pid: Option<u32>,
    pub pgid: Option<u32>,
    pub logs: VecDeque<LogEntry>,
    pub scroll: usize,
    pub follow_tail: bool,
    pub should_quit: bool,
    pub last_status: String,
}

impl App {
    pub fn new() -> Self {
        let root_dir = crate::process::detect_project_root();
        Self {
            root_dir,
            proc: None,
            rx: None,
            running: false,
            pid: None,
            pgid: None,
            logs: VecDeque::with_capacity(MAX_LOG_LINES),
            scroll: 0,
            follow_tail: true,
            should_quit: false,
            last_status: "Not started".to_string(),
        }
    }

    pub fn command_string(&self) -> String {
        std::iter::once(PROC_PROGRAM)
            .chain(PROC_ARGS.iter().copied())
            .collect::<Vec<_>>()
            .join(" ")
    }

    pub fn push_log(&mut self, kind: LogKind, text: impl Into<String>) {
        if self.logs.len() >= MAX_LOG_LINES {
            self.logs.pop_front();
        }
        self.logs.push_back(LogEntry {
            kind,
            text: text.into(),
        });
        if self.follow_tail {
            self.scroll = usize::MAX;
        }
    }

    pub fn push_system(&mut self, text: impl Into<String>) {
        self.push_log(LogKind::System, text);
    }

    pub fn clear_logs(&mut self) {
        self.logs.clear();
        self.scroll = 0;
        self.push_system("Logs cleared");
    }

    pub fn start(&mut self) -> Result<()> {
        self.stop()?;
        self.logs.clear();
        self.follow_tail = true;
        self.scroll = usize::MAX;

        let command = self.command_string();
        self.push_system(format!("Starting: {command}"));
        self.push_system(format!("Working dir: {}", self.root_dir.display()));

        let mut cmd = Command::new(PROC_PROGRAM);
        cmd.args(PROC_ARGS)
            .current_dir(&self.root_dir)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let mut child = cmd
            .group_spawn()
            .with_context(|| format!("Failed to spawn command: {command}"))?;

        let pgid = child.id();
        let (pid, stdout, stderr) = {
            let inner = child.inner();
            let pid = inner.id();
            let stdout = inner.stdout.take();
            let stderr = inner.stderr.take();
            (pid, stdout, stderr)
        };

        let (tx, rx) = mpsc::channel();
        if let Some(stdout) = stdout {
            crate::process::spawn_reader(stdout, tx.clone(), LogKind::Stdout);
        }
        if let Some(stderr) = stderr {
            crate::process::spawn_reader(stderr, tx.clone(), LogKind::Stderr);
        }

        self.pid = Some(pid);
        self.pgid = Some(pgid);
        self.running = true;
        self.last_status = format!("Running (PID {pid}, PGID {pgid})");
        self.push_system(self.last_status.clone());
        self.proc = Some(child);
        self.rx = Some(rx);
        Ok(())
    }

    pub fn stop(&mut self) -> Result<()> {
        if self.proc.is_none() {
            self.running = false;
            self.pid = None;
            self.pgid = None;
            return Ok(());
        }

        let mut child = self.proc.take().expect("checked is_some above");
        let pgid = child.id();
        self.push_system(format!("Stopping PGID {pgid} ..."));

        crate::process::send_signal(pgid, Signal::SIGTERM)?;
        let deadline = Instant::now() + STOP_TIMEOUT;

        let exit_status = loop {
            match child.try_wait().context("Failed to wait for child")? {
                Some(status) => break status,
                None if Instant::now() < deadline => {
                    thread::sleep(Duration::from_millis(100));
                }
                None => {
                    self.push_system("SIGTERM timed out, sending SIGKILL");
                    crate::process::send_signal(pgid, Signal::SIGKILL)?;
                    break child.wait().context("Failed to wait after SIGKILL")?;
                }
            }
        };

        self.running = false;
        self.pid = None;
        self.pgid = None;
        self.last_status = format!("Stopped (exit: {exit_status})");
        self.push_system(self.last_status.clone());
        self.drain_events();
        Ok(())
    }

    pub fn on_tick(&mut self) -> Result<()> {
        self.drain_events();

        if let Some(child) = self.proc.as_mut() {
            if let Some(status) = child.try_wait().context("Failed to check child status")? {
                self.running = false;
                self.pid = None;
                self.pgid = None;
                self.proc = None;
                self.last_status = format!("Process exited (exit: {status})");
                self.push_system(self.last_status.clone());
                self.drain_events();
            }
        }

        Ok(())
    }

    pub fn drain_events(&mut self) {
        let mut buffered = Vec::new();
        if let Some(rx) = &self.rx {
            while let Ok(event) = rx.try_recv() {
                buffered.push(event);
            }
        }

        for event in buffered {
            match event {
                AppEvent::Output { kind, line } => self.push_log(kind, line),
            }
        }
    }

    pub fn scroll_up(&mut self, amount: usize) {
        self.follow_tail = false;
        self.scroll = self.scroll.saturating_sub(amount);
    }

    pub fn scroll_down(&mut self, amount: usize) {
        self.follow_tail = false;
        self.scroll = self.scroll.saturating_add(amount);
    }

    pub fn scroll_top(&mut self) {
        self.follow_tail = false;
        self.scroll = 0;
    }

    pub fn scroll_bottom(&mut self) {
        self.follow_tail = true;
        self.scroll = usize::MAX;
    }
}
