# AI Relay Benchmark Design

Date: 2026-06-16

## Purpose

This design defines a production-oriented benchmark for the `keel-samples` AI relay path. The goal is to measure how stable and fast the gateway is when serving heavy agent traffic through Anthropic Messages and OpenAI Responses compatible APIs.

The benchmark must answer these questions:

- What is the maximum stable concurrency for each routing topology?
- Where does `503` begin, and is it caused by pool exhaustion, timeout, transport failure, or another relay failure mode?
- How do `direct provider`, `alias -> specific provider`, and `alias -> any attached providers` compare under identical simulated provider generation duration?
- What are the P50, P90, P95, P99, throughput, error rate, and streaming characteristics for each protocol and topology?

## Scope

The benchmark includes the API-key relay path:

- API key authentication.
- Anthropic Messages and OpenAI Responses request decoding.
- Model alias and provider route selection.
- Upstream request encoding.
- Upstream HTTP or SSE send to simulated providers.
- Upstream response or SSE receive.
- Client protocol response encoding.
- HTTP response back to the benchmark runner.

The benchmark excludes:

- Customer portal UI and admin UI.
- Real external model providers.
- Provider network volatility outside the local benchmark environment.
- Prompt and context quality as a performance variable.
- Intentional provider-side error injection in the first benchmark phase.

Prompt and context are treated as relay payload. The primary provider-side variable is simulated generation duration.

## Architecture

The benchmark harness has three runtime pieces.

### Keel Samples Service

`keel-samples` runs as the system under test. Requests enter through real HTTP endpoints so the benchmark covers the same API-key relay path used by production clients.

The service must be configured with benchmark routing groups and providers for:

- Direct provider access.
- Alias pinned to one specific provider or channel.
- Alias routed to any attached providers.

### Simulated Provider Server

A local simulated provider server exposes multiple provider endpoints. Each endpoint behaves like one upstream model provider and supports both:

- Anthropic Messages style blocking and streaming responses.
- OpenAI Responses style blocking and streaming responses.

Every simulated provider in a test case is intentionally homogeneous:

- Same configured generation duration.
- Same protocol behavior.
- Same max concurrency.
- Same response payload shape, except for provider identity.

The simulated provider does not analyze prompt content. It only receives the gateway request, waits according to the configured duration model, and returns a protocol-valid response.

For blocking requests, total provider time should match the configured generation duration. For streaming requests, the provider should hold the SSE connection for the configured duration, emit protocol-valid chunks, and then send the protocol completion event.

### Benchmark Runner

The benchmark runner sends real HTTP requests to the Keel gateway. It is responsible for:

- Generating heavy agent-style request bodies for both protocols.
- Switching model names and API keys according to the topology under test.
- Running step-concurrency and near-limit RPS or soak phases.
- Recording per-request metrics and per-case summaries.
- Correlating client-side results with gateway and provider-side request identifiers.

The runner must inject an `X-Benchmark-Request-Id` header into every request. Keel and the simulated provider should propagate or log the identifier so a `503` can be traced end to end.

## Data Flow

```text
benchmark runner
  -> Keel gateway HTTP endpoint
  -> API key verification
  -> Anthropic/Responses request decode
  -> alias/direct provider route selection
  -> upstream protocol encode
  -> simulated provider HTTP/SSE endpoint
  -> simulated generation duration
  -> upstream protocol response/SSE
  -> gateway response decode
  -> client protocol response encode
  -> benchmark runner metrics
```

## Test Matrix

The benchmark matrix varies protocol, route topology, attached provider count, stream mode, and generation duration.

Protocols:

- Anthropic Messages.
- OpenAI Responses.

Request modes:

- Blocking.
- Streaming SSE.

Route topologies:

- `direct_provider`: request uses the model exposed by a single provider.
- `alias_specific_provider`: request uses an alias whose target is pinned to one provider or channel.
- `alias_any_attached_provider`: request uses an alias whose target can be served by any attached provider.

