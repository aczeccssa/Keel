package com.keel.openapi.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.validate

/**
 * KSP processor that:
 * 1. reads @KeelApiPlugin annotations and generates OpenApiFragment providers
 * 2. fails build when legacy @KeelApi usage is detected
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
        val legacyAnnotationName = "com.keel.kernel.api.KeelApi"
        val unprocessed = mutableListOf<KSAnnotated>()
        val fragmentProviders = mutableListOf<String>()

        resolver.getSymbolsWithAnnotation(pluginAnnotationName).forEach { symbol ->
            if (!symbol.validate()) {
                unprocessed += symbol
                return@forEach
            }
            val classDecl = symbol as? KSClassDeclaration ?: return@forEach
            val annotation = classDecl.findAnnotation(pluginAnnotationName) ?: return@forEach
            generatePluginFragment(classDecl, annotation)?.let(fragmentProviders::add)
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

    private fun escape(value: String): String {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
