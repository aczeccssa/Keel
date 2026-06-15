//! TUI rendering and keyboard event handling.

use anyhow::Result;
use crossterm::event::{KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::layout::Constraint;
use ratatui::prelude::{Line, Span, Style, Text};
use ratatui::style::Modifier;
use ratatui::widgets::{Block, Borders, Paragraph};
use ratatui::Frame;

use crate::app::App;
use crate::control::WriteCommand;
use crate::runtime::LifecycleState;

const HELP_LINE: &str = "Keys: Ctrl+R restart | Ctrl+P stop | Ctrl+C exit | \
     ↑↓ PgUp PgDn scroll | Home/End top/bottom | f tail | l clear | q quit";

fn status_span(state: LifecycleState) -> Span<'static> {
    use ratatui::prelude::Color;

    match state {
        LifecycleState::Running => Span::styled(
            "● RUNNING",
            Style::default()
                .fg(Color::Green)
                .add_modifier(Modifier::BOLD),
        ),
        LifecycleState::Starting | LifecycleState::Restarting | LifecycleState::Stopping => {
            Span::styled(
                format!("◐ {:?}", state).to_uppercase(),
                Style::default()
                    .fg(Color::LightBlue)
                    .add_modifier(Modifier::BOLD),
            )
        }
        LifecycleState::Failed => Span::styled(
            "▲ FAILED",
            Style::default().fg(Color::Red).add_modifier(Modifier::BOLD),
        ),
        LifecycleState::Exited => Span::styled(
            "◆ EXITED",
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD),
        ),
        LifecycleState::Idle => Span::styled(
            "■ IDLE",
            Style::default()
                .fg(Color::Yellow)
                .add_modifier(Modifier::BOLD),
        ),
    }
}

fn pid_line(app: &App) -> Line<'static> {
    Line::from(vec![
        Span::styled("Status: ", Style::default().add_modifier(Modifier::BOLD)),
        status_span(app.snapshot.lifecycle),
        Span::raw(format!(
            "    PID: {}    PGID: {}    profile: {}",
            app.snapshot
                .pid
                .map(|v| v.to_string())
                .unwrap_or_else(|| "-".into()),
            app.snapshot
                .pgid
                .map(|v| v.to_string())
                .unwrap_or_else(|| "-".into()),
            app.snapshot.active_profile
        )),
    ])
}

pub fn render(frame: &mut Frame, app: &App) {
    use ratatui::layout::Layout;

    let area = frame.size();
    let sections = Layout::vertical([
        Constraint::Length(7),
        Constraint::Min(8),
        Constraint::Length(1),
    ])
    .split(area);

    let info = Paragraph::new(vec![
        pid_line(app),
        Line::raw(format!("config: {}", app.snapshot.config_path.display())),
        Line::raw(format!(
            "queue: {}    busy: {}",
            app.snapshot.queue_depth, app.snapshot.busy
        )),
        Line::raw(format!(
            "mcp: {}://{}:{}{}    auth: {}    state: {:?}",
            "http",
            app.snapshot.mcp.host,
            app.snapshot.mcp.port,
            app.snapshot.mcp.path,
            if app.snapshot.mcp.auth_enabled { "on" } else { "off" },
            app.snapshot.mcp.server_state
        )),
        Line::raw(HELP_LINE),
    ])
    .block(
        Block::default()
            .borders(Borders::ALL)
            .title("keel-sample-launcher"),
    );
    frame.render_widget(info, sections[0]);

    let output_area = sections[1];
    let inner_height = output_area.height.saturating_sub(2) as usize;
    let logs = app.log_entries();
    let content_height = logs.len();
    let max_scroll = content_height.saturating_sub(inner_height);
    let scroll = if app.follow_tail {
        max_scroll
    } else {
        app.scroll.min(max_scroll)
    };

    let text = Text::from(logs.iter().map(log_to_line).collect::<Vec<_>>());
    let output = Paragraph::new(text)
        .block(Block::default().borders(Borders::ALL).title("output"))
        .scroll((scroll as u16, 0));
    frame.render_widget(output, output_area);

    let footer = Line::from(vec![
        Span::styled("last: ", Style::default().add_modifier(Modifier::BOLD)),
        Span::raw(
            app.snapshot
                .last_result
                .clone()
                .unwrap_or_else(|| "None".to_string()),
        ),
        Span::raw("    "),
        Span::styled("lines: ", Style::default().add_modifier(Modifier::BOLD)),
        Span::raw(app.snapshot.log_line_count.to_string()),
        Span::raw("    "),
        Span::styled("follow: ", Style::default().add_modifier(Modifier::BOLD)),
        Span::raw(if app.follow_tail { "on" } else { "off" }),
    ]);
    frame.render_widget(Paragraph::new(footer), sections[2]);
}

fn log_to_line(entry: &crate::runtime::LogEntry) -> Line<'static> {
    use ratatui::prelude::Color;
    use ratatui::prelude::Span;

    let style = match entry.kind {
        crate::runtime::LogKind::System => Style::default().fg(Color::Cyan),
        crate::runtime::LogKind::Stdout => Style::default().fg(Color::White),
        crate::runtime::LogKind::Stderr => Style::default().fg(Color::LightRed),
    };
    Line::from(Span::styled(entry.text.clone(), style))
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
            app.enqueue(WriteCommand::Restart)?;
        }
        (KeyCode::Char('p'), m) if m.contains(KeyModifiers::CONTROL) => {
            app.enqueue(WriteCommand::Stop)?;
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

#[cfg(test)]
mod tests {
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    use super::*;
    use crate::control::ControlCore;

    fn temp_repo_root() -> std::path::PathBuf {
        let unique = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let root = std::env::temp_dir().join(format!("keel-launcher-ui-test-{unique}"));
        fs::create_dir_all(root.join("tui")).expect("create tui dir");
        fs::write(root.join("gradlew"), "#!/bin/sh\n").expect("write gradlew marker");
        root
    }

    #[test]
    fn ctrl_r_dispatches_restart_command() {
        let core = ControlCore::new(temp_repo_root()).expect("core");
        let mut app = App::new(core.handle());
        handle_key(
            &mut app,
            KeyEvent::new(KeyCode::Char('r'), KeyModifiers::CONTROL),
        )
        .expect("key handled");
        assert_eq!(app.control.snapshot().queue_depth, 1);
    }
}