Attached provider counts for `alias_any_attached_provider`:

- 2 providers.
- 3 providers.
- 5 providers.

Provider generation duration levels:

- 1 second.
- 3 seconds.
- 5 seconds.
- 10 seconds.
- 20 seconds.
- 40 seconds.

Only one meaningful variable should change between comparison cases. For example, when comparing direct provider with alias-specific provider, the simulated provider duration, provider capacity, request body, protocol, and request mode must remain the same.

## Load Phases

Each case runs in two phases.

### Phase 1: Step Concurrency Search

The runner increases concurrency by step to find the maximum stable concurrency.

Suggested concurrency ladder:

- 10.
- 25.
- 50.
- 100.
- 200.
- 400.
- 800.
- 1200.
- 1600.
- 2400.

Each step includes a warmup period and a measured period. For generation duration levels up to 5 seconds, each measured step should run for at least 90 seconds. For 10 seconds or longer, each measured step should run for at least 180 seconds so the window contains multiple generation cycles.

When a step exceeds the stability threshold, the runner should run one more step when practical to confirm the failure inflection point, then stop the current case.

### Phase 2: Near-Limit Soak

The runner validates the boundary found in phase 1.

Soak run:

- Run at the maximum stable concurrency for 10 to 20 minutes.
- Confirm that `503` rate, total error rate, and P99 remain inside the production stability threshold.

Near-limit run:

- Run at 90%, 100%, and 110% of the maximum stable concurrency.
- Use shorter windows to confirm the capacity boundary and avoid treating a transient result as stable capacity.

## Stability Criteria

A case is stable only when all of these are true during the measured window:

- `503` rate is less than 0.1%.
- Total error rate is less than 0.5%.
- P99 latency does not show sustained upward drift.

For this benchmark, sustained P99 drift means either:

- P99 rises for three consecutive one-minute windows after warmup.
- P99 remains more than 25% above the previous stable step for three consecutive one-minute windows.

The benchmark should report both the last stable step and the first unstable step.

## Metrics

Each case must report these capacity metrics:

- Maximum stable concurrency.
- Maximum stable RPS.
- First unstable concurrency.
- First unstable RPS.

Each case must report these latency metrics:

- P50.
- P90.
- P95.
- P99.
- Max latency.

Streaming cases must also report:

- Time to first byte.
- Total stream duration.
- Chunk count.
- Chunk interval deviation.
- Client disconnect count.

Error metrics:

- HTTP status distribution.
- `503` rate.
- Total error rate.
- Timeout count.
- Connection reset count.
- Protocol decode and encode error count.
- Transport error count.

Routing metrics:

- Selected provider, channel, and key.
- Matched alias name.
- Candidate count.
- Failover count.

Pool metrics:

- Current concurrency by provider and key.
- Max concurrency by provider and key.
- Pool exhausted count.
- Cooldown count.
- Disabled count.
- Degraded count.

System metrics:

- JVM heap usage.
- GC pause summary.
- CPU utilization.
- Thread count.
- Ktor or Netty event loop saturation indicators when available.

## Reports

The harness should emit three report layers.

### Summary Report

`summary.csv` and `summary.json` contain one row or object per case. They are used for high-level comparison across protocols, topologies, durations, and provider counts.

### Time Series Report

`timeseries.csv` or `timeseries.jsonl` records per-second measurements during each case:

- Throughput.
- Error rate.
- `503` rate.
- P50, P90, P95, and P99.
- In-flight request count.
- Provider and key pool state.

This report is used to determine whether P99 is drifting upward.

### Request Report

`requests.jsonl` records one line per request:

- Benchmark request id.
- Protocol.
- Request mode.
- Route topology.
- Attached provider count.
- Generation duration level.
- Concurrency or RPS step.
- HTTP status.
- Latency.
- Time to first byte for streaming requests.
- Stream total duration for streaming requests.
- Selected provider, channel, and key when available.
- Error type.

