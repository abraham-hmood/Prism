package com.prism.launcher.vpn

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import com.prism.launcher.PrismSettings

object PrismProxyClient {
    fun start(context: Context) {
        val servers = PrismSettings.getPrismServers()
        if (servers.isEmpty()) return

        val builder = ProxyConfig.Builder()
        
        // 1. Add Active server first
        val active = servers.find { it.isActive }
        if (active != null) {
            builder.addProxyRule("http://${active.address}:${active.port}")
        }
        
        // 2. Add others as fallbacks
        servers.filter { it != active }.forEach {
            builder.addProxyRule("http://${it.address}:${it.port}")
        }

        builder.addDirect()

        ProxyController.getInstance().setProxyOverride(
            builder.build(), { it.run() }, {}
        )
    }

    fun stop() {
        ProxyController.getInstance().clearProxyOverride({ it.run() }, {})
    }
}
