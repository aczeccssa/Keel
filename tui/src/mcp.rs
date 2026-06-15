//! HTTP MCP adapter over the shared control core.

use std::borrow::Cow;
use std::future::Future;
use std::sync::mpsc;
use std::thread;
use std::time::Duration;

use anyhow::{Context, Result, bail};
use axum::extract::{Request, State};
use axum::http::{HeaderValue, StatusCode, header};
use axum::middleware::{self, Next};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::Router;
use rmcp::handler::server::router::tool::{AsyncTool, ToolBase, ToolRouter};
use rmcp::handler::server::tool::ToolCallContext;
use rmcp::model::{
    CallToolRequestParams, ListToolsResult, PaginatedRequestParams, ServerCapabilities,
    ServerInfo, Tool,
};
use rmcp::service::RequestContext;
use rmcp::transport::streamable_http_server::{
    StreamableHttpServerConfig, StreamableHttpService, session::local::LocalSessionManager,
};
use rmcp::{ErrorData, RoleServer, ServerHandler};
use schemars::JsonSchema;
use serde::Deserialize;
use serde_json::Value;
use tokio_util::sync::CancellationToken;

use crate::config::{McpConfig, ProfilePatch};
use crate::control::{CommandSource, CommandResult, ControlHandle, WriteCommand};
use crate::runtime::{LogEntry, McpServerState, RuntimeSnapshot};

const STARTUP_TIMEOUT: Duration = Duration::from_secs(5);
const WRITE_TIMEOUT: Duration = Duration::from_secs(30);

pub struct HttpMcpServerHandle {
    #[cfg_attr(not(test), allow(dead_code))]
    base_url: String,
    shutdown: Option<CancellationToken>,
    join: Option<thread::JoinHandle<()>>,
}

impl HttpMcpServerHandle {
    #[cfg(test)]
    pub fn base_url(&self) -> &str {
        &self.base_url
    }

    fn disabled() -> Self {
        Self {
            base_url: String::new(),
            shutdown: None,
            join: None,
        }
    }
}

impl Drop for HttpMcpServerHandle {
    fn drop(&mut self) {
        if let Some(token) = self.shutdown.take() {
            token.cancel();
        }
        if let Some(join) = self.join.take() {
            let _ = join.join();
        }
    }
}

pub fn start_http_mcp_server(handle: ControlHandle) -> Result<HttpMcpServerHandle> {
    let config = handle.mcp_config();
    if !config.enabled {
        handle.set_mcp_server_state(McpServerState::Disabled);
        return Ok(HttpMcpServerHandle::disabled());
    }

    handle.set_mcp_server_state(McpServerState::Starting);

    let shutdown = CancellationToken::new();
    let server_shutdown = shutdown.clone();
    let server_handle = handle.clone();
    let server_config = config.clone();
    let (startup_tx, startup_rx) = mpsc::sync_channel::<Result<String, String>>(1);

    let join = thread::spawn(move || {
        if let Err(err) =
            run_http_mcp_server(server_handle, server_config, server_shutdown, startup_tx)
        {
            eprintln!("MCP HTTP server stopped: {err:#}");
        }
    });

    match startup_rx.recv_timeout(STARTUP_TIMEOUT) {
        Ok(Ok(base_url)) => {
            handle.set_mcp_server_state(McpServerState::Running);
            Ok(HttpMcpServerHandle {
                base_url,
                shutdown: Some(shutdown),
                join: Some(join),
            })
        }
        Ok(Err(err)) => {
            shutdown.cancel();
            let _ = join.join();
            bail!(err);
        }
        Err(_) => {
            shutdown.cancel();
            let _ = join.join();
            bail!("Timed out waiting for MCP HTTP server startup");
        }
    }
}

