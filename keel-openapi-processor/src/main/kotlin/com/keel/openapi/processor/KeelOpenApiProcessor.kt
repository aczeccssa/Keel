package com.keel.openapi.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate

/**
 * KSP processor that:
 * 1. reads @KeelApiPlugin annotations and generates OpenApiFragment providers
 * 2. reads interceptor annotations and generates runtime metadata providers
 * 3. fails build when legacy @KeelApi usage is detected
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
        val interceptorAnnotationName = "com.keel.openapi.annotations.KeelInterceptors"
        val routeInterceptorAnnotationName = "com.keel.openapi.annotations.KeelRouteInterceptors"
        val legacyAnnotationName = "com.keel.kernel.api.KeelApi"

        val unprocessed = mutableListOf<KSAnnotated>()
        val fragmentProviders = mutableListOf<String>()
        val interceptorProviders = mutableListOf<String>()
        val candidateClasses = linkedMapOf<String, KSClassDeclaration>()

        resolver.getSymbolsWithAnnotation(pluginAnnotationName).forEach { symbol ->
            if (!symbol.validate()) {
                unprocessed += symbol
                return@forEach
            }
            val classDecl = symbol as? KSClassDeclaration ?: return@forEach
            candidateClasses[classDecl.qualifiedName?.asString().orEmpty()] = classDecl
            val annotation = classDecl.findAnnotation(pluginAnnotationName) ?: return@forEach
            generatePluginFragment(classDecl, annotation)?.let(fragmentProviders::add)
        }
        resolver.getSymbolsWithAnnotation(interceptorAnnotationName).forEach { symbol ->
            if (!symbol.validate()) {
                unprocessed += symbol
                return@forEach
            }
            val classDecl = symbol as? KSClassDeclaration ?: return@forEach
            candidateClasses[classDecl.qualifiedName?.asString().orEmpty()] = classDecl
        }
        resolver.getSymbolsWithAnnotation(routeInterceptorAnnotationName).forEach { symbol ->
            if (!symbol.validate()) {
                unprocessed += symbol
                return@forEach
            }
            val classDecl = symbol as? KSClassDeclaration ?: return@forEach
            candidateClasses[classDecl.qualifiedName?.asString().orEmpty()] = classDecl
        }

        candidateClasses.values.forEach { classDecl ->
            generateInterceptorMetadataProvider(
                classDecl = classDecl,
                interceptorAnnotationName = interceptorAnnotationName,
                routeInterceptorAnnotationName = routeInterceptorAnnotationName
            )?.let(interceptorProviders::add)
        }

        resolver.getSymbolsWithAnnotation(legacyAnnotationName)
            .forEach { symbol ->
                logger.error(
                    "@KeelApi is no longer supported. Migrate endpoint docs to doc = OpenApiDoc(...) on DSL calls.",
                    symbol
                )
            }

        writeServiceFile(
            service = "com.keel.openapi.runtime.OpenApiFragment",
            implementations = fragmentProviders
        )
        writeServiceFile(
            service = "com.keel.kernel.plugin.KeelGeneratedInterceptorMetadataProvider",
            implementations = interceptorProviders
        )

        return unprocessed
    }

    private fun generatePluginFragment(
        classDecl: KSClassDeclaration,
        annotation: KSAnnotation
    ): String? {
        val pluginId = annotation.arguments.firstOrNull { it.name?.asString() == "pluginId" }?.value as? String
            ?: return null
        val title = annotation.arguments.firstOrNull { it.name?.asString() == "title" }?.value as? String
            ?: return null
        val description = annotation.arguments.firstOrNull { it.name?.asString() == "description" }?.value as? String ?: ""
        val version = annotation.arguments.firstOrNull { it.name?.asString() == "version" }?.value as? String ?: "1.0.0"

        val typeName = pluginId.replaceFirstChar { it.uppercase() } + "OpenApiFragment"
        val packageName = "com.keel.openapi.generated"
        val fqn = "$packageName.$typeName"

        logger.info("Generating OpenApiFragment: $fqn for plugin '$pluginId'")

        val file = codeGenerator.createNewFile(
            dependencies = classDecl.containingFile?.let { Dependencies(aggregating = false, it) }
                ?: Dependencies(aggregating = false),
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
        return fqn
    }

    private fun generateInterceptorMetadataProvider(
        classDecl: KSClassDeclaration,
        interceptorAnnotationName: String,
        routeInterceptorAnnotationName: String
    ): String? {
        val className = classDecl.qualifiedName?.asString() ?: return null
        val defaultInterceptors = classDecl.findAnnotation(interceptorAnnotationName)
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "value" }
            ?.value
            .toClassNameList()
        val routeInterceptors = classDecl.annotations
            .filter { it.annotationType.resolve().declaration.qualifiedName?.asString() == routeInterceptorAnnotationName }
            .map { annotation ->
                GeneratedRouteMetadataDescriptor(
                    method = annotation.argumentValue("method") as? String ?: error("Missing method in @KeelRouteInterceptors"),
                    path = annotation.argumentValue("path") as? String ?: error("Missing path in @KeelRouteInterceptors"),
                    clearDefaults = annotation.argumentValue("clearDefaults") as? Boolean ?: false,
                    interceptors = annotation.argumentValue("value").toClassNameList()
                )
            }
            .toList()

        if (defaultInterceptors.isEmpty() && routeInterceptors.isEmpty()) {
            return null
        }

        val typeName = generatedMetadataTypeName(className, "InterceptorMetadata")
        val packageName = "com.keel.generated"
        val fqn = "$packageName.$typeName"
        val routeEntries = routeInterceptors.joinToString(",\n") { route ->
            """
            |        com.keel.kernel.plugin.GeneratedKeelRouteInterceptorMetadata(
            |            method = "${escape(route.method)}",
            |            path = "${escape(route.path)}",
            |            clearDefaults = ${route.clearDefaults},
            |            interceptorClassNames = listOf(${route.interceptors.joinToString(", ") { "\"${escape(it)}\"" }})
            |        )
            """.trimMargin()
        }

        val file = codeGenerator.createNewFile(
            dependencies = classDecl.containingFile?.let { Dependencies(aggregating = false, it) }
                ?: Dependencies(aggregating = false),
            packageName = packageName,
            fileName = typeName
        )
        file.write(
            """
            |package $packageName
            |
            |class $typeName : com.keel.kernel.plugin.KeelGeneratedInterceptorMetadataProvider {
            |    override val pluginClassName: String = "${escape(className)}"
            |    override val pluginInterceptors: List<String> = listOf(${defaultInterceptors.joinToString(", ") { "\"${escape(it)}\"" }})
            |    override val routeInterceptors: List<com.keel.kernel.plugin.GeneratedKeelRouteInterceptorMetadata> = listOf(
            |$routeEntries
            |    )
            |}
            """.trimMargin().toByteArray()
        )
        file.close()
        return fqn
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

    private fun KSClassDeclaration.findAnnotation(annotationName: String): KSAnnotation? {
        return annotations.firstOrNull {
            it.annotationType.resolve().declaration.qualifiedName?.asString() == annotationName
        }
    }

    private fun KSAnnotation.argumentValue(name: String): Any? {
        return arguments.firstOrNull { it.name?.asString() == name }?.value
    }

    private fun Any?.toClassNameList(): List<String> {
        val values = when (this) {
            is List<*> -> this
            null -> emptyList()
            else -> listOf(this)
        }
        return values.mapNotNull { value ->
            when (value) {
                is KSType -> value.declaration.qualifiedName?.asString()
                else -> null
            }
        }
    }

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun generatedMetadataTypeName(className: String, suffix: String): String {
        val sanitized = className.map { char ->
            when {
                char.isLetterOrDigit() -> char
                else -> '_'
            }
        }.joinToString("")
        return "${sanitized}_${suffix}"
    }

    private data class GeneratedRouteMetadataDescriptor(
        val method: String,
        val path: String,
        val clearDefaults: Boolean,
        val interceptors: List<String>
    )
}
