package com.prism.launcher.minigames

import com.prism.core.MeshCore
import com.prism.core.MeshTransport
import com.prism.core.PrismPlatform
import com.prism.core.json.JSONObject

/**
 * GeoTag over the mesh.
 *
 * A tag is already the whole state: the wall key, which surface family it belongs to, who put it
 * there, and the seed of the artwork. That is a few hundred bytes, so this is a broadcast rather
 * than a service, and a dropped packet costs a neighbour one tag until they hear it again.
 *
 * ## Why the opcode is 0x5E
 *
 * 0x50..0x57 belong to the minigame protocol, 0x58 to Kokoro announcements and 0x59..0x5D to photo
 * backup. 0x5E is the next free byte above all of them. A clash here is not a compile error; it is
 * two features that each stop seeing the other's packets.
 */
object GeoTagNet {

    const val OPCODE: Byte = 0x5E

    /** Called on the transport's thread after a tag has been stored. */
    @Volatile
    var onReceived: ((GeoTagStore.Tag) -> Unit)? = null

    private fun available(): Boolean = runCatching { MeshTransport.isOnMesh() }.getOrDefault(false)

    /**
     * Tells the mesh where a piece was placed.
     *
     * Fire and forget: the tag is already saved locally, and a neighbour who misses it sees the
     * wall on the Map without the picture until the next placement there.
     */
    fun announce(tag: GeoTagStore.Tag, art: GeoTagStore.Artwork?) {
        if (!available()) return
        val payload = JSONObject().apply {
            put("id", tag.id)
            put("art", tag.artId)
            put("title", art?.title.orEmpty())
            put("artist", art?.artist.orEmpty())
            put("seed", art?.seed ?: 0L)
            put("x", tag.x.toDouble())
            put("y", tag.y.toDouble())
            put("wall", tag.wall)
            put("lat", tag.lat)
            put("lng", tag.lng)
            put("author", tag.author)
            put("at", tag.at)
            put("surface", tag.surface.key)
        }.toString()
        runCatching { MeshTransport.announce(OPCODE, payload) }
            .onFailure { PrismPlatform.log.warn(TAG, "Could not announce a tag: ${it.message}") }
    }

    /**
     * A tag from another device.
     *
     * The payload is untrusted: a peer chooses its own coordinates, and that is fine because the
     * location only decides which wall the tag occupies. Nothing here grants XP, deletes local
     * artwork or writes outside GeoTag's own store.
     */
    fun receive(peerIp: String, payload: String): Boolean = runCatching {
        val json = JSONObject(payload)
        val id = json.optString("id")
        val artId = json.optString("art")
        val wall = json.optString("wall")
        if (id.isBlank() || artId.isBlank() || wall.isBlank()) return@runCatching false

        GeoTagStore.learn(
            id = artId,
            title = json.optString("title"),
            artist = json.optString("artist").ifBlank { peerIp },
            seed = json.optLong("seed", artId.hashCode().toLong()),
        )
        val tag = GeoTagStore.Tag(
            id = id,
            artId = artId,
            x = json.optDouble("x", 0.5).toFloat(),
            y = json.optDouble("y", 0.5).toFloat(),
            wall = wall,
            lat = json.optDouble("lat", 0.0),
            lng = json.optDouble("lng", 0.0),
            author = json.optString("author").ifBlank { peerIp },
            at = json.optLong("at", System.currentTimeMillis()),
            surface = GeoTagStore.Surface.fromKey(json.optString("surface", "wall")),
        )
        val accepted = GeoTagStore.receive(tag)
        if (accepted) onReceived?.invoke(tag)
        accepted
    }.getOrElse {
        PrismPlatform.log.warn(TAG, "Bad GeoTag packet from $peerIp: ${it.message}")
        false
    }

    /**
     * Desktop registration. Android's transport has its own dispatch and calls [receive] directly;
     * MeshCore needs a handler installed once, which the desktop does at startup.
     */
    fun install() {
        MeshCore.register(OPCODE) { peerIp, payload -> receive(peerIp, payload) }
    }

    private const val TAG = "PrismGeoTag"
}