fn run_http_mcp_server(
    handle: ControlHandle,
    config: McpConfig,
    shutdown: CancellationToken,
    startup_tx: mpsc::SyncSender<Result<String, String>>,
) -> Result<()> {
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .context("Failed to build Tokio runtime for MCP server")?;

    runtime.block_on(async move {
        let service: StreamableHttpService<RunnerMcpServer, LocalSessionManager> =
            StreamableHttpService::new(
                {
                    let handle = handle.clone();
                    move || Ok(RunnerMcpServer::new(handle.clone()))
                },
                Default::default(),
                StreamableHttpServerConfig::default()
                    .with_sse_keep_alive(None)
                    .with_cancellation_token(shutdown.child_token()),
            );

        let listener = match tokio::net::TcpListener::bind((config.host.as_str(), config.port))
            .await
            .with_context(|| {
                format!(
                    "Failed to bind MCP HTTP server to {}:{}",
                    config.host, config.port
                )
            }) {
            Ok(listener) => listener,
            Err(err) => {
                let _ = startup_tx.send(Err(format!("{err:#}")));
                return Err(err);
            }
        };

        let router = build_http_router(config.clone(), service);
        let base_url = config.base_url();
        let _ = startup_tx.send(Ok(base_url));

        axum::serve(listener, router)
            .with_graceful_shutdown(async move {
                shutdown.cancelled_owned().await;
            })
            .await
            .context("HTTP server exited with error")
    })
}

fn build_http_router(
    config: McpConfig,
    service: StreamableHttpService<RunnerMcpServer, LocalSessionManager>,
) -> Router {
    let mcp_router = Router::new().nest_service(&config.path, service);
    let mcp_router = if config.auth.enabled {
        let auth_state = AuthState {
            bearer_token: config
                .auth
                .bearer_token
                .expect("validated bearer token"),
        };
        mcp_router.layer(middleware::from_fn_with_state(auth_state, require_bearer_auth))
    } else {
        mcp_router
    };

    Router::new()
        .route("/healthz", get(healthz))
        .merge(mcp_router)
}

async fn healthz() -> StatusCode {
    StatusCode::OK
}

#[derive(Clone)]
struct AuthState {
    bearer_token: String,
}

async fn require_bearer_auth(
    State(state): State<AuthState>,
    request: Request,
    next: Next,
) -> Response {
    let expected = format!("Bearer {}", state.bearer_token);
    let authorized = request
        .headers()
        .get(header::AUTHORIZATION)
        .and_then(|value| value.to_str().ok())
        .map(|value| value == expected)
        .unwrap_or(false);

    if !authorized {
        return (
            StatusCode::UNAUTHORIZED,
            [(header::WWW_AUTHENTICATE, HeaderValue::from_static("Bearer"))],
        )
            .into_response();
    }

    next.run(request).await
}

#[derive(Clone)]
struct RunnerMcpServer {
    control: ControlHandle,
    tool_router: ToolRouter<Self>,
}

impl RunnerMcpServer {
    fn new(control: ControlHandle) -> Self {
        Self {
            control,
            tool_router: Self::tool_router(),
        }
    }

    fn tool_router() -> ToolRouter<Self> {
        ToolRouter::new()
            .with_async_tool::<RunnerGetLogsTool>()
            .with_async_tool::<RunnerGetProfileTool>()
            .with_async_tool::<RunnerListProfilesTool>()
            .with_async_tool::<RunnerRestartTool>()
            .with_async_tool::<RunnerSetDefaultProfileTool>()
            .with_async_tool::<RunnerShutdownTool>()
            .with_async_tool::<RunnerStartTool>()
            .with_async_tool::<RunnerStatusTool>()
            .with_async_tool::<RunnerStopTool>()
            .with_async_tool::<RunnerSwitchProfileTool>()
            .with_async_tool::<RunnerUpdateProfileTool>()
    }
}

impl ServerHandler for RunnerMcpServer {
    fn get_info(&self) -> ServerInfo {
        ServerInfo::new(ServerCapabilities::builder().enable_tools().build())
            .with_instructions("Controls the keel sample runner and exposes launcher state.")
    }

