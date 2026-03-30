package com.keel.kernel.plugin

import java.util.ServiceLoader
import kotlin.reflect.KClass
import org.koin.core.scope.Scope

internal fun normalizeRequestHeaders(headers: Map<String, List<String>>): Map<String, List<String>> {
    val normalized = linkedMapOf<String, List<String>>()
    headers.forEach { (key, values) ->
        normalized.putIfAbsent(key, values)
        normalized.putIfAbsent(key.lowercase(), values)
        normalized.putIfAbsent(
            key.lowercase().split('-').joinToString("-") { segment ->
                segment.replaceFirstChar { char ->
                    if (char.isLowerCase()) char.titlecase() else char.toString()
                }
            },
            values
        )
    }
    return normalized
}

data class DefaultPluginRequestContext(
    override val pluginId: String,
    override val method: String,
    override val rawPath: String,
    override val pathParameters: Map<String, String>,
    override val queryParameters: Map<String, List<String>>,
    override val requestHeaders: Map<String, List<String>>,
    override val requestId: String,
    override val attributes: MutableMap<String, Any?> = linkedMapOf(),
    override var principal: Any? = null,
    override var tenant: Any? = null
) : PluginRequestContext

data class GeneratedKeelRouteInterceptorMetadata(
    val method: String,
    val path: String,
    val clearDefaults: Boolean = false,
    val interceptorClassNames: List<String> = emptyList()
)

interface KeelGeneratedInterceptorMetadataProvider {
    val pluginClassName: String
    val pluginInterceptors: List<String>
    val routeInterceptors: List<GeneratedKeelRouteInterceptorMetadata>
}

internal fun mergeGeneratedInterceptorMetadata(
    plugin: KeelPlugin,
    routeDefinitions: List<PluginRouteDefinition>
): List<PluginRouteDefinition> {
    val provider = ServiceLoader.load(KeelGeneratedInterceptorMetadataProvider::class.java, plugin.javaClass.classLoader)
        .firstOrNull { it.pluginClassName == plugin.javaClass.name }
        ?: return routeDefinitions
    val generatedPluginInterceptors = resolveInterceptorClasses(plugin.javaClass.classLoader, provider.pluginInterceptors)
    val generatedRoutes = provider.routeInterceptors.associateBy {
        "${it.method.uppercase()} ${normalizeGeneratedRoutePath(it.path)}"
    }
    return routeDefinitions.map { route ->
        val endpoint = route as? PluginEndpointDefinition<*, *> ?: return@map route
        if (endpoint.interceptorSource == InterceptorMetadataSource.DSL) {
            return@map endpoint
        }
        val generatedRoute = generatedRoutes["${endpoint.method.value.uppercase()} ${normalizeGeneratedRoutePath(endpoint.path)}"]
        when {
            generatedRoute != null -> {
                val generatedRouteInterceptors = resolveInterceptorClasses(
                    plugin.javaClass.classLoader,
                    generatedRoute.interceptorClassNames
                )
                val generatedInterceptors = if (generatedRoute.clearDefaults) {
                    generatedRouteInterceptors
                } else {
                    generatedPluginInterceptors + generatedRouteInterceptors
                }
                endpoint.copy(
                    interceptors = generatedInterceptors,
                    interceptorSource = InterceptorMetadataSource.GENERATED
                )
            }
            generatedPluginInterceptors.isNotEmpty() -> endpoint.copy(
                interceptors = generatedPluginInterceptors,
                interceptorSource = InterceptorMetadataSource.GENERATED
            )
            else -> endpoint
        }
    }
}

internal suspend fun executeKeelInterceptors(
    context: PluginRequestContext,
    interceptors: List<KeelRequestInterceptor>,
    terminal: suspend () -> PluginResult<Any?>
): KeelInterceptorResult {
    suspend fun invoke(index: Int): KeelInterceptorResult {
        if (index >= interceptors.size) {
            return KeelInterceptorResult.proceed(terminal())
        }
        return interceptors[index].intercept(context) {
            invoke(index + 1)
        }
    }
    return invoke(0)
}

internal fun resolveInterceptors(
    scope: Scope,
    interceptorClasses: List<KClass<out KeelRequestInterceptor>>
): List<KeelRequestInterceptor> {
    return interceptorClasses.map { interceptorClass ->
        resolveInterceptor(scope, interceptorClass)
    }
}

private fun resolveInterceptor(
    scope: Scope,
    interceptorClass: KClass<out KeelRequestInterceptor>
): KeelRequestInterceptor {
    val scoped = runCatching {
        @Suppress("UNCHECKED_CAST")
        scope.get(clazz = interceptorClass) as KeelRequestInterceptor
    }.getOrNull()
    if (scoped != null) return scoped
    val noArgCtor = interceptorClass.constructors.firstOrNull { it.parameters.isEmpty() }
        ?: error("Interceptor ${interceptorClass.qualifiedName} must be resolvable from plugin scope or expose a no-arg constructor")
    return noArgCtor.call()
}

private fun resolveInterceptorClasses(
    classLoader: ClassLoader,
    classNames: List<String>
): List<KClass<out KeelRequestInterceptor>> {
    return classNames.map { className ->
        val loaded = classLoader.loadClass(className).kotlin
        require(KeelRequestInterceptor::class.java.isAssignableFrom(loaded.java)) {
            "Generated interceptor class $className does not implement KeelRequestInterceptor"
        }
        @Suppress("UNCHECKED_CAST")
        loaded as KClass<out KeelRequestInterceptor>
    }
}

private fun normalizeGeneratedRoutePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed.isBlank() || trimmed == "/") return "/"
    return if (trimmed.startsWith("/")) trimmed else "/$trimmed"
}
