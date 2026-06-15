//! Launcher config file schema and static tunables.

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;
use std::time::{Duration, SystemTime};

use anyhow::{bail, Context, Result};
use schemars::JsonSchema;
use serde::{Deserialize, Serialize};

pub const MAX_LOG_LINES: usize = 2_000;
pub const POLL_INTERVAL: Duration = Duration::from_millis(50);
pub const DEFAULT_STOP_TIMEOUT_MS: u64 = 3_000;
pub const DEFAULT_MCP_HOST: &str = "127.0.0.1";
pub const DEFAULT_MCP_PORT: u16 = 8765;
pub const DEFAULT_MCP_PATH: &str = "/mcp";
pub const CONFIG_FILE_NAME: &str = "keel-runner.yaml";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct RunnerConfigFile {
    pub version: u32,
    pub default_profile: String,
    pub auto_start: bool,
    #[serde(default)]
    pub mcp: McpConfig,
    pub profiles: BTreeMap<String, RunnerProfile>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct McpConfig {
    #[serde(default = "default_true")]
    pub enabled: bool,
    #[serde(default = "default_mcp_host")]
    pub host: String,
    #[serde(default = "default_mcp_port")]
    pub port: u16,
    #[serde(default = "default_mcp_path")]
    pub path: String,
    #[serde(default)]
    pub auth: McpAuthConfig,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct McpAuthConfig {
    #[serde(default)]
    pub enabled: bool,
    #[serde(default)]
    pub bearer_token: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, JsonSchema)]
pub struct RunnerProfile {
    pub program: String,
    pub args: Vec<String>,
    pub cwd: String,
    #[serde(default)]
    pub env: BTreeMap<String, String>,
    pub stop_timeout_ms: u64,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq, JsonSchema)]
pub struct ProfilePatch {
    pub program: Option<String>,
    pub args: Option<Vec<String>>,
    pub cwd: Option<String>,
    pub env: Option<BTreeMap<String, String>>,
    pub stop_timeout_ms: Option<u64>,
}

#[derive(Debug, Clone)]
pub struct ConfigStore {
    path: PathBuf,
    loaded_at: SystemTime,
    file: RunnerConfigFile,
}

impl RunnerConfigFile {
    pub fn from_yaml_str(input: &str) -> Result<Self> {
        let file: Self =
            serde_yaml::from_str(input).context("Failed to parse launcher YAML config")?;
        file.validate()?;
        Ok(file)
    }

    pub fn default_for_repo() -> Self {
        let mut profiles = BTreeMap::new();
        profiles.insert(
            "sample-dev".to_string(),
            RunnerProfile {
                program: "./gradlew".to_string(),
                args: vec![":keel-samples:run".to_string()],
                cwd: ".".to_string(),
                env: BTreeMap::from([("KEEL_ENV".to_string(), "development".to_string())]),
                stop_timeout_ms: DEFAULT_STOP_TIMEOUT_MS,
            },
        );

        Self {
            version: 1,
            default_profile: "sample-dev".to_string(),
            auto_start: true,
            mcp: McpConfig::default_local(),
            profiles,
        }
    }

    pub fn validate(&self) -> Result<()> {
        if self.version == 0 {
            bail!("version must be positive");
        }
        if self.default_profile.trim().is_empty() {
            bail!("default_profile must not be empty");
        }
        if !self.profiles.contains_key(&self.default_profile) {
            bail!("default_profile '{}' does not exist", self.default_profile);
        }
        if self.profiles.is_empty() {
            bail!("profiles must not be empty");
        }
        self.mcp.validate()?;

        for (name, profile) in &self.profiles {
            if name.trim().is_empty() {
                bail!("profile name must not be empty");
            }
            profile
                .validate()
                .with_context(|| format!("Invalid profile '{name}'"))?;
        }

        Ok(())
    }

    pub fn profile_mut(&mut self, name: &str) -> Option<&mut RunnerProfile> {
        self.profiles.get_mut(name)
    }
}