    fn call_tool(
        &self,
        request: CallToolRequestParams,
        context: RequestContext<RoleServer>,
    ) -> impl Future<Output = Result<rmcp::model::CallToolResult, ErrorData>> + Send + '_ {
        self.tool_router.call(ToolCallContext::new(self, request, context))
    }

    fn list_tools(
        &self,
        _request: Option<PaginatedRequestParams>,
        _context: RequestContext<RoleServer>,
    ) -> impl Future<Output = Result<ListToolsResult, ErrorData>> + Send + '_ {
        std::future::ready(Ok(ListToolsResult {
            tools: self.tool_router.list_all(),
            next_cursor: None,
            meta: None,
        }))
    }

    fn get_tool(&self, name: &str) -> Option<Tool> {
        self.tool_router.get(name).cloned()
    }
}

#[derive(Debug, Default, Deserialize, JsonSchema)]
struct ProfileNameInput {
    profile: String,
}

#[derive(Debug, Default, Deserialize, JsonSchema)]
struct UpdateProfileInput {
    profile: String,
    patch: ProfilePatch,
}

struct RunnerStatusTool;
struct RunnerGetLogsTool;
struct RunnerListProfilesTool;
struct RunnerGetProfileTool;
struct RunnerSwitchProfileTool;
struct RunnerUpdateProfileTool;
struct RunnerSetDefaultProfileTool;
struct RunnerStartTool;
struct RunnerStopTool;
struct RunnerRestartTool;
struct RunnerShutdownTool;

impl ToolBase for RunnerStatusTool {
    type Parameter = ();
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_status".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Return the current launcher runtime snapshot.".into())
    }

    fn input_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerStatusTool {
    async fn invoke(service: &RunnerMcpServer, _param: ()) -> Result<Value, ErrorData> {
        snapshot_value(service.control.snapshot())
    }
}

impl ToolBase for RunnerGetLogsTool {
    type Parameter = ();
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_get_logs".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Return the recent launcher log buffer.".into())
    }

    fn input_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerGetLogsTool {
    async fn invoke(service: &RunnerMcpServer, _param: ()) -> Result<Value, ErrorData> {
        logs_value(&service.control.snapshot().logs)
    }
}

impl ToolBase for RunnerListProfilesTool {
    type Parameter = ();
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_list_profiles".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("List available launcher profiles.".into())
    }

    fn input_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerListProfilesTool {
    async fn invoke(service: &RunnerMcpServer, _param: ()) -> Result<Value, ErrorData> {
        json_value(service.control.list_profiles())
    }
}

impl ToolBase for RunnerGetProfileTool {
    type Parameter = ProfileNameInput;
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_get_profile".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Return one launcher profile by name.".into())
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerGetProfileTool {
    async fn invoke(
        service: &RunnerMcpServer,
        ProfileNameInput { profile }: ProfileNameInput,
    ) -> Result<Value, ErrorData> {
        let profile = service
            .control
            .get_profile(&profile)
            .map_err(|err| ErrorData::invalid_params(err.to_string(), None))?;
        json_value(profile)
    }
}

impl ToolBase for RunnerSwitchProfileTool {
    type Parameter = ProfileNameInput;
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_switch_profile".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Switch the active launcher profile without restarting.".into())
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerSwitchProfileTool {
    async fn invoke(
        service: &RunnerMcpServer,
        ProfileNameInput { profile }: ProfileNameInput,
    ) -> Result<Value, ErrorData> {
        command_value(service.control.execute_write(
            CommandSource::Mcp,
            WriteCommand::SwitchProfile { profile },
            WRITE_TIMEOUT,
        ))
    }
}

impl ToolBase for RunnerUpdateProfileTool {
    type Parameter = UpdateProfileInput;
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_update_profile".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Persist a launcher profile patch.".into())
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerUpdateProfileTool {
    async fn invoke(
        service: &RunnerMcpServer,
        UpdateProfileInput { profile, patch }: UpdateProfileInput,
    ) -> Result<Value, ErrorData> {
        command_value(service.control.execute_write(
            CommandSource::Mcp,
            WriteCommand::UpdateProfile { profile, patch },
            WRITE_TIMEOUT,
        ))
    }
}

