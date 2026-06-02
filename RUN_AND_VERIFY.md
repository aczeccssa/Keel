# Keel AI Gateway — Run & Verify

I could NOT run the server inside the agent sandbox: `ServerSocket.bind()` returns
"Operation not permitted" for every port (proven with a 6-line test), so no HTTP server —
gradle or java — can start here. Everything below is **compile-verified** (kotlinc, 1453
classes, zero errors). You run it to confirm behaviour end-to-end.

## 1. Build & run

```bash
cd /Users/a/Documents/GitHub/OpenSource/Keel
export ANTHROPIC_AUTH_TOKEN=<your-token>        # the test pool key

./gradlew run                                    # Netty (default)
# or, better for many SSE connections:
./gradlew run -Dkeel.engine=cio
```

The app serves on http://localhost:8080.

## 2. Verify /index no longer blocks

```bash
curl -i http://localhost:8080/index            # expect 302 -> /api/plugins/observability/ui/

# Open the observability UI in a browser (it now opens only ONE SSE stream, not 7):
#   http://localhost:8080/api/plugins/observability/ui/
# Then, while it's open, confirm the API is still responsive:
curl -s http://localhost:8080/api/plugins/airelay/v1/models   # must return promptly
```

If `/api/*` stays responsive with the dashboard open, the SSE-exhaustion fix worked.

## 3. Configure a provider in the UI (the new entry point)

Open the management console:
```
http://localhost:8080/api/plugins/airelay/ui/
```
Sign in (`admin@example.com` / `admin123`), go to the **Providers** tab → **Add Provider**:
- Name: `Local Anthropic`
- Protocol: `Anthropic Messages`
- Base URL: `http://127.0.0.1:15721`
- API Key Env: `ANTHROPIC_AUTH_TOKEN`   (or paste the key into API Key)
- Models: `claude-sonnet-4-20250514` (one per line)

Save, then click **Test** → expect a green "Connected in NNms".

## 4. Verify proxy through the configured provider (test pool)

Create an API key in the **API Keys** tab (copy the `sk-keel-...`), then:

```bash
KEY=sk-keel-...    # the virtual key from the UI

# (a) Direct Anthropic
curl -s http://localhost:8080/api/plugins/airelay/v1/messages \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"claude-sonnet-4-20250514","max_tokens":64,"messages":[{"role":"user","content":"Say pong"}]}'

# (b) OpenAI Responses input -> Anthropic upstream (transcoded), returned as Responses envelope
curl -i http://localhost:8080/api/plugins/airelay/v1/responses \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"claude-sonnet-4-20250514","input":"Say pong","store":false,"max_output_tokens":64}'
# Check response header: X-Upstream-Protocol: ANTHROPIC_MESSAGES
```

## 5. Run the tests

```bash
./gradlew test --tests "com.keel.test.kernel.ChannelConfigTest"        # config store (no network)
./gradlew test --tests "com.keel.test.kernel.RealUpstreamAnthropicTest" # live; skips if 127.0.0.1:15721 down
./gradlew test --tests "com.keel.test.kernel.IndexStaticBlockingTest"
```

## If something fails

Paste the error/stack and I'll fix it. The most likely runtime issues to watch for:
- A sample plugin configured as EXTERNAL_JVM will try to allocate ephemeral ports at startup
  (`PluginProcessSupervisor`/`NetworkUtils.allocateFreePort`). All AI-gateway plugins are
  IN_PROCESS; if startup fails on port allocation, tell me which plugin and I'll gate it.
- H2 file DB path: defaults to `${java.io.tmpdir}/keel-data`. Override with `-Dkeel.data.dir=...`.
