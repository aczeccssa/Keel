//! Tunable constants.

use std::time::Duration;

pub const PROC_PROGRAM: &str = "./gradlew";
pub const PROC_ARGS: &[&str] = &[":keel-samples:run"];
pub const MAX_LOG_LINES: usize = 2_000;
pub const STOP_TIMEOUT: Duration = Duration::from_secs(3);
pub const POLL_INTERVAL: Duration = Duration::from_millis(50);
