package com.keel.openapi.runtime

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import java.util.jar.JarFile

/**
 * Discovers OpenApiFragment implementations via ServiceLoader and merges
 * runtime-registered operations into a unified OpenAPI 3.1.0 specification.
 */
object OpenApiAggregator {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    @Volatile
    private var fragmentCache: List<OpenApiFragment>? = null
    @Volatile
    private var declaredOperationsCache: List<OpenApiDeclaredOperation>? = null
    @Volatile
    private var specCache: CachedSpec? = null

    /**
     * Discover all registered OpenApiFragment implementations via ServiceLoader.
     */
    fun discoverFragments(): List<OpenApiFragment> {
        fragmentCache?.let { return it }
        return discoverImplementations(
            interfaceClass = OpenApiFragment::class.java,
            generatedClassNameFilter = { it.endsWith("OpenApiFragment") }
        ).also { fragmentCache = it }
    }

    fun discoverDeclaredOperations(): List<OpenApiDeclaredOperation> {
        declaredOperationsCache?.let { return it }
        return discoverImplementations(
            interfaceClass = OpenApiOperationFragment::class.java,
            generatedClassNameFilter = { it.contains(".AnnotatedOps_") }
        )
            .flatMap { it.operations() }
            .also { declaredOperationsCache = it }
    }

    fun buildSpec(serverUrl: String = "http://localhost:8080"): String {
        val topologyVersion = OpenApiRegistry.topologyVersion()
        specCache?.takeIf { it.serverUrl == serverUrl && it.topologyVersion == topologyVersion }?.let { cached ->
            return cached.spec
        }
        val fragments = discoverFragments()
        val operations = mergeOperations(discoverDeclaredOperations(), OpenApiRegistry.operations())
        val spec = if (operations.isEmpty()) {
            buildMinimalSpec(serverUrl)
        } else {
            val schemaGenerator = OpenApiSchemaGenerator()
            val tags = buildTags(fragments, operations)
            val paths = buildPaths(operations, schemaGenerator)

            val specObject = JsonObject(buildMap {
                put("openapi", JsonPrimitive("3.1.0"))
                put("info", JsonObject(mapOf(
                    "title" to JsonPrimitive("Keel API"),
                    "description" to JsonPrimitive("Auto-generated API documentation for the Keel modular monolith"),
                    "version" to JsonPrimitive("1.0.0")
                )))
                put("servers", JsonArray(listOf(
                    JsonObject(mapOf(
                        "url" to JsonPrimitive(serverUrl),
                        "description" to JsonPrimitive("Local development server")
                    ))
                )))
                put("tags", JsonArray(tags))
                put("paths", JsonObject(paths))
                if (schemaGenerator.components().isNotEmpty()) {
                    put("components", JsonObject(mapOf(
                        "schemas" to JsonObject(schemaGenerator.components())
                    )))
                }
            })

            json.encodeToString(JsonElement.serializer(), specObject)
        }
        specCache = CachedSpec(serverUrl = serverUrl, topologyVersion = topologyVersion, spec = spec)
        return spec
    }

    /**
     * Build a minimal spec from fragments alone (without InspeKtor-generated specs).
     * This provides basic documentation even when InspeKtor specs are not available.
     */
    fun buildMinimalSpec(serverUrl: String = "http://localhost:8080"): String {
        val fragments = discoverFragments()
        val tags = mutableListOf<JsonElement>()

        tags.add(JsonObject(mapOf(
            "name" to JsonPrimitive("system"),
            "description" to JsonPrimitive("Keel System Management API")
        )))

        for (fragment in fragments) {
            tags.add(JsonObject(buildMap {
                put("name", JsonPrimitive(fragment.pluginId))
                put("description", JsonPrimitive(
                    fragment.description.ifEmpty { "${fragment.title} API" }
                ))
            }))
        }

        val spec = JsonObject(buildMap {
            put("openapi", JsonPrimitive("3.1.0"))
            put("info", JsonObject(mapOf(
                "title" to JsonPrimitive("Keel API"),
                "description" to JsonPrimitive("Auto-generated API documentation for the Keel modular monolith"),
                "version" to JsonPrimitive("1.0.0")
            )))
            put("servers", JsonArray(listOf(
                JsonObject(mapOf(
                    "url" to JsonPrimitive(serverUrl),
                    "description" to JsonPrimitive("Local development server")
                ))
            )))
            put("tags", JsonArray(tags))
            put("paths", JsonObject(emptyMap()))
        })

        return json.encodeToString(JsonElement.serializer(), spec)
    }

