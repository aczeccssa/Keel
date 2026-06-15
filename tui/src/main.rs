mod app;
mod config;
mod control;
mod mcp;
mod process;
mod runtime;
mod ui;

use std::env;
use std::time::Duration;

use anyhow::Result;
use crossterm::event::{self, Event};
use crossterm::terminal::{disable_raw_mode, enable_raw_mode, LeaveAlternateScreen};
use crossterm::{
    event::{DisableMouseCapture, EnableMouseCapture},
    execute,
    terminal::EnterAlternateScreen,
};
use ratatui::backend::CrosstermBackend;
use ratatui::Terminal;

use crate::app::App;
use crate::config::POLL_INTERVAL;
use crate::control::{CommandSource, ControlCore, WriteCommand};
use crate::mcp::start_http_mcp_server;
use crate::process::detect_project_root;
use crate::ui::{handle_key, render};

fn run_tui() -> Result<()> {
    let repo_root = detect_project_root();
    let core = ControlCore::new(repo_root)?;
    let handle = core.handle();
    let _server = start_http_mcp_server(handle.clone())?;

    if handle.auto_start_enabled() {
        handle.queue_write(CommandSource::Tui, WriteCommand::Start)?;
    }

    let mut stdout = std::io::stdout();
    execute!(stdout, EnterAlternateScreen, EnableMouseCapture)?;
    enable_raw_mode()?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;

    let mut app = App::new(handle.clone());

    loop {
        app.on_tick()?;
        terminal.draw(|frame| render(frame, &app))?;

        if app.should_quit {
            break;
        }

        if event::poll(POLL_INTERVAL)? {
            if let Event::Key(key) = event::read()? {
                handle_key(&mut app, key)?;
            }
        }
    }

    let _ = handle.execute_write(
        CommandSource::Tui,
        WriteCommand::Stop,
        Duration::from_secs(5),
    );

    let mut stdout = std::io::stdout();
    let _ = execute!(stdout, LeaveAlternateScreen, DisableMouseCapture);
    disable_raw_mode().ok();
    Ok(())
}

fn main() -> Result<()> {
    let _args = env::args().skip(1).collect::<Vec<_>>();
    let result = run_tui();

    let mut stdout = std::io::stdout();
    let _ = execute!(stdout, LeaveAlternateScreen, DisableMouseCapture);
    disable_raw_mode().ok();
    result
}
