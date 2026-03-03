# Keel Framework Samples

This module contains sample applications demonstrating how to use the Keel framework.

## HelloWorld Sample

A simple demonstration of creating a plugin and running a server.

### Running the Sample

```bash
./gradlew :keel-samples:run
```

Or run the main class directly:

```bash
./gradlew :keel-samples:run -PmainClass=com.keel.samples.helloworld.HelloWorldAppKt
```

### Accessing Endpoints

Once running, you can access:

- `http://localhost:8080/api/plugins/helloworld` - HelloWorld plugin
- `http://localhost:8080/api/plugins/helloworld/version` - Version info
- `http://localhost:8080/api/plugins/admin/status` - Admin plugin

## Creating Your Own Plugin

1. Create a new module under `plugins/`
2. Implement the `KPlugin` interface:

```kotlin
class MyPlugin : KPlugin {
    override val pluginId: String = "myplugin"
    override val version: String = "1.0.0"

    override suspend fun onInit(ctx: PluginInitContext) {
        // Initialize plugin
    }

    override suspend fun onInstall(scope: Scope) {
        // Install dependencies
    }

    override suspend fun onEnable(routing: Routing) {
        // Register routes at /api/plugins/myplugin/
    }

    override suspend fun onDisable() {
        // Cleanup
    }

    override fun getState(): PluginState = PluginState.ENABLED
}
```

3. Register the plugin with the Kernel:

```kotlin
val kernel = KernelBuilder()
    .plugin(MyPlugin())
    .build()
```

## Architecture Overview

```
Keel Framework
├── keel-core          # Framework engine
├── keel-contract     # Common interfaces
├── keel-exposed-starter  # Database utilities
├── plugins           # Business plugins
│   ├── keel-plugin-admin
│   ├── keel-plugin-auth
│   ├── keel-plugin-eventbus
│   └── keel-plugin-user
└── keel-samples      # Example applications
```
