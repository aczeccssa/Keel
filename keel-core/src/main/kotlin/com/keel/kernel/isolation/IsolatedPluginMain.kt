package com.keel.kernel.isolation

@Deprecated("Use ExternalPluginHostMain", ReplaceWith("ExternalPluginHostMain"))
object IsolatedPluginMain {
    @JvmStatic
    fun main(args: Array<String>) {
        ExternalPluginHostMain.main(args)
    }
}
