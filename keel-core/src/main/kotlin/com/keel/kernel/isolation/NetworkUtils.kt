package com.keel.kernel.isolation

import java.net.ServerSocket

internal object NetworkUtils {
    fun allocateFreePort(): Int {
        ServerSocket(0).use { return it.localPort }
    }

    fun allocateDistinctPorts(count: Int): List<Int> {
        val ports = LinkedHashSet<Int>(count)
        while (ports.size < count) {
            ports.add(allocateFreePort())
        }
        return ports.toList()
    }
}