impl McpConfig {
    pub fn default_local() -> Self {
        Self {
            enabled: true,
            host: DEFAULT_MCP_HOST.to_string(),
            port: DEFAULT_MCP_PORT,
            path: DEFAULT_MCP_PATH.to_string(),
            auth: McpAuthConfig {
                enabled: false,
                bearer_token: None,
            },
        }
    }

    pub fn validate(&self) -> Result<()> {
        if !self.enabled {
            return Ok(());
        }
        if self.host.trim().is_empty() {
            bail!("mcp.host must not be empty");
        }
        if self.port == 0 {
            bail!("mcp.port must be within 1..=65535");
        }
        if self.path.trim().is_empty() || !self.path.starts_with('/') {
            bail!("mcp.path must start with '/'");
        }
        if self.auth.enabled {
            let token = self
                .auth
                .bearer_token
                .as_deref()
                .map(str::trim)
                .filter(|token| !token.is_empty());
            if token.is_none() {
                bail!("mcp.auth.bearer_token must not be empty when auth is enabled");
            }
        } else if self.host != DEFAULT_MCP_HOST {
            bail!("mcp.auth must be enabled for non-localhost listeners");
        }
        Ok(())
    }

    pub fn base_url(&self) -> String {
        format!("http://{}:{}", self.host, self.port)
    }
}

impl Default for McpConfig {
    fn default() -> Self {
        Self::default_local()
    }
}

impl Default for McpAuthConfig {
    fn default() -> Self {
        Self {
            enabled: false,
            bearer_token: None,
        }
    }
}

fn default_true() -> bool {
    true
}

fn default_mcp_host() -> String {
    DEFAULT_MCP_HOST.to_string()
}

fn default_mcp_port() -> u16 {
    DEFAULT_MCP_PORT
}

fn default_mcp_path() -> String {
    DEFAULT_MCP_PATH.to_string()
}

impl RunnerProfile {
    pub fn validate(&self) -> Result<()> {
        if self.program.trim().is_empty() {
            bail!("program must not be empty");
        }
        if self.cwd.trim().is_empty() {
            bail!("cwd must not be empty");
        }
        if self.stop_timeout_ms == 0 {
            bail!("stop_timeout_ms must be positive");
        }
        for key in self.env.keys() {
            if key.trim().is_empty() {
                bail!("environment variable keys must not be empty");
            }
        }
        Ok(())
    }

    pub fn apply_patch(&mut self, patch: &ProfilePatch) {
        if let Some(program) = &patch.program {
            self.program = program.clone();
        }
        if let Some(args) = &patch.args {
            self.args = args.clone();
        }
        if let Some(cwd) = &patch.cwd {
            self.cwd = cwd.clone();
        }
        if let Some(env) = &patch.env {
            self.env = env.clone();
        }
        if let Some(stop_timeout_ms) = patch.stop_timeout_ms {
            self.stop_timeout_ms = stop_timeout_ms;
        }
    }
}

