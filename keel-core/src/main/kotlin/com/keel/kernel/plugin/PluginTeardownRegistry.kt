package com.keel.kernel.plugin

import java.util.ArrayDeque

class PluginTeardownRegistry {
    private val actions = ArrayDeque<() -> Unit>()

    fun register(action: () -> Unit) {
        synchronized(actions) {
            actions.addFirst(action)
        }
    }

    fun runAll() {
        while (true) {
            val action = synchronized(actions) {
                if (actions.isEmpty()) null else actions.removeFirst()
            } ?: return
            runCatching(action)
        }
    }

    fun size(): Int = synchronized(actions) { actions.size }
}
