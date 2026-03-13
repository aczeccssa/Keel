package com.keel.contract.di

import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named

/**
 * Single source of truth for framework-wide DI qualifier identifiers.
 */
object KeelDiQualifiers {
    const val KEEL_DATABASE = "keelDatabase"
    const val KERNEL_KOIN = "kernelKoin"

    val keelDatabaseQualifier: Qualifier = named(KEEL_DATABASE)
    val kernelKoinQualifier: Qualifier = named(KERNEL_KOIN)
}
