package com.prism.launcher.photos

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.platform.AndroidImageCodec
import com.prism.launcher.trusted.TrustedDevices
import java.io.ByteArrayOutputStream

/**
 * The device's camera roll, as [PhotoBackup.Entry] values.
 *
 * ## Why MediaStore and not a file walk
 *
 * A file walk misses the naming, the bucket, the taken-at time and the duration that MediaStore
 * already knows, and on Android 11+ it cannot read other apps' media directories at all without
 * broad storage access. MediaStore is the index the system itself maintains.
 *
 * ## Thumbnails are requested at the size they are drawn
 *
 * `loadThumbnail` is the API that does this without decoding the original. On API 29+ it is a
 * one-line call and the platform caches the result; older releases fall back to decoding a sample.
 * A page that decodes every original to fill a grid runs out of memory on a phone with two years
 * of photographs on it.
 */
object PhotoRepository {

    private const val TAG = "PrismPhotos"

    /** Every image and video MediaStore knows about, newest first. */
    fun scan(context: Context): List<PhotoBackup.Entry> {
        val out = ArrayList<PhotoBackup.Entry>(512)
        out += images(context)
        out += videos(context)
        return out.sortedByDescending { it.takenAt }
    }

    private data class Columns(
        val id: String,
        val name: String,
        val mime: String,
        val width: String,
        val height: String,
        val taken: String,
        val size: String,
        val modified: String,
        val duration: String?,
    )