impl ConfigStore {
    pub fn load_or_create(path: PathBuf) -> Result<Self> {
        if path.exists() {
            let content = fs::read_to_string(&path)
                .with_context(|| format!("Failed to read {}", path.display()))?;
            let file = RunnerConfigFile::from_yaml_str(&content)?;
            return Ok(Self {
                path,
                loaded_at: SystemTime::now(),
                file,
            });
        }

        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent)
                .with_context(|| format!("Failed to create config dir {}", parent.display()))?;
        }

        let store = Self {
            path,
            loaded_at: SystemTime::now(),
            file: RunnerConfigFile::default_for_repo(),
        };
        store.save()?;
        Ok(store)
    }

    pub fn save(&self) -> Result<()> {
        let serialized =
            serde_yaml::to_string(&self.file).context("Failed to serialize launcher config")?;
        let tmp_path = self.path.with_extension("yaml.tmp");
        fs::write(&tmp_path, serialized)
            .with_context(|| format!("Failed to write {}", tmp_path.display()))?;
        fs::rename(&tmp_path, &self.path)
            .with_context(|| format!("Failed to replace {}", self.path.display()))?;
        Ok(())
    }

    pub fn file(&self) -> &RunnerConfigFile {
        &self.file
    }

    pub fn loaded_at(&self) -> SystemTime {
        self.loaded_at
    }

    pub fn default_profile(&self) -> &str {
        &self.file.default_profile
    }

    pub fn mcp(&self) -> McpConfig {
        self.file.mcp.clone()
    }

    pub fn profile_names(&self) -> Vec<String> {
        self.file.profiles.keys().cloned().collect()
    }

    pub fn profile(&self, name: &str) -> Option<RunnerProfile> {
        self.file.profiles.get(name).cloned()
    }

    pub fn update_profile(&mut self, name: &str, patch: ProfilePatch) -> Result<()> {
        let mut candidate = self.file.clone();
        let profile = candidate
            .profile_mut(name)
            .with_context(|| format!("Profile '{name}' not found"))?;
        profile.apply_patch(&patch);
        candidate.validate()?;
        self.file = candidate;
        self.loaded_at = SystemTime::now();
        self.save()
    }

    pub fn set_default_profile(&mut self, name: &str) -> Result<()> {
        if !self.file.profiles.contains_key(name) {
            bail!("Profile '{name}' not found");
        }
        self.file.default_profile = name.to_string();
        self.file.validate()?;
        self.loaded_at = SystemTime::now();
        self.save()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_default_yaml_profile_file() {
        let yaml = r#"
version: 1
default_profile: sample-dev
auto_start: true
profiles:
  sample-dev:
    program: ./gradlew
    args: [":keel-samples:run"]
    cwd: .
    env:
      KEEL_ENV: development
    stop_timeout_ms: 3000
"#;

        let file = RunnerConfigFile::from_yaml_str(yaml).expect("config parses");
        assert_eq!(file.default_profile, "sample-dev");
        assert_eq!(
            file.profiles.get("sample-dev").unwrap().program,
            "./gradlew"
        );
    }

    #[test]
    fn rejects_empty_program() {
        let mut file = RunnerConfigFile::default_for_repo();
        file.profiles.get_mut("sample-dev").unwrap().program = String::new();

        let err = file.validate().expect_err("empty program must fail");
        let rendered = format!("{err:#}");
        assert!(rendered.contains("program"));
    }

    #[test]
    fn default_config_includes_mcp_settings() {
        let file = RunnerConfigFile::default_for_repo();
        assert!(file.mcp.enabled);
        assert_eq!(file.mcp.host, "127.0.0.1");
        assert_eq!(file.mcp.port, 8765);
        assert_eq!(file.mcp.path, "/mcp");
    }

    #[test]
    fn rejects_mcp_path_without_leading_slash() {
        let yaml = r#"
version: 1
default_profile: sample-dev
auto_start: true
mcp:
  enabled: true
  host: 127.0.0.1
  port: 8765
  path: mcp
  auth:
    enabled: false
    bearer_token: null
profiles:
  sample-dev:
    program: ./gradlew
    args: [":keel-samples:run"]
    cwd: .
    env: {}
    stop_timeout_ms: 3000
"#;

        let err = RunnerConfigFile::from_yaml_str(yaml).expect_err("invalid path must fail");
        assert!(format!("{err:#}").contains("path"));
    }

    #[test]
    fn rejects_non_localhost_without_auth() {
        let yaml = r#"
version: 1
default_profile: sample-dev
auto_start: true
mcp:
  enabled: true
  host: 0.0.0.0
  port: 8765
  path: /mcp
  auth:
    enabled: false
    bearer_token: null
profiles:
  sample-dev:
    program: ./gradlew
    args: [":keel-samples:run"]
    cwd: .
    env: {}
    stop_timeout_ms: 3000
"#;

        let err = RunnerConfigFile::from_yaml_str(yaml).expect_err("non-localhost without auth must fail");
        assert!(format!("{err:#}").contains("auth"));
    }
}
