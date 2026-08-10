package com.prism.launcher.vpn

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.prism.launcher.browser.PrismTunnelEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object AppUsageMonitor {
    private var isRunning = false
    private var job: Job? = null
    private var currentPackage: String? = null
    private var lastWhitelistedState = false

    fun start(context: Context, engine: PrismTunnelEngine) {
        if (isRunning) return
        isRunning = true
        
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        job = CoroutineScope(Dispatchers.IO).launch {
            while (isActive && isRunning) {
                val time = System.currentTimeMillis()
                val events = usm.queryEvents(time - 2000, time)
                var latestEvent: UsageEvents.Event? = null
                
                while (events.hasNextEvent()) {
                    val event = UsageEvents.Event()
                    events.getNextEvent(event)
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        latestEvent = event
                    }
                }
                
                if (latestEvent != null && latestEvent.packageName != currentPackage) {
                    currentPackage = latestEvent.packageName
                    val appWhitelist = com.prism.launcher.PrismSettings.getAppWhitelist()
                    val isWhitelisted = appWhitelist.contains(currentPackage)
                    
                    if (isWhitelisted != lastWhitelistedState) {
                        lastWhitelistedState = isWhitelisted
                        // In Prism tunnel engine, we can pause or resume based on this!
                        // The prompt described dynamically detaching TunnelEngine. 
                        // To keep it simple, we'll assume tunneling acts upon this callback or simply we set a state.
                        if (isWhitelisted) {
                            engine.pauseTunnel() // Detaches external tunnels but keeps base engine/DNS
                        } else {
                            engine.resumeTunnel() // Re-enables tunnel
                        }
                    }
                }
                
                delay(1000)
            }
        }
    }

    fun stop() {
        isRunning = false
        job?.cancel()
        job = null
    }
}