impl ToolBase for RunnerSetDefaultProfileTool {
    type Parameter = ProfileNameInput;
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_set_default_profile".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Persist the default launcher profile.".into())
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerSetDefaultProfileTool {
    async fn invoke(
        service: &RunnerMcpServer,
        ProfileNameInput { profile }: ProfileNameInput,
    ) -> Result<Value, ErrorData> {
        command_value(service.control.execute_write(
            CommandSource::Mcp,
            WriteCommand::SetDefaultProfile { profile },
            WRITE_TIMEOUT,
        ))
    }
}

impl ToolBase for RunnerStartTool {
    type Parameter = ();
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_start".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Start the configured launcher process.".into())
    }

    fn input_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerStartTool {
    async fn invoke(service: &RunnerMcpServer, _param: ()) -> Result<Value, ErrorData> {
        command_value(service.control.execute_write(
            CommandSource::Mcp,
            WriteCommand::Start,
            WRITE_TIMEOUT,
        ))
    }
}

impl ToolBase for RunnerStopTool {
    type Parameter = ();
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_stop".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Stop the running launcher process.".into())
    }

    fn input_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerStopTool {
    async fn invoke(service: &RunnerMcpServer, _param: ()) -> Result<Value, ErrorData> {
        command_value(service.control.execute_write(
            CommandSource::Mcp,
            WriteCommand::Stop,
            WRITE_TIMEOUT,
        ))
    }
}

impl ToolBase for RunnerRestartTool {
    type Parameter = ();
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_restart".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Restart the running launcher process with the active profile.".into())
    }

    fn input_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerRestartTool {
    async fn invoke(service: &RunnerMcpServer, _param: ()) -> Result<Value, ErrorData> {
        command_value(service.control.execute_write(
            CommandSource::Mcp,
            WriteCommand::Restart,
            WRITE_TIMEOUT,
        ))
    }
}

impl ToolBase for RunnerShutdownTool {
    type Parameter = ();
    type Output = Value;
    type Error = ErrorData;

    fn name() -> Cow<'static, str> {
        "runner_shutdown".into()
    }

    fn description() -> Option<Cow<'static, str>> {
        Some("Request launcher shutdown.".into())
    }

    fn input_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }

    fn output_schema() -> Option<std::sync::Arc<rmcp::model::JsonObject>> {
        None
    }
}

impl AsyncTool<RunnerMcpServer> for RunnerShutdownTool {
    async fn invoke(service: &RunnerMcpServer, _param: ()) -> Result<Value, ErrorData> {
        command_value(service.control.execute_write(
            CommandSource::Mcp,
            WriteCommand::ShutdownLauncher,
            WRITE_TIMEOUT,
        ))
    }
}

fn snapshot_value(snapshot: RuntimeSnapshot) -> Result<Value, ErrorData> {
    json_value(snapshot)
}

fn logs_value(logs: &std::collections::VecDeque<LogEntry>) -> Result<Value, ErrorData> {
    json_value(logs)
}

fn command_value(result: Result<CommandResult>) -> Result<Value, ErrorData> {
    json_value(result.map_err(|err| ErrorData::internal_error(err.to_string(), None))?)
}

fn json_value<T: serde::Serialize>(value: T) -> Result<Value, ErrorData> {
    serde_json::to_value(value)
        .map_err(|err| ErrorData::internal_error(format!("Failed to serialize tool output: {err}"), None))
}

#[cfg(test)]
mod tests {
    use std::fs;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
    use std::time::{SystemTime, UNIX_EPOCH};

    use rmcp::ServiceExt;
    use rmcp::model::CallToolRequestParams;
    use rmcp::model::ClientInfo;
    use rmcp::transport::StreamableHttpClientTransport;
    use rmcp::transport::streamable_http_client::StreamableHttpClientTransportConfig;

    use crate::config::{POLL_INTERVAL, RunnerConfigFile};
    use crate::control::ControlCore;

    use super::*;

    static NEXT_TEST_ID: AtomicU64 = AtomicU64::new(1);