    internal fun invalidateCache() {
        specCache = null
    }

    private fun buildTags(
        fragments: List<OpenApiFragment>,
        operations: List<OpenApiOperation>
    ): List<JsonElement> {
        val tags = linkedMapOf<String, JsonElement>()
        tags["system"] = JsonObject(
            mapOf(
                "name" to JsonPrimitive("system"),
                "description" to JsonPrimitive("Keel System Management API")
            )
        )
        for (fragment in fragments) {
            tags[fragment.pluginId] = JsonObject(
                mapOf(
                    "name" to JsonPrimitive(fragment.pluginId),
                    "description" to JsonPrimitive(fragment.description.ifEmpty { "${fragment.title} API" })
                )
            )
        }
        for (operation in operations) {
            for (tag in operation.tags) {
                tags.putIfAbsent(
                    tag,
                    JsonObject(
                        mapOf(
                            "name" to JsonPrimitive(tag),
                            "description" to JsonPrimitive("${tag.replaceFirstChar { it.uppercase() }} API")
                        )
                    )
                )
            }
        }
        return tags.values.toList()
    }

    private fun mergeOperations(
        declaredOperations: List<OpenApiDeclaredOperation>,
        runtimeOperations: List<OpenApiOperation>
    ): List<OpenApiOperation> {
        val runtimeByKey = runtimeOperations.associateBy { "${it.method.value} ${it.path}" }
        val declaredByKey = declaredOperations.associateBy { "${it.method.value} ${it.path}" }
        val allKeys = (runtimeByKey.keys + declaredByKey.keys).sorted()

        return allKeys.mapNotNull { key ->
            val runtime = runtimeByKey[key]
            val declared = declaredByKey[key]
            when {
                runtime != null && declared != null -> runtime.copy(
                    summary = runtime.summary.ifBlank { declared.summary },
                    description = runtime.description.ifBlank { declared.description },
                    tags = if (declared.tags.isNotEmpty()) declared.tags else runtime.tags,
                    successStatus = declared.successStatus,
                    errorStatuses = if (declared.errorStatuses.isNotEmpty()) declared.errorStatuses else runtime.errorStatuses,
                    responseEnvelope = declared.responseEnvelope
                )
                runtime != null -> runtime
                declared != null -> OpenApiOperation(
                    method = declared.method,
                    path = declared.path,
                    requestBodyType = null,
                    responseBodyType = null,
                    typeBound = false,
                    summary = declared.summary,
                    description = declared.description,
                    tags = declared.tags,
                    successStatus = declared.successStatus,
                    errorStatuses = declared.errorStatuses,
                    responseEnvelope = declared.responseEnvelope
                )
                else -> null
            }
        }
    }

    private fun <T : Any> discoverImplementations(
        interfaceClass: Class<T>,
        generatedClassNameFilter: (String) -> Boolean
    ): List<T> {
        val classLoader = Thread.currentThread().contextClassLoader ?: interfaceClass.classLoader
        val discovered = linkedMapOf<String, T>()

        val serviceLoader = ServiceLoader.load(interfaceClass, classLoader)
        val iterator = serviceLoader.iterator()
        while (true) {
            val hasNext = try {
                iterator.hasNext()
            } catch (_: ServiceConfigurationError) {
                continue
            }
            if (!hasNext) {
                break
            }
            val implementation = try {
                iterator.next()
            } catch (_: ServiceConfigurationError) {
                continue
            }
            discovered[implementation.javaClass.name] = implementation
        }

        for (className in scanGeneratedClassNames(classLoader)) {
            if (!generatedClassNameFilter(className) || discovered.containsKey(className)) {
                continue
            }
            runCatching {
                val clazz = Class.forName(className, true, classLoader)
                if (!interfaceClass.isAssignableFrom(clazz) || java.lang.reflect.Modifier.isAbstract(clazz.modifiers)) {
                    return@runCatching null
                }
                @Suppress("UNCHECKED_CAST")
                clazz.getDeclaredConstructor().newInstance() as T
            }.getOrNull()?.let { implementation ->
                discovered[className] = implementation
            }
        }

        return discovered.values.toList()
    }

    private fun scanGeneratedClassNames(classLoader: ClassLoader): Set<String> {
        val classNames = linkedSetOf<String>()
        val root = "com/keel/openapi/generated"
        val classPath = System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }

