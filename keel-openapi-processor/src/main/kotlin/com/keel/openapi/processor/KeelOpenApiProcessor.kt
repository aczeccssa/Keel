package com.keel.openapi.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.validate
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * KSP processor that:
 * 1. reads @KeelApiPlugin annotations on KPlugin classes and generates OpenApiFragment providers
 * 2. scans source files for @KeelApi route annotations and generates OpenApiOperationFragment providers
 */
class KeelOpenApiProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val pluginAnnotationName = "com.keel.openapi.annotations.KeelApiPlugin"
        val pluginSymbols = resolver.getSymbolsWithAnnotation(pluginAnnotationName)

        val unprocessed = mutableListOf<KSAnnotated>()
        val fragmentProviders = mutableListOf<String>()
        val operationProviders = mutableListOf<String>()

        pluginSymbols.forEach { symbol ->
            if (!symbol.validate()) {
                unprocessed.add(symbol)
                return@forEach
            }

            val classDecl = symbol as? KSClassDeclaration ?: return@forEach
            val annotation = classDecl.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == pluginAnnotationName
            }

            val pluginId = annotation.arguments.first { it.name?.asString() == "pluginId" }.value as String
            val title = annotation.arguments.first { it.name?.asString() == "title" }.value as String
            val description = annotation.arguments.firstOrNull { it.name?.asString() == "description" }?.value as? String ?: ""
            val version = annotation.arguments.firstOrNull { it.name?.asString() == "version" }?.value as? String ?: "1.0.0"

            val typeName = pluginId.replaceFirstChar { it.uppercase() } + "OpenApiFragment"
            val packageName = "com.keel.openapi.generated"
            val fqn = "$packageName.$typeName"

            logger.info("Generating OpenApiFragment: $fqn for plugin '$pluginId'")

            val file = codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = typeName
            )

            file.write(
                """
                |package $packageName
                |
                |import com.keel.openapi.runtime.OpenApiFragment
                |
                |class $typeName : OpenApiFragment {
                |    override val pluginId: String = "${escape(pluginId)}"
                |    override val basePath: String = "/api/plugins/${escape(pluginId)}"
                |    override val title: String = "${escape(title)}"
                |    override val description: String = "${escape(description)}"
                |    override val version: String = "${escape(version)}"
                |}
                """.trimMargin().toByteArray()
            )
            file.close()

            fragmentProviders += fqn
        }

        resolver.getAllFiles().forEach { file ->
            val operations = parseAnnotatedOperations(file)
            if (operations.isEmpty()) {
                return@forEach
            }

            val packageName = "com.keel.openapi.generated"
            val typeName = buildAnnotatedTypeName(file)
            val fqn = "$packageName.$typeName"
            logger.info("Generating OpenApiOperationFragment: $fqn with ${operations.size} operations")

            val generatedFile = codeGenerator.createNewFile(
                dependencies = Dependencies(aggregating = false, file),
                packageName = packageName,
                fileName = typeName
            )
            generatedFile.write(
                buildString {
                    appendLine("package $packageName")
                    appendLine()
                    appendLine("import com.keel.openapi.runtime.OpenApiDeclaredOperation")
                    appendLine("import com.keel.openapi.runtime.OpenApiOperationFragment")
                    appendLine("import io.ktor.http.HttpMethod")
                    appendLine()
                    appendLine("@Suppress(\"unused\")")
                    appendLine("class $typeName : OpenApiOperationFragment {")
                    appendLine("    override fun operations(): List<OpenApiDeclaredOperation> = listOf(")
                    operations.forEachIndexed { index, operation ->
                        append("        OpenApiDeclaredOperation(")
                        append("method = HttpMethod.${operation.method}, ")
                        append("path = \"${escape(operation.path)}\", ")
                        append("summary = \"${escape(operation.summary)}\", ")
                        append("description = \"${escape(operation.description)}\", ")
                        append("tags = listOf(${operation.tags.joinToString(", ") { "\"${escape(it)}\"" }}), ")
                        append("successStatus = ${operation.successStatus}, ")
                        append("errorStatuses = setOf(${operation.errorStatuses.joinToString(", ")}), ")
                        append("responseEnvelope = ${operation.responseEnvelope}")
                        append(")")
                        appendLine(if (index == operations.lastIndex) "" else ",")
                    }
                    appendLine("    )")
                    appendLine("}")
                }.toByteArray()
            )
            generatedFile.close()
            operationProviders += fqn
        }

        writeServiceFile(
            service = "com.keel.openapi.runtime.OpenApiFragment",
            implementations = fragmentProviders
        )
        writeServiceFile(
            service = "com.keel.openapi.runtime.OpenApiOperationFragment",
            implementations = operationProviders
        )

        return unprocessed
    }

    private fun writeServiceFile(service: String, implementations: List<String>) {
        if (implementations.isEmpty()) return
        val spiFile = codeGenerator.createNewFileByPath(
            dependencies = Dependencies(aggregating = true),
            path = "META-INF/services/$service",
            extensionName = ""
        )
        spiFile.write(implementations.joinToString("\n").toByteArray())
        spiFile.close()
    }

    private fun buildAnnotatedTypeName(file: KSFile): String {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(file.filePath.toByteArray())
            .take(6)
            .joinToString("") { "%02x".format(it) }
        return "AnnotatedOps$hash"
    }

    private fun parseAnnotatedOperations(file: KSFile): List<ParsedOperation> {
        val sourcePath = Path.of(file.filePath)
        if (!Files.exists(sourcePath)) {
            return emptyList()
        }
        val source = Files.readString(sourcePath)
        if (!source.contains("@KeelApi(")) {
            return emptyList()
        }

        val pluginId = extractPluginId(source)
        val operations = mutableListOf<ParsedOperation>()
        val contexts = ArrayDeque<RouteContext>()
        var braceDepth = 0
        var pendingAnnotation: ParsedKeelApi? = null
        val annotationBuffer = StringBuilder()
        val callBuffer = StringBuilder()
        var readingAnnotation = false
        var readingCall = false
        var currentPrefix = ""

        for (line in source.lines()) {
            while (contexts.isNotEmpty() && braceDepth < contexts.last().activeDepth) {
                contexts.removeLast()
            }

            val trimmed = line.trim()

            if (readingAnnotation || trimmed.startsWith("@KeelApi(")) {
                if (!readingAnnotation) {
                    annotationBuffer.clear()
                    readingAnnotation = true
                }
                annotationBuffer.append(trimmed).append('\n')
                if (isBalanced(annotationBuffer.toString(), '(', ')')) {
                    pendingAnnotation = parseKeelApi(annotationBuffer.toString())
                    annotationBuffer.clear()
                    readingAnnotation = false
                }
            } else if (pendingAnnotation != null && (readingCall || (trimmed.isNotBlank() && !trimmed.startsWith("//")))) {
                if (!readingCall) {
                    callBuffer.clear()
                    readingCall = true
                    currentPrefix = contexts.fold("") { prefix, context ->
                        buildPath(prefix, context.prefix)
                    }
                }
                callBuffer.append(trimmed).append('\n')
                if (trimmed.contains("{")) {
                    parseRouteCall(callBuffer.toString(), pendingAnnotation, currentPrefix)?.let(operations::add)
                    callBuffer.clear()
                    pendingAnnotation = null
                    readingCall = false
                }
            }

            detectContext(trimmed, pluginId, braceDepth)?.let(contexts::addLast)
            braceDepth += line.count { it == '{' } - line.count { it == '}' }
        }

        return operations.distinctBy { "${it.method} ${it.path}" }
    }

    private fun detectContext(trimmed: String, pluginId: String?, braceDepth: Int): RouteContext? {
        if (trimmed.contains("systemApi {")) {
            return RouteContext(prefix = "/api/_system", activeDepth = braceDepth + 1)
        }

        val pluginMatch = Regex("""pluginApi\(\s*([^)]+)\)\s*\{""").find(trimmed)
        if (pluginMatch != null) {
            val argument = pluginMatch.groupValues[1].trim()
            val resolvedPluginId = when {
                argument.startsWith("\"") && argument.endsWith("\"") -> argument.trim('"')
                else -> pluginId
            } ?: return null
            return RouteContext(prefix = "/api/plugins/$resolvedPluginId", activeDepth = braceDepth + 1)
        }

        if (trimmed.contains("pluginEndpoints(")) {
            val resolvedPluginId = pluginId ?: return null
            return RouteContext(prefix = "/api/plugins/$resolvedPluginId", activeDepth = braceDepth + 1)
        }

        if ((trimmed.startsWith("route(") || trimmed.startsWith("typedRoute(")) && trimmed.contains("{")) {
            return RouteContext(prefix = extractPath(trimmed), activeDepth = braceDepth + 1)
        }

        return null
    }

    private fun parseRouteCall(
        callSource: String,
        annotation: ParsedKeelApi,
        currentPrefix: String
    ): ParsedOperation? {
        val methodMatch = Regex("""^(typedGet|typedPost|typedPut|typedDelete|documentedGet|documentedPost|documentedPut|documentedDelete|get|post|put|delete|sse|staticResources)""")
            .find(callSource.trim())
            ?: return null

        val callName = methodMatch.groupValues[1]
        val method = when {
            callName.endsWith("Get") || callName == "get" -> "Get"
            callName.endsWith("Post") || callName == "post" -> "Post"
            callName.endsWith("Put") || callName == "put" -> "Put"
            callName.endsWith("Delete") || callName == "delete" -> "Delete"
            callName == "sse" || callName == "staticResources" -> "Get"
            else -> return null
        }

        val path = extractPath(callSource)
        val normalizedPath = buildPath(currentPrefix, path)
        return ParsedOperation(
            method = method,
            path = normalizedPath,
            summary = annotation.summary,
            description = annotation.description,
            tags = annotation.tags,
            successStatus = annotation.successStatus,
            errorStatuses = annotation.errorStatuses,
            responseEnvelope = annotation.responseEnvelope
        )
    }

    private fun extractPath(callSource: String): String {
        val pathAssignment = Regex("""path\s*=\s*"([^"]*)"""").find(callSource)
        if (pathAssignment != null) {
            return pathAssignment.groupValues[1]
        }
        val firstQuotedArg = Regex("""^[^(]*\(\s*"([^"]*)"""", RegexOption.DOT_MATCHES_ALL).find(callSource.trim())
        return firstQuotedArg?.groupValues?.get(1) ?: ""
    }

    private fun buildPath(prefix: String, path: String): String {
        val normalizedPrefix = prefix.trimEnd('/')
        val normalizedPath = path.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("/")) it else "/$it"
        } ?: ""
        val fullPath = "$normalizedPrefix$normalizedPath"
        return fullPath.ifBlank { "/" }
    }

    private fun parseKeelApi(annotationSource: String): ParsedKeelApi {
        val summary = Regex("""summary\s*=\s*"((?:\\.|[^"])*)"""", RegexOption.DOT_MATCHES_ALL)
            .find(annotationSource)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?: Regex("""@KeelApi\(\s*"((?:\\.|[^"])*)"""", RegexOption.DOT_MATCHES_ALL)
                .find(annotationSource)
                ?.groupValues
                ?.get(1)
                ?.replace("\\\"", "\"")
                ?: ""
        val description = Regex("""description\s*=\s*"((?:\\.|[^"])*)"""", RegexOption.DOT_MATCHES_ALL)
            .find(annotationSource)
            ?.groupValues?.get(1)
            ?.replace("\\\"", "\"")
            ?: ""
        val tagsBody = Regex("""tags\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(annotationSource)
            ?.groupValues?.get(1)
            .orEmpty()
        val tags = Regex(""""((?:\\.|[^"])*)"""")
            .findAll(tagsBody)
            .map { it.groupValues[1].replace("\\\"", "\"") }
            .toList()
        val successStatus = Regex("""successStatus\s*=\s*(\d+)""")
            .find(annotationSource)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: 200
        val errorStatusesBody = Regex("""errorStatuses\s*=\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(annotationSource)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val errorStatuses = Regex("""\d+""")
            .findAll(errorStatusesBody)
            .map { it.value.toInt() }
            .toList()
        val responseEnvelope = Regex("""responseEnvelope\s*=\s*(true|false)""")
            .find(annotationSource)
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrictOrNull()
            ?: false
        return ParsedKeelApi(
            summary = summary,
            description = description,
            tags = tags,
            successStatus = successStatus,
            errorStatuses = errorStatuses,
            responseEnvelope = responseEnvelope
        )
    }

    private fun extractPluginId(source: String): String? {
        Regex("""@KeelApiPlugin\(\s*pluginId\s*=\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.let { return it.groupValues[1] }
        Regex("""@KeelApiPlugin\(\s*"([^"]+)"""", RegexOption.DOT_MATCHES_ALL)
            .find(source)
            ?.let { return it.groupValues[1] }
        Regex("""override\s+val\s+pluginId\s*:\s*String\s*=\s*"([^"]+)"""")
            .find(source)
            ?.let { return it.groupValues[1] }
        return null
    }

    private fun isBalanced(text: String, open: Char, close: Char): Boolean {
        var depth = 0
        var inString = false
        var escaped = false
        for (char in text) {
            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (char == open) depth++
                if (char == close) depth--
            }
        }
        return depth == 0
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private data class ParsedKeelApi(
        val summary: String,
        val description: String,
        val tags: List<String>,
        val successStatus: Int,
        val errorStatuses: List<Int>,
        val responseEnvelope: Boolean
    )

    private data class ParsedOperation(
        val method: String,
        val path: String,
        val summary: String,
        val description: String,
        val tags: List<String>,
        val successStatus: Int,
        val errorStatuses: List<Int>,
        val responseEnvelope: Boolean
    )

    private data class RouteContext(
        val prefix: String,
        val activeDepth: Int
    )
}