    fn temp_repo_root(port: u16, auth_token: Option<&str>) -> std::path::PathBuf {
        let timestamp = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("time")
            .as_nanos();
        let unique = NEXT_TEST_ID.fetch_add(1, Ordering::Relaxed);
        let root =
            std::env::temp_dir().join(format!("keel-launcher-mcp-test-{timestamp}-{unique}"));
        fs::create_dir_all(root.join("tui")).expect("create tui dir");

        let script = "#!/bin/sh\ntrap 'exit 0' TERM INT\nwhile true; do sleep 1; done\n";
        let gradlew = root.join("gradlew");
        fs::write(&gradlew, script).expect("write gradlew");
        let mut permissions = fs::metadata(&gradlew).expect("metadata").permissions();
        permissions.set_mode(0o755);
        fs::set_permissions(&gradlew, permissions).expect("chmod");

        let mut config = RunnerConfigFile::default_for_repo();
        config.auto_start = false;
        config.mcp.port = port;
        config.mcp.auth.enabled = auth_token.is_some();
        config.mcp.auth.bearer_token = auth_token.map(str::to_string);
        if let Some(profile) = config.profiles.get_mut("sample-dev") {
            profile.args.clear();
            profile.env.clear();
        }
        let rendered = serde_yaml::to_string(&config).expect("serialize config");
        fs::write(root.join("tui").join("keel-runner.yaml"), rendered).expect("write config");
        root
    }

    fn reserve_port() -> u16 {
        std::net::TcpListener::bind("127.0.0.1:0")
            .expect("bind ephemeral")
            .local_addr()
            .expect("local addr")
            .port()
    }

    struct TickGuard {
        stop: Arc<AtomicBool>,
        join: Option<thread::JoinHandle<()>>,
    }

    impl TickGuard {
        fn start(handle: ControlHandle) -> Self {
            let stop = Arc::new(AtomicBool::new(false));
            let stop_flag = stop.clone();
            let join = thread::spawn(move || {
                while !stop_flag.load(Ordering::Relaxed) {
                    let _ = handle.tick();
                    thread::sleep(POLL_INTERVAL);
                }
            });
            Self {
                stop,
                join: Some(join),
            }
        }
    }

    impl Drop for TickGuard {
        fn drop(&mut self) {
            self.stop.store(true, Ordering::Relaxed);
            if let Some(join) = self.join.take() {
                let _ = join.join();
            }
        }
    }

    fn client_transport(
        base_url: &str,
        token: Option<&str>,
    ) -> StreamableHttpClientTransport<reqwest::Client> {
        let mut config = StreamableHttpClientTransportConfig::with_uri(format!("{base_url}/mcp"));
        if let Some(token) = token {
            config = config.auth_header(token.to_string());
        }
        StreamableHttpClientTransport::from_config(config)
    }

    fn tool_args(input: Value) -> serde_json::Map<String, Value> {
        serde_json::from_value(input).expect("tool args")
    }

    #[tokio::test]
    async fn healthz_endpoint_returns_ok() {
        let repo_root = temp_repo_root(reserve_port(), None);
        let core = ControlCore::new(repo_root).expect("core");
        let server = start_http_mcp_server(core.handle()).expect("http mcp server");
        let response = reqwest::get(format!("{}/healthz", server.base_url()))
            .await
            .expect("healthz response");
        assert_eq!(response.status(), reqwest::StatusCode::OK);
    }

    #[tokio::test]
    async fn runner_status_returns_snapshot_json() {
        let repo_root = temp_repo_root(reserve_port(), None);
        let core = ControlCore::new(repo_root).expect("core");
        let server = start_http_mcp_server(core.handle()).expect("http mcp server");
        let client = ClientInfo::default()
            .serve(client_transport(server.base_url(), None))
            .await
            .expect("client");

        let result = client
            .call_tool(CallToolRequestParams::new("runner_status"))
            .await
            .expect("status tool");
        let structured = result.structured_content.expect("structured content");

        assert_eq!(structured["active_profile"], "sample-dev");
        assert_eq!(structured["mcp"]["path"], "/mcp");
    }

