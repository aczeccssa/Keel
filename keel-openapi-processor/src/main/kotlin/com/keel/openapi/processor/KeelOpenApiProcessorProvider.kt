package com.keel.openapi.processor

import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider

class KeelOpenApiProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return KeelOpenApiProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}
