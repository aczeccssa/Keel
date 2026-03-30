package com.keel.kernel.isolation

import com.keel.kernel.plugin.JvmCommunicationMode
import java.io.File
import java.nio.file.Path

internal class ExternalPluginHostArgsParser {
    fun parse(args: Array<String>): ExternalPluginHostArgs {
        val parsedArgs = args.associate {
            val parts = it.removePrefix("--").split("=", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        val commMode = parsedArgs["comm-mode"]?.let { JvmCommunicationMode.valueOf(it.uppercase()) }
            ?: JvmCommunicationMode.UDS

        return ExternalPluginHostArgs(
            pluginClass = requireNotNull(parsedArgs["plugin-class"]) { "Missing --plugin-class" },
            commMode = commMode,
            invokeSocketPath = parsedArgs["invoke-socket-path"]?.let { Path.of(it) },
            adminSocketPath = parsedArgs["admin-socket-path"]?.let { Path.of(it) },
            eventSocketPath = parsedArgs["event-socket-path"]?.let { Path.of(it) },
            invokePort = parsedArgs["invoke-port"]?.toIntOrNull(),
            adminPort = parsedArgs["admin-port"]?.toIntOrNull(),
            eventPort = parsedArgs["event-port"]?.toIntOrNull(),
            authToken = requireNotNull(parsedArgs["auth-token"]) { "Missing --auth-token" },
            generation = parsedArgs["generation"]?.toLongOrNull() ?: 1L,
            configPath = parsedArgs["config-path"]?.takeIf { it.isNotBlank() }?.let(::File)
        )
    }
}
