# Keel

Keel is a Ktor-based modular monolith kernel with hot-swappable plugins. It lets you ship independent business modules inside a single JVM process, enable/disable them at runtime, and expose typed, documented APIs under a unified gateway.

## Highlights

- Plugin lifecycle management with runtime enable/disable
- Plugin gateway with `/api/plugins/{pluginId}` routing and status enforcement
- Two execution modes: in-process or isolated JVM
- OpenAPI 3.1 docs with Swagger UI at `/api/_system/docs/`
- Koin-based DI integration per plugin
- Exposed-based database starter with plugin-scoped tables and audit/soft-delete helpers
- Config + plugin directory hot-reload support in development mode

## Requirements

- JDK 23
- Gradle (wrapper provided)

## Tech Stack

- Kotlin 2.3.0 (K2 compiler)
- Ktor 3.4.0 (Netty)
- Koin 4.0.0
- Exposed 0.61.0
- kotlinx.serialization 1.8.0
- kotlinx.datetime 0.6.2
- Dokka 2.1.0

## Quick Start

Run the sample application (includes `helloworld` and `dbdemo` plugins):

```bash
./gradlew :keel-samples:run
```

Key endpoints (default port is `8080`):

- `http://localhost:8080/api/plugins/helloworld`
- `http://localhost:8080/api/plugins/helloworld/version`
- `http://localhost:8080/api/plugins/dbdemo/notes`
- `http://localhost:8080/api/_system/plugins`
- `http://localhost:8080/api/_system/docs/`
- `http://localhost:8080/api/_system/docs/openapi.json`

## Project Structure

```
keel-core                Core kernel (routing, plugin manager, gateway, hot reload)
keel-contract            Shared DTOs/contracts
keel-exposed-starter     Database utilities (Exposed, PluginTable, audit helpers)
keel-openapi-annotations OpenAPI annotations
keel-openapi-processor   KSP processor for OpenAPI
keel-openapi-runtime     Runtime OpenAPI routes + Swagger UI
keel-ipc-runtime         IPC runtime for isolated plugins
keel-test-suite          Testing helpers
keel-samples             Sample application
plugins/sample-hello     Example plugin (Hello World)
plugins/sample-dbdemo    Example plugin (CRUD + H2 in-memory)
```

## Plugin Development (KeelPlugin)

Create a plugin by implementing `KeelPlugin` and declaring endpoints:

```kotlin
class MyPlugin : KeelPlugin {
    override val descriptor = PluginDescriptor(
        pluginId = "myplugin",
        version = "1.0.0",
        displayName = "My Plugin"
    )

    override suspend fun onInit(context: PluginInitContext) {
        // init resources
    }

    override suspend fun onStart(context: PluginRuntimeContext) {
        // start background work if needed
    }

    override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
        get<String>("/ping") {
            PluginResult(body = "pong")
        }
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        // stop background work
    }

    override suspend fun onDispose(context: PluginRuntimeContext) {
        // cleanup
    }
}
```

Register plugins when building the kernel:

```kotlin
runKernel(port = 8080) {
    plugin(MyPlugin())
}
```

## Configuration

Environment mode:
`-Dkeel.env=development` or `-Dkeel.env=production`, or `KEEL_ENV=development` / `KEEL_ENV=production`.

Server port:
`-Dkeel.port=8080` or `KEEL_PORT=8080`.

Hot reload:
Enabled by default in development mode; toggle via `runKernel { enablePluginHotReload(true|false) }`.

Default directories:
Config directory is `config/`. Plugin jars directory is `plugins/`.

## System APIs

System management endpoints are mounted at `/api/_system`:

- `GET /api/_system/plugins` list plugins
- `GET /api/_system/plugins/{pluginId}` plugin details
- `GET /api/_system/plugins/{pluginId}/health` plugin runtime health
- `POST /api/_system/plugins/{pluginId}/start` start a plugin
- `POST /api/_system/plugins/{pluginId}/stop` stop a plugin
- `POST /api/_system/plugins/{pluginId}/dispose` dispose a plugin
- `POST /api/_system/plugins/{pluginId}/reload` reload a plugin generation
- `POST /api/_system/plugins/{pluginId}/replace` replace a plugin artifact
- `POST /api/_system/plugins/discover` discover plugins under `plugins/`
- `GET /api/_system/docs` Swagger UI
- `GET /api/_system/docs/openapi.json` OpenAPI spec

## Build and Test

```bash
./gradlew build
./gradlew test
./gradlew dokka
```

## Notes

- OpenAPI metadata is collected via `@KeelApi` and `@KeelApiPlugin` annotations.

## Contributing

Issues and pull requests are welcome. Please keep plugin boundaries strict and avoid cross-plugin dependencies.

## License

No license file is present yet. Add one if you plan to publish or distribute the project.