        for (entry in classPath) {
            val file = File(entry)
            when {
                file.isDirectory -> {
                    val rootDir = File(file, root)
                    if (!rootDir.exists()) continue
                    rootDir.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .forEach { classFile ->
                            val relative = classFile.relativeTo(file).invariantSeparatorsPath.removeSuffix(".class")
                            classNames += relative.replace('/', '.')
                        }
                }
                file.isFile && file.extension == "jar" -> {
                    runCatching {
                        JarFile(file).use { jar ->
                            jar.entries().asSequence()
                                .filter { !it.isDirectory && it.name.startsWith(root) && it.name.endsWith(".class") }
                                .forEach { entryName ->
                                    classNames += entryName.name.removeSuffix(".class").replace('/', '.')
                                }
                        }
                    }
                }
            }
        }

        classLoader.getResources(root).toList().forEach { resource ->
            if (resource.protocol == "file") {
                val resourceDir = File(resource.toURI())
                if (!resourceDir.exists()) return@forEach
                resourceDir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .forEach { classFile ->
                        val relative = classFile.relativeTo(resourceDir.parentFile.parentFile.parentFile.parentFile)
                            .invariantSeparatorsPath
                            .removeSuffix(".class")
                        classNames += relative.replace('/', '.')
                    }
            }
        }

        return classNames
    }

    private fun buildPaths(
        operations: List<OpenApiOperation>,
        schemaGenerator: OpenApiSchemaGenerator
    ): Map<String, JsonElement> {
        val paths = linkedMapOf<String, MutableMap<String, JsonElement>>()
        for (operation in operations) {
            val methodKey = operation.method.value.lowercase()
            val operationObject = JsonObject(
                buildMap {
                    put("summary", JsonPrimitive(operation.summary))
                    if (operation.description.isNotBlank()) {
                        put("description", JsonPrimitive(operation.description))
                    }
                    if (operation.tags.isNotEmpty()) {
                        put("tags", JsonArray(operation.tags.distinct().map(::JsonPrimitive)))
                    }
                    val parameters = inferPathParameters(operation.path)
                    if (parameters.isNotEmpty()) {
                        put("parameters", JsonArray(parameters))
                    }
                    if (operation.requestBodyType != null && operation.method != HttpMethod.Get) {
                        put(
                            "requestBody",
                            JsonObject(
                                mapOf(
                                    "required" to JsonPrimitive(true),
                                    "content" to JsonObject(
                                        mapOf(
                                            "application/json" to JsonObject(
                                                mapOf("schema" to schemaGenerator.schemaForType(operation.requestBodyType))
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    }
                    put("responses", buildResponses(operation, schemaGenerator))
                }
            )
            paths.getOrPut(operation.path) { linkedMapOf() }[methodKey] = operationObject
        }
        return paths.mapValues { JsonObject(it.value) }
    }

    private fun buildResponses(
        operation: OpenApiOperation,
        schemaGenerator: OpenApiSchemaGenerator
    ): JsonObject {
        val responses = linkedMapOf<String, JsonElement>()
        responses[operation.successStatus.toString()] = JsonObject(
            mapOf(
                "description" to JsonPrimitive("Successful response"),
                "content" to JsonObject(
                    mapOf(
                        "application/json" to JsonObject(
                            mapOf(
                                "schema" to schemaGenerator.schemaForResponse(
                                    operation.responseBodyType,
                                    operation.responseEnvelope
                                )
                            )
                        )
                    )
                )
            )
        )
        for (status in operation.errorStatuses.toSortedSet()) {
            responses[status.toString()] = JsonObject(
                mapOf(
                    "description" to JsonPrimitive("Error response"),
                    "content" to JsonObject(
                        mapOf(
                            "application/json" to JsonObject(
                                mapOf("schema" to schemaGenerator.schemaForResponse(null, wrapped = true))
                            )
                        )
                    )
                )
            )
        }
        return JsonObject(responses)
    }

    private fun inferPathParameters(path: String): List<JsonElement> {
        return "\\{([^}/]+)\\}".toRegex()
            .findAll(path)
            .map { match ->
                val name = match.groupValues[1]
                JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(name),
                        "in" to JsonPrimitive("path"),
                        "required" to JsonPrimitive(true),
                        "schema" to JsonObject(mapOf("type" to JsonPrimitive("string")))
                    )
                )
            }
            .toList()
    }

    private data class CachedSpec(
        val serverUrl: String,
        val topologyVersion: Long,
        val spec: String
    )
}
