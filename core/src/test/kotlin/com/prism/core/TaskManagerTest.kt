package com.prism.core

import com.prism.launcher.taskmanager.TaskManager
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The Task Manager's scan is the part that can fail differently on every operating system.
 *
 * These tests do not assert a particular process or memory figure -- those belong to the machine
 * running them. They assert that the scan produced a real process table, that this JVM is in it,
 * and that the hardware fields are populated rather than silently blank.
 */
class TaskManagerTest {

    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun installTempHost() {
        previousHost = PrismPlatform.host
        val temp = File(System.getProperty("java.io.tmpdir"), "prism-tasks-${System.nanoTime()}")
        temp.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = temp)
    }

    @AfterTest
    fun restore() {
        PrismPlatform.host = previousHost
    }

    @Test
    fun `the process table includes this process and a cpu name`() {
        val snapshot = TaskManager.snapshot(force = true)

        assertTrue(snapshot.tasks.isNotEmpty(), "no processes were returned")
        assertTrue(snapshot.cpu.isNotBlank(), "cpu was blank")
        assertTrue(snapshot.ramTotalBytes > 0, "total memory was not read")
        assertTrue(snapshot.volumes.isNotEmpty(), "no storage roots were returned")

        val own = snapshot.tasks.firstOrNull { it.current }
        assertTrue(own != null, "this JVM was not in the process table")
        assertTrue(own!!.memoryBytes > 0, "this JVM reported no resident memory")
    }

    @Test
    fun `holder lookup is safe for a path nothing holds`() {
        val file = File(System.getProperty("java.io.tmpdir"), "prism-unheld-${System.nanoTime()}.txt")
        file.writeText("x")
        try {
            val result = TaskManager.holders(file)
            // The scan must return a value rather than throw; an empty list is the normal answer
            // for a file that was written and closed.
            assertTrue(result.holders.isEmpty() || result.note.isNotBlank())
        } finally {
            file.delete()
        }
    }
}
