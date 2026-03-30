//! TUI rendering and keyboard event handling.

use anyhow::Result;
use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::layout::Constraint;
use ratatui::prelude::{Line, Span, Style, Text};
use ratatui::style::Modifier;
use ratatui::widgets::{Block, Borders, Paragraph};
use ratatui::Frame;

use crate::app::App;
use crate::config::PROC_PROGRAM;

const HELP_LINE: &str =
    "Keys: Ctrl+R restart | Ctrl+P stop | Ctrl+C exit | \
     ↑↓ PgUp PgDn scroll | Home/End top/bottom | f tail | l clear | q quit";

fn status_span(running: bool) -> Span<'static> {
    use ratatui::prelude::Color;

    if running {
        Span::styled("● RUNNING", Style::default().fg(Color::Green).add_modifier(Modifier::BOLD))
    } else {
        Span::styled("■ STOPPED", Style::default().fg(Color::Yellow).add_modifier(Modifier::BOLD))
    }
}

fn pid_line(app: &App) -> Line<'static> {
    Line::from(vec![
        Span::styled("Status: ", Style::default().add_modifier(Modifier::BOLD)),
        status_span(app.running),
        Span::raw(format!(
            "    PID: {}    PGID: {}",
            app.pid.map(|v| v.to_string()).unwrap_or_else(|| "-".into()),
            app.pgid.map(|v| v.to_string()).unwrap_or_else(|| "-".into())
        )),
    ])
}

pub fn render(frame: &mut Frame, app: &App) {
    use ratatui::layout::Layout;

    let area = frame.size();
    let sections = Layout::vertical([
        Constraint::Length(5),
        Constraint::Min(8),
        Constraint::Length(1),
    ])
    .split(area);

    // Header
    let info = Paragraph::new(vec![
        pid_line(app),
        Line::raw(format!("cwd: {}", app.root_dir.display())),
        Line::raw(HELP_LINE),
    ])
    .block(
        Block::default()
            .borders(Borders::ALL)
            .title(format!("{} | {}", PROC_PROGRAM, app.command_string())),
    );
    frame.render_widget(info, sections[0]);

    // Log output
    let output_area = sections[1];
    let inner_height = output_area.height.saturating_sub(2) as usize;
    let content_height = app.logs.len();
    let max_scroll = content_height.saturating_sub(inner_height);
    let scroll = if app.follow_tail {
        max_scroll
    } else {
        app.scroll.min(max_scroll)
    };

    let text = Text::from(app.logs.iter().map(crate::app::LogEntry::to_line).collect::<Vec<_>>());
    let output = Paragraph::new(text)
        .block(Block::default().borders(Borders::ALL).title("output"))
        .scroll((scroll as u16, 0));
    frame.render_widget(output, output_area);

    // Footer
    let footer = Line::from(vec![
        Span::styled("last: ", Style::default().add_modifier(Modifier::BOLD)),
        Span::raw(app.last_status.clone()),
        Span::raw("    "),
        Span::styled("lines: ", Style::default().add_modifier(Modifier::BOLD)),
        Span::raw(app.logs.len().to_string()),
        Span::raw("    "),
        Span::styled("follow: ", Style::default().add_modifier(Modifier::BOLD)),
        Span::raw(if app.follow_tail { "on" } else { "off" }),
    ]);
    frame.render_widget(Paragraph::new(footer), sections[2]);
}

pub fn handle_key(app: &mut App, key: KeyEvent) -> Result<()> {
    if key.kind != KeyEventKind::Press {
        return Ok(());
    }

    match (key.code, key.modifiers) {
        (KeyCode::Char('c'), m) if m.contains(KeyModifiers::CONTROL) => {
            app.should_quit = true;
        }
        (KeyCode::Char('r'), m) if m.contains(KeyModifiers::CONTROL) => {
            app.start()?;
        }
        (KeyCode::Char('p'), m) if m.contains(KeyModifiers::CONTROL) => {
            app.stop()?;
        }
        (KeyCode::Char('q'), _) | (KeyCode::Esc, _) => {
            app.should_quit = true;
        }
        (KeyCode::Up, _) => app.scroll_up(1),
        (KeyCode::Down, _) => app.scroll_down(1),
        (KeyCode::PageUp, _) => app.scroll_up(10),
        (KeyCode::PageDown, _) => app.scroll_down(10),
        (KeyCode::Home, _) => app.scroll_top(),
        (KeyCode::End, _) => app.scroll_bottom(),
        (KeyCode::Char('f'), _) => {
            app.follow_tail = !app.follow_tail;
            if app.follow_tail {
                app.scroll_bottom();
            }
        }
        (KeyCode::Char('l'), _) => app.clear_logs(),
        _ => {}
    }

    Ok(())
}
