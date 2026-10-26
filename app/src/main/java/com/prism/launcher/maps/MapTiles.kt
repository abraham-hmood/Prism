package com.prism.launcher.maps

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.prism.core.PrismPlatform
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * OpenStreetMap tiles, on disk and in memory.
 *
 * The disk cache is the offline map. A tile that has been viewed once or deliberately downloaded
 * is read from `maptiles/z/x/y.png` before any network request, so a downloaded area keeps working
 * with the radio off. Positions are never part of a request: a tile is a zoom, column and row.
 */
object MapTiles {

    private const val USER_AGENT = "Prism/1.0 (Android map; contact: prism@localhost)"
    private const val MEMORY_LIMIT = 500
    private const val TILE_URL = "https://tile.openstreetmap.org"

    private val memory = ConcurrentHashMap<String, Bitmap>()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "prism-map-tiles").apply { isDaemon = true }
    }

    fun get(z: Int, x: Int, y: Int, loaded: () -> Unit): Bitmap? {
        val key = "$z/$x/$y"
        memory[key]?.let { return it }
        if (!pending.add(key)) return null
        executor.execute {
            val bitmap = read(z, x, y)
            if (bitmap != null) {
                if (memory.size > MEMORY_LIMIT) memory.clear()
                memory[key] = bitmap
            }
            pending.remove(key)
            loaded()
        }
        return null
    }

    /** Downloads one tile if it is not already on disk. Used by the offline area downloader. */
    fun ensure(z: Int, x: Int, y: Int): Boolean {
        val file = fileFor(z, x)?.let { File(it, "$y.png") } ?: return false
        if (file.isFile && file.length() > 0) return true
        val bitmap = read(z, x, y) ?: return false
        if (memory.size > MEMORY_LIMIT) memory.clear()
        memory["$z/$x/$y"] = bitmap
        return true
    }

    fun fileFor(z: Int, x: Int): File? = runCatching {
        File(PrismPlatform.host.dataDir(), "maptiles/$z/$x").apply { mkdirs() }
    }.getOrNull()

    fun delete(z: Int, x: Int, y: Int) {
        memory.remove("$z/$x/$y")
        runCatching { File(fileFor(z, x), "$y.png").delete() }
    }

    /** Bytes this tile occupies on disk, or zero if it is not cached. */
    fun bytesOf(z: Int, x: Int, y: Int): Long =
        runCatching {
            File(fileFor(z, x), "$y.png").takeIf { it.isFile }?.length() ?: 0L
        }.getOrDefault(0L)

    fun clearMemory() = memory.clear()

    private fun read(z: Int, x: Int, y: Int): Bitmap? {
        val dir = fileFor(z, x) ?: return null
        val file = File(dir, "$y.png")
        if (file.isFile) {
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        return runCatching {
            val connection = URL("$TILE_URL/$z/$x/$y.png").openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.inputStream.use { stream ->
                val bytes = stream.readBytes()
                file.writeBytes(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }.getOrNull()
    }
}
