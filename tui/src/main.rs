mod app;
mod config;
mod process;
mod ui;

use anyhow::Result;
use crossterm::event::{self, Event};
use crossterm::terminal::{disable_raw_mode, enable_raw_mode, LeaveAlternateScreen};
use crossterm::{
    execute,
    terminal::EnterAlternateScreen,
    event::{DisableMouseCapture, EnableMouseCapture},
};
use ratatui::backend::CrosstermBackend;
use ratatui::Terminal;

use crate::config::POLL_INTERVAL;
use crate::ui::{handle_key, render};

fn run() -> Result<()> {
    let mut stdout = std::io::stdout();
    execute!(stdout, EnterAlternateScreen, EnableMouseCapture)?;
    enable_raw_mode()?;
    let backend = CrosstermBackend::new(stdout);
    let mut terminal = Terminal::new(backend)?;

    let mut app = app::App::new();
    app.start()?;

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

    app.stop()?;
    let mut stdout = std::io::stdout();
    let _ = execute!(stdout, LeaveAlternateScreen, DisableMouseCapture);
    disable_raw_mode().ok();
    Ok(())
}

fn main() -> Result<()> {
    let result = run();
    let mut stdout = std::io::stdout();
    let _ = execute!(stdout, LeaveAlternateScreen, DisableMouseCapture);
    disable_raw_mode().ok();
    result
}