    private fun images(context: Context): List<PhotoBackup.Entry> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val columns = Columns(
            id = MediaStore.Images.Media._ID,
            name = MediaStore.Images.Media.DISPLAY_NAME,
            mime = MediaStore.Images.Media.MIME_TYPE,
            width = MediaStore.Images.Media.WIDTH,
            height = MediaStore.Images.Media.HEIGHT,
            taken = MediaStore.Images.Media.DATE_TAKEN,
            size = MediaStore.Images.Media.SIZE,
            modified = MediaStore.Images.Media.DATE_MODIFIED,
            duration = null,
        )
        return query(context, collection, columns, PhotoBackup.Kind.IMAGE)
    }

    private fun videos(context: Context): List<PhotoBackup.Entry> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val columns = Columns(
            id = MediaStore.Video.Media._ID,
            name = MediaStore.Video.Media.DISPLAY_NAME,
            mime = MediaStore.Video.Media.MIME_TYPE,
            width = MediaStore.Video.Media.WIDTH,
            height = MediaStore.Video.Media.HEIGHT,
            taken = MediaStore.Video.Media.DATE_TAKEN,
            size = MediaStore.Video.Media.SIZE,
            modified = MediaStore.Video.Media.DATE_MODIFIED,
            duration = MediaStore.Video.Media.DURATION,
        )
        return query(context, collection, columns, PhotoBackup.Kind.VIDEO)
    }

    private fun query(
        context: Context,
        collection: Uri,
        columns: Columns,
        kind: PhotoBackup.Kind,
    ): List<PhotoBackup.Entry> {
        val projection = buildList {
            add(columns.id); add(columns.name); add(columns.mime)
            add(columns.width); add(columns.height)
            add(columns.taken); add(columns.size); add(columns.modified)
            columns.duration?.let { add(it) }
        }.toTypedArray()

        val entries = ArrayList<PhotoBackup.Entry>()
        runCatching {
            context.contentResolver.query(
                collection, projection, null, null,
                "${columns.taken} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(columns.id)
                val nameColumn = cursor.getColumnIndex(columns.name)
                val mimeColumn = cursor.getColumnIndex(columns.mime)
                val widthColumn = cursor.getColumnIndex(columns.width)
                val heightColumn = cursor.getColumnIndex(columns.height)
                val takenColumn = cursor.getColumnIndex(columns.taken)
                val sizeColumn = cursor.getColumnIndex(columns.size)
                val modifiedColumn = cursor.getColumnIndex(columns.modified)
                val durationColumn = columns.duration?.let { cursor.getColumnIndex(it) } ?: -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val name = nameColumn.takeIf { it >= 0 }?.let { cursor.getString(it) } ?: "item-$id"
                    val mime = mimeColumn.takeIf { it >= 0 }?.let { cursor.getString(it) }
                        ?: if (kind == PhotoBackup.Kind.VIDEO) "video/mp4" else "image/jpeg"
                    val size = sizeColumn.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L
                    val modified = modifiedColumn.takeIf { it >= 0 }?.let { cursor.getLong(it) * 1000 } ?: 0L
                    val taken = takenColumn.takeIf { it >= 0 }?.let { cursor.getLong(it) }?.takeIf { it > 0 } ?: modified
                    entries += PhotoBackup.Entry(
                        id = PhotoBackup.idFor(uri.toString(), name, size, modified),
                        name = name,
                        mime = mime,
                        kind = kind,
                        width = widthColumn.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                        height = heightColumn.takeIf { it >= 0 }?.let { cursor.getInt(it) } ?: 0,
                        durationMs = durationColumn.takeIf { it >= 0 }?.let { cursor.getLong(it) } ?: 0L,
                        takenAt = taken,
                        bytes = size,
                        chunks = 0,
                        origin = PhotoBackup.Origin.LOCAL,
                        localUri = uri.toString(),
                    )
                }
            }
        }.onFailure {
            PrismLogger.logWarning(TAG, "Could not read the media store: ${it.message}")
        }
        return entries
    }

    fun uriOf(entry: PhotoBackup.Entry): Uri? =
        entry.localUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    /** A square-ish thumbnail no larger than [size], or null when the source is gone. */
    fun thumbnail(context: Context, entry: PhotoBackup.Entry, size: Int): Bitmap? {
        val uri = uriOf(entry)
        if (uri != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    return context.contentResolver.loadThumbnail(uri, android.util.Size(size, size), null)
                }.onFailure {
                    // A deleted file or a provider that will not thumbnail it; the fallback below
                    // decodes a sample instead of failing the tile.
                }
            }
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    val sample = sampleFor(bounds.outWidth, bounds.outHeight, size)
                    context.contentResolver.openInputStream(uri)?.use { second ->
                        return BitmapFactory.decodeStream(
                            second, null,
                            BitmapFactory.Options().apply { inSampleSize = sample },
                        )
                    }
                }
            }.onFailure {
                PrismLogger.logWarning(TAG, "Could not thumbnail ${entry.name}: ${it.message}")
            }
        }
        // A backup of a photo whose original has been deleted still has a stored frame.
        val stored = entry.thumb ?: return null
        return runCatching {
            val bytes = android.util.Base64.decode(stored, android.util.Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    private fun sampleFor(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    /** The full image, decoded down to [maxDimension] so a large photo does not exhaust the heap. */
    fun decode(context: Context, entry: PhotoBackup.Entry, maxDimension: Int = 4096): PrismImage? {
        uriOf(entry)?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeStream(stream, null, bounds)
                    val sample = sampleFor(bounds.outWidth, bounds.outHeight, maxDimension)
                    context.contentResolver.openInputStream(uri)?.use { second ->
                        val bitmap = BitmapFactory.decodeStream(
                            second, null,
                            BitmapFactory.Options().apply {
                                inSampleSize = sample
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            },
                        ) ?: return@use null
                        try {
                            AndroidImageCodec.fromBitmap(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }.onFailure {
                PrismLogger.logWarning(TAG, "Could not decode ${entry.name}: ${it.message}")
            }.getOrNull()?.let { return it }
        }

        // Not on this device: the backup holds the bytes.
        val bytes = PhotoBackup.readLocal(entry.id, entry.chunks)
            ?: fetchFromPeers(entry)
            ?: return null
        return runCatching {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            try {
                AndroidImageCodec.fromBitmap(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull()
    }

    /** Pulls every chunk of a remote photo from whichever peer listed it. */
    fun fetchFromPeers(entry: PhotoBackup.Entry): ByteArray? {
        val peer = entry.ownerIp.takeIf { it.isNotBlank() } ?: return null
        val chunks = entry.chunks.takeIf { it > 0 } ?: return null
        val out = ByteArrayOutputStream()
        for (index in 0 until chunks) {
            val chunk = PhotoBackup.fetchChunk(peer, entry.id, index) ?: return null
            out.write(chunk)
        }
        return out.toByteArray()
    }

    // ── Backup ────────────────────────────────────────────────────────────

    data class Progress(val done: Int, val total: Int, val current: String, val bytes: Long)

    data class Report(
        val stored: Int,
        val sentTo: Set<String>,
        val failed: Int,
        val compressedVideos: Int,
        val copiedVideos: Int,
        val bytesBefore: Long,
        val bytesAfter: Long,
    ) {
        fun describe(): String = buildString {
            append("$stored item(s) in this device's vault")
            if (sentTo.isNotEmpty()) append(", sent to ${sentTo.joinToString(", ")}")
            if (failed > 0) append(". $failed could not be read")
            if (copiedVideos > 0) {
                append(". $copiedVideos video(s) were stored without re-encoding — this device has no encoder that would keep them playable")
            }
            if (bytesBefore > 0) {
                val saved = 1.0 - bytesAfter.toDouble() / bytesBefore
                if (saved > 0.01) append(". ${(saved * 100).toInt()}% smaller")
            }
        }
    }

    /**
     * Compresses and backs up everything, newest first.
     *
     * Blocking; the page runs it on a background thread and shows [onProgress]. Existing backups
     * are skipped by id, so a second run only handles what is new -- which is what makes running it
     * on every launch reasonable.
     */
    fun backupAll(
        context: Context,
        entries: List<PhotoBackup.Entry>,
        onProgress: (Progress) -> Unit = {},
    ): Report {
        val peers = runCatching {
            TrustedDevices.all().filter { it.confirmed && it.lastIp.isNotBlank() }
                .map { it.lastIp to it.name }
        }.getOrDefault(emptyList())

        val already = PhotoBackup.localEntries().associateBy { it.id }
        val recipients = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        var stored = 0
        var failed = 0
        var compressedVideos = 0
        var copiedVideos = 0
        var before = 0L
        var after = 0L

        var done = 0
        for (entry in entries) {
            onProgress(Progress(done, entries.size, entry.name, after))
            done++

            val existing = already[entry.id]
            if (existing != null && existing.chunks > 0) {
                // Already in the vault AND already offered to the peers when it was stored. Re-sending
                // the whole library on every scan would saturate the mesh with data that is already
                // there; the page has a separate action for "send this one again".
                continue
            }

            val payload = compress(context, entry) ?: run {
                failed++
                null
            } ?: continue
            if (payload.kind == PhotoBackup.Kind.VIDEO) {
                if (payload.compressed) compressedVideos++ else copiedVideos++
            }
            val storedEntry = PhotoBackup.storeLocally(payload) ?: run {
                failed++
                null
            } ?: continue
            stored++
            // The ORIGINAL size, so the report can say how much the compression saved. Using the
            // stored size for both sides made that percentage always zero, which is a claim of
            // "no saving" rather than a measurement of one.
            before += entry.bytes.takeIf { it > 0 } ?: payload.data.size.toLong()
            after += payload.data.size.toLong()
            PhotoBackup.send(storedEntry, peers).forEach { recipients.add(it) }
        }

        onProgress(Progress(entries.size, entries.size, "", after))
        return Report(stored, recipients, failed, compressedVideos, copiedVideos, before, after)
    }

    /**
     * Compresses one item for the vault.
     *
     * Images are re-encoded to JPEG at the configured longest edge and quality. Videos go through
     * [VideoTranscoder], which re-encodes at a lower resolution and bitrate; when the device refuses,
     * the original bytes are stored and the report says so rather than claiming a compression that
     * did not happen.
     */
    fun compress(context: Context, entry: PhotoBackup.Entry): PhotoBackup.Payload? {
        val uri = uriOf(entry) ?: return null
        val maxEdge = PrismSettings.getPhotoBackupMaxEdge()
        val quality = PrismSettings.getPhotoBackupQuality()

        if (entry.kind == PhotoBackup.Kind.VIDEO) {
            val transcoded = VideoTranscoder.compress(context, uri, maxEdge)
            val bytes = transcoded?.bytes ?: runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: return null
            return PhotoBackup.Payload(
                id = entry.id,
                name = entry.name,
                mime = if (transcoded != null) "video/mp4" else entry.mime,
                kind = PhotoBackup.Kind.VIDEO,
                width = transcoded?.width ?: entry.width,
                height = transcoded?.height ?: entry.height,
                durationMs = transcoded?.durationMs ?: entry.durationMs,
                takenAt = entry.takenAt,
                data = bytes,
                thumb = videoPoster(context, uri),
                compressed = transcoded != null,
                localUri = entry.localUri,
            )
        }

        val bitmap = decodeBitmap(context, uri, maxEdge) ?: return null
        return try {
            val bytes = ByteArrayOutputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
            val thumbBitmap = scaleTo(bitmap, 320)
            val thumb = ByteArrayOutputStream().use { out ->
                thumbBitmap.compress(Bitmap.CompressFormat.JPEG, 70, out)
                out.toByteArray()
            }
            if (thumbBitmap !== bitmap) thumbBitmap.recycle()
            PhotoBackup.Payload(
                id = entry.id,
                name = entry.name,
                mime = "image/jpeg",
                kind = PhotoBackup.Kind.IMAGE,
                width = bitmap.width,
                height = bitmap.height,
                durationMs = 0,
                takenAt = entry.takenAt,
                data = bytes,
                thumb = thumb,
                compressed = true,
                localUri = entry.localUri,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeBitmap(context: Context, uri: Uri, maxEdge: Int): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, bounds)
            val sample = sampleFor(bounds.outWidth, bounds.outHeight, maxEdge)
            context.contentResolver.openInputStream(uri)?.use { second ->
                BitmapFactory.decodeStream(
                    second, null,
                    BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    },
                )
            }
        }
    }.getOrNull()

    private fun scaleTo(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val scale = minOf(maxEdge.toDouble() / bitmap.width, maxEdge.toDouble() / bitmap.height)
        if (scale >= 1.0) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun videoPoster(context: Context, uri: Uri): ByteArray? = runCatching {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val frame = retriever.getFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scaled = scaleTo(frame, 320)
            ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 70, out)
                if (scaled !== frame) scaled.recycle()
                out.toByteArray()
            }
        } finally {
            retriever.release()
        }
    }.getOrNull()

    /** The URI a video player should open: the original when it exists, otherwise the backup. */
    fun playableUri(context: Context, entry: PhotoBackup.Entry): Uri? {
        uriOf(entry)?.takeIf { exists(context, it) }?.let { return it }
        val bytes = PhotoBackup.readLocal(entry.id, entry.chunks) ?: return null
        val file = java.io.File(context.cacheDir, "photo-backup-${entry.id}.mp4").apply { writeBytes(bytes) }
        return Uri.fromFile(file)
    }

    private fun exists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { } != null
    }.getOrDefault(false)

    /** Ensures the key and the opcode handlers are live for this process. */
    fun start() {
        runCatching { PhotoBackup.install() }
            .onFailure { PrismLogger.logWarning(TAG, "Photo backup did not install: ${it.message}") }
    }
}