The request report can be large, but it is the evidence trail for `503` diagnosis.

## 503 Attribution

Every `503` must be classified into one of these categories when possible:

- `pool_exhausted`: no available pool candidate exists because configured provider or key concurrency is occupied.
- `gateway_timeout`: the gateway timed out waiting for upstream, encoding, decoding, or stream relay completion.
- `provider_503`: the simulated provider returned 503. This is disabled in the first phase unless a later failure-mode benchmark explicitly enables it.
- `local_accounting_or_usage_error`: local usage or accounting work failed. This should be isolated or disabled for this relay benchmark path where possible.
- `transport_error`: connection reset, client timeout, or premature SSE termination.
- `unknown_503`: the harness could not attribute the failure; this should be treated as a benchmark instrumentation gap.

The final report should include both total `503` rate and attribution breakdown.

## Configuration

The benchmark should be driven by a declarative spec file. A representative shape:

```yaml
protocols:
  - anthropic_messages
  - openai_responses
requestModes:
  - blocking
  - streaming
generationDurations:
  - 1s
  - 3s
  - 5s
  - 10s
  - 20s
  - 40s
topologies:
  - direct_provider
  - alias_specific_provider
  - alias_any_attached_provider
attachedProviderCounts:
  - 2
  - 3
  - 5
stability:
  max503Rate: 0.001
  maxErrorRate: 0.005
  p99Drift: bounded
```

The implementation should allow narrowing the matrix from the command line so a developer can run a smoke subset before launching the full benchmark.

## Validation

Validation happens in three layers.

### Smoke Validation

Before running load, execute one blocking and one streaming request for each protocol and topology. Confirm that:

- Authentication succeeds.
- Route selection matches the expected topology.
- The simulated provider receives the request.
- The gateway returns a protocol-valid response.
- `X-Benchmark-Request-Id` is visible in runner, gateway, and provider traces.

### Calibration Validation

Run a single provider at low concurrency for each generation duration. Confirm that:

- Blocking latency is close to configured generation duration plus expected gateway overhead.
- Streaming duration is close to configured generation duration plus expected gateway overhead.
- TTFB is consistent with the configured streaming model.
- Provider identity is visible in the collected metrics.

### Full Benchmark Validation

Run the full matrix through step concurrency and near-limit soak. Confirm that:

- Direct provider, alias-specific provider, and alias-any-provider topologies each produce independent reports.
- Anthropic Messages and OpenAI Responses both produce blocking and streaming percentile metrics.
- Every `503` is attributed or explicitly marked as `unknown_503`.
- The summary identifies the last stable step and first unstable step for each case.

## Implementation Notes

The first implementation should prioritize measurement correctness over report aesthetics. A useful implementation path is:

- Add a simulated provider server with protocol-valid blocking and SSE responses.
- Add benchmark fixtures that configure Keel routing groups, aliases, providers, API keys, and provider capacities.
- Add a runner that can execute smoke, calibration, step concurrency, and near-limit soak phases.
- Add report writers for summary, time series, and per-request JSONL output.
- Add targeted automated tests for route selection, protocol response validity, duration calibration, and `503` attribution.

The implementation should avoid changing production relay semantics unless instrumentation needs a safe opt-in benchmark header or log field.

## Acceptance Criteria

The design is successfully implemented when:

- A developer can run the benchmark locally against `keel-samples` and simulated providers.
- The benchmark can run both Anthropic Messages and OpenAI Responses in blocking and streaming modes.
- The benchmark can compare `direct_provider`, `alias_specific_provider`, and `alias_any_attached_provider`.
- The `alias_any_attached_provider` topology can run with 2, 3, and 5 attached providers.
- The provider generation duration matrix includes 1s, 3s, 5s, 10s, 20s, and 40s.
- The output includes P50, P90, P95, P99, max latency, RPS, maximum stable concurrency, and first unstable concurrency.
- The output includes `503` attribution and enough request correlation data to trace failures through runner, gateway, and simulated provider.
