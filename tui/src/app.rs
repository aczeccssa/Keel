//! TUI view model built on top of the control core.

use anyhow::Result;

use crate::config::MAX_LOG_LINES;
use crate::control::{CommandSource, ControlHandle, WriteCommand};
use crate::runtime::{LogEntry, RuntimeSnapshot};

pub struct App {
    pub control: ControlHandle,
    pub snapshot: RuntimeSnapshot,
    pub scroll: usize,
    pub follow_tail: bool,
    pub should_quit: bool,
}

impl App {
    pub fn new(control: ControlHandle) -> Self {
        let snapshot = control.snapshot();
        Self {
            control,
            snapshot,
            scroll: 0,
            follow_tail: true,
            should_quit: false,
        }
    }

    pub fn enqueue(&mut self, command: WriteCommand) -> Result<()> {
        self.control.queue_write(CommandSource::Tui, command)?;
        self.snapshot = self.control.snapshot();
        Ok(())
    }

    pub fn clear_logs(&mut self) {
        self.snapshot.logs.clear();
        self.snapshot.log_line_count = 0;
        self.scroll = 0;
        self.push_local_system("Logs cleared");
    }

    pub fn on_tick(&mut self) -> Result<()> {
        self.control.tick()?;
        self.snapshot = self.control.snapshot();
        if self.follow_tail {
            self.scroll = usize::MAX;
        }
        if self.control.should_shutdown() {
            self.should_quit = true;
        }
        Ok(())
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

    pub fn log_entries(&self) -> Vec<LogEntry> {
        let mut logs = self.snapshot.logs.iter().cloned().collect::<Vec<_>>();
        if logs.len() > MAX_LOG_LINES {
            logs.drain(..logs.len() - MAX_LOG_LINES);
        }
        logs
    }

    fn push_local_system(&mut self, text: impl Into<String>) {
        if self.snapshot.logs.len() >= MAX_LOG_LINES {
            self.snapshot.logs.pop_front();
        }
        self.snapshot.logs.push_back(LogEntry {
            kind: crate::runtime::LogKind::System,
            text: text.into(),
        });
        self.snapshot.log_line_count = self.snapshot.logs.len();
    }
}