    #[tokio::test]
    async fn runner_update_profile_persists_changes() {
        let repo_root = temp_repo_root(reserve_port(), None);
        let core = ControlCore::new(repo_root.clone()).expect("core");
        let _ticker = TickGuard::start(core.handle());
        let server = start_http_mcp_server(core.handle()).expect("http mcp server");
        let client = ClientInfo::default()
            .serve(client_transport(server.base_url(), None))
            .await
            .expect("client");

        let result = client
            .call_tool(
                CallToolRequestParams::new("runner_update_profile").with_arguments(tool_args(
                    serde_json::json!({
                        "profile": "sample-dev",
                        "patch": {
                            "env": {
                                "KEEL_ENV": "qa"
                            }
                        }
                    }),
                )),
            )
            .await
            .expect("update profile tool");

        assert_eq!(result.structured_content.expect("structured")["ok"], true);
        let config = fs::read_to_string(repo_root.join("tui").join("keel-runner.yaml"))
            .expect("read config");
        assert!(config.contains("qa"));
    }

    #[tokio::test]
    async fn runner_restart_uses_control_core() {
        let repo_root = temp_repo_root(reserve_port(), None);
        let core = ControlCore::new(repo_root).expect("core");
        let _ticker = TickGuard::start(core.handle());
        let server = start_http_mcp_server(core.handle()).expect("http mcp server");
        let client = ClientInfo::default()
            .serve(client_transport(server.base_url(), None))
            .await
            .expect("client");

        let start = client
            .call_tool(CallToolRequestParams::new("runner_start"))
            .await
            .expect("start tool");
        assert_eq!(start.structured_content.expect("structured")["ok"], true);
        let pid_before = core.handle().snapshot().pid.expect("pid after start");

        let restart = client
            .call_tool(CallToolRequestParams::new("runner_restart"))
            .await
            .expect("restart tool");
        assert_eq!(restart.structured_content.expect("structured")["ok"], true);
        let pid_after = core.handle().snapshot().pid.expect("pid after restart");

        assert_ne!(pid_before, pid_after);

        let _ = core.handle().execute_write(
            CommandSource::Tui,
            WriteCommand::Stop,
            WRITE_TIMEOUT,
        );
    }

    #[tokio::test]
    async fn auth_enabled_rejects_missing_token() {
        let repo_root = temp_repo_root(reserve_port(), Some("secret-token"));
        let core = ControlCore::new(repo_root).expect("core");
        let server = start_http_mcp_server(core.handle()).expect("http mcp server");
        let response = reqwest::Client::new()
            .post(format!("{}/mcp", server.base_url()))
            .send()
            .await
            .expect("unauthorized response");
        assert_eq!(response.status(), reqwest::StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn auth_enabled_rejects_wrong_token() {
        let repo_root = temp_repo_root(reserve_port(), Some("secret-token"));
        let core = ControlCore::new(repo_root).expect("core");
        let server = start_http_mcp_server(core.handle()).expect("http mcp server");
        let response = reqwest::Client::new()
            .post(format!("{}/mcp", server.base_url()))
            .header(reqwest::header::AUTHORIZATION, "Bearer wrong-token")
            .send()
            .await
            .expect("unauthorized response");
        assert_eq!(response.status(), reqwest::StatusCode::UNAUTHORIZED);
    }

    #[tokio::test]
    async fn auth_enabled_accepts_correct_token() {
        let repo_root = temp_repo_root(reserve_port(), Some("secret-token"));
        let core = ControlCore::new(repo_root).expect("core");
        let server = start_http_mcp_server(core.handle()).expect("http mcp server");
        let client = ClientInfo::default()
            .serve(client_transport(server.base_url(), Some("secret-token")))
            .await
            .expect("client");

        let result = client
            .call_tool(CallToolRequestParams::new("runner_status"))
            .await
            .expect("status tool");
        assert_eq!(result.structured_content.expect("structured")["active_profile"], "sample-dev");
    }
}
