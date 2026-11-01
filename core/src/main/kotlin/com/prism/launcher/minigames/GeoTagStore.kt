package com.prism.launcher.minigames

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * GeoTag's library, shop, places and XP.
 *
 * ## What a "tag" is
 *
 * A piece of artwork the player placed on a real wall, floor or ceiling. ARCore supplies the plane
 * and the anchor; this store keeps the part that survives the session -- where on Earth it was and
 * which kind of surface it belongs to -- so the Map pin and the next player on the mesh agree with
 * the placement. A tag is replaced when someone else tags the same wall, which is the game's only
 * rule.
 *
 * ## One hundred shop pieces without one hundred files
 *
 * The shop's art is procedurally drawn from a seed. Shipping a hundred PNGs would add megabytes to
 * an APK for pictures that only ever appear in a grid, and drawing them at the display's size keeps
 * them sharp. User-imported PNGs are the exception: those are real files under `geotag/imports`.
 */
object GeoTagStore {

    /** The surface an ARCore plane represents. The key travels on the mesh. */
    enum class Surface(val key: String, val label: String) {
        WALL("wall", "Wall"),
        FLOOR("floor", "Floor"),
        CEILING("ceiling", "Ceiling"),
        ;

        /**
         * A ceiling is viewed from below. Its upright basis puts the picture's right axis on the
         * room's left, so the texture is sampled right-to-left to stop the artwork reading mirrored.
         * Floors and walls are already seen from their front side and need no flip.
         */
        val flipsTextureU: Boolean get() = this == CEILING

        companion object {
            fun fromKey(key: String?): Surface =
                entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: WALL
        }
    }

    data class Artwork(
        val id: String,
        val title: String,
        val artist: String,
        val seed: Long,
        val price: Int,
        val owned: Boolean,
        val forSale: Boolean = false,
        val importedPath: String = "",
    )

    data class Tag(
        val id: String,
        val artId: String,
        /** 0..1 in the camera frame, so a photo taken later still lines up. */
        val x: Float,
        val y: Float,
        /** Stable wall key: rounded coordinates, shared by everyone who tags that wall. */
        val wall: String,
        val lat: Double,
        val lng: Double,
        val author: String,
        val at: Long,
        /** Which plane family ARCore identified when this tag was placed. */
        val surface: Surface = Surface.WALL,
    )

    private const val PREFS = "prism_geotag"
    private const val KEY_STATE = "state_v1"
    private const val SHOP_COUNT = 100

    private val lock = Any()
    private var loaded = false
    private var xp = 320
    private val artworks = LinkedHashMap<String, Artwork>()
    private val tags = LinkedHashMap<String, Tag>()

    private fun prefs() = PrismPlatform.host.prefs(PREFS)

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        seedDefaults()
        val raw = prefs().getString(KEY_STATE, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            xp = root.optInt("xp", xp)
            val art = root.optJSONArray("art") ?: JSONArray()
            for (i in 0 until art.length()) {
                val o = art.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                artworks[id] = Artwork(
                    id = id,
                    title = o.optString("title", ""),
                    artist = o.optString("artist", "You"),
                    seed = o.optLong("seed", id.hashCode().toLong()),
                    price = o.optInt("price", 0),
                    owned = o.optBoolean("owned", false),
                    forSale = o.optBoolean("sale", false),
                    importedPath = o.optString("path", ""),
                )
            }
            val tagArray = root.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until tagArray.length()) {
                val o = tagArray.getJSONObject(i)
                val id = o.optString("id", "")
                if (id.isBlank()) continue
                tags[id] = Tag(
                    id = id,
                    artId = o.optString("art", ""),
                    x = o.optDouble("x", 0.5).toFloat(),
                    y = o.optDouble("y", 0.5).toFloat(),
                    wall = o.optString("wall", ""),
                    lat = o.optDouble("lat", 0.0),
                    lng = o.optDouble("lng", 0.0),
                    author = o.optString("author", "You"),
                    at = o.optLong("at", 0L),
                    surface = Surface.fromKey(o.optString("surface", Surface.WALL.key)),
                )
            }
        }.onFailure { PrismPlatform.log.warn("GeoTag", "Could not read the gallery: ${it.message}") }
    }

    private fun seedDefaults() {
        val palette = listOf("Neon Bloom", "Paper Moon", "Static Garden", "Low Tide", "Signal Fire",
            "Night Swim", "Copper Rain", "Glass Orchard", "Afterimage", "Quiet Voltage")
        for (i in 0 until 6) {
            val id = "starter-$i"
            artworks[id] = Artwork(
                id = id,
                title = palette[i],
                artist = "Prism Studio",
                seed = 1_000L + i * 37L,
                price = 0,
                owned = true,
            )
        }
        for (i in 0 until SHOP_COUNT) {
            val id = "shop-$i"
            val title = palette[i % palette.size] + " " + (i / palette.size + 1)
            val artist = listOf("Mira Voss", "Kite & Co", "Atlas Nine", "Rin Okafor", "Studio Halo")
                .let { it[i % it.size] }
            artworks[id] = Artwork(
                id = id,
                title = title,
                artist = artist,
                seed = 10_000L + i * 1_013L,
                price = 40 + (i * 17) % 460,
                owned = false,
            )
        }
    }

    fun xp(): Int = synchronized(lock) { ensureLoaded(); xp }

    fun library(): List<Artwork> = synchronized(lock) {
        ensureLoaded()
        artworks.values.filter { it.owned }.sortedBy { it.title.lowercase() }
    }

    fun shop(): List<Artwork> = synchronized(lock) {
        ensureLoaded()
        artworks.values.filter { !it.owned || it.forSale }.sortedBy { it.price }
    }

    fun artwork(id: String): Artwork? = synchronized(lock) { ensureLoaded(); artworks[id] }

    /**
     * Learns a piece from another player's tag.
     *
     * The seed travels with the tag, so the other player's picture is drawn here exactly as it is
     * on their device rather than as a hash of its id. Learning is idempotent: hearing about the
     * same piece from two walls does not overwrite the title the owner gave it.
     */
    fun learn(id: String, title: String, artist: String, seed: Long) = synchronized(lock) {
        ensureLoaded()
        if (id.isBlank() || artworks.containsKey(id)) return@synchronized
        artworks[id] = Artwork(
            id = id,
            title = title.ifBlank { "Shared piece" },
            artist = artist.ifBlank { "A neighbour" },
            seed = seed,
            price = 0,
            owned = false,
        )
        saveLocked()
    }

    fun tags(): List<Tag> = synchronized(lock) {
        ensureLoaded()
        tags.values.sortedByDescending { it.at }
    }

    fun places(): List<Tag> = tags()

    fun buy(id: String): String = synchronized(lock) {
        ensureLoaded()
        val art = artworks[id] ?: return "That piece is no longer in the shop."
        if (art.owned) return "You already own ${art.title}."
        if (xp < art.price) return "You need ${art.price - xp} more XP for ${art.title}."
        xp -= art.price
        artworks[id] = art.copy(owned = true, forSale = false)
        saveLocked()
        "Bought ${art.title} for ${art.price} XP."
    }

    /** Puts a player's own artwork on the shop and pays them the asking price when it sells. */
    fun sell(id: String, price: Int = 120): String = synchronized(lock) {
        ensureLoaded()
        val art = artworks[id] ?: return "That piece is not in your library."
        if (!art.owned) return "You do not own that piece."
        if (art.importedPath.isBlank()) return "Only artwork you imported can be sold."
        artworks[id] = art.copy(owned = false, forSale = true, price = price.coerceIn(20, 500))
        saveLocked()
        "Listed ${art.title} for ${price.coerceIn(20, 500)} XP. When another player buys it, you are paid."
    }

    fun importPng(source: File, title: String): Artwork? = synchronized(lock) {
        ensureLoaded()
        val dir = File(PrismPlatform.host.dataDir(), "geotag/imports").apply { mkdirs() }
        val target = File(dir, UUID.randomUUID().toString() + ".png")
        val copied = runCatching { source.copyTo(target, overwrite = true) }.getOrNull() ?: return null
        val art = Artwork(
            id = "import-" + UUID.randomUUID(),
            title = title.ifBlank { "My artwork" },
            artist = "You",
            seed = copied.length(),
            price = 120,
            owned = true,
            importedPath = copied.absolutePath,
        )
        artworks[art.id] = art
        saveLocked()
        art
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        ensureLoaded()
        val art = artworks[id] ?: return false
        if (art.importedPath.isNotBlank()) runCatching { File(art.importedPath).delete() }
        val removed = artworks.remove(id) != null
        tags.values.removeAll { it.artId == id }
        if (removed) saveLocked()
        removed
    }

    /**
     * Places [artId] on an ARCore plane. A new tag on an existing wall replaces whoever was there,
     * which is the one rule that makes this a game rather than a gallery.
     */
    fun place(
        artId: String,
        x: Float,
        y: Float,
        lat: Double,
        lng: Double,
        author: String,
        surface: Surface = Surface.WALL,
    ): Tag? =
        synchronized(lock) {
            ensureLoaded()
            val art = artworks[artId] ?: return null
            if (!art.owned) return null
            val wall = wallKey(lat, lng)
            tags.values.removeAll { it.wall == wall }
            val tag = Tag(
                id = UUID.randomUUID().toString(),
                artId = artId,
                x = x.coerceIn(0.05f, 0.95f),
                y = y.coerceIn(0.05f, 0.95f),
                wall = wall,
                lat = lat,
                lng = lng,
                author = author.ifBlank { "You" },
                at = System.currentTimeMillis(),
                surface = surface,
            )
            tags[tag.id] = tag
            xp += 12
            saveLocked()
            tag
        }

    /** A remote tag, from another player on the mesh. Replaces the local tag on the same wall. */
    fun receive(tag: Tag): Boolean = synchronized(lock) {
        ensureLoaded()
        if (tag.wall.isBlank() || tag.artId.isBlank()) return false
        if (artworks[tag.artId] == null) {
            // A tag from a version that did not send a seed still gets a stable picture, rather
            // than being dropped. GeoTagNet supplies the real seed whenever it is present.
            artworks[tag.artId] = Artwork(
                id = tag.artId,
                title = "Shared piece",
                artist = tag.author,
                seed = tag.artId.hashCode().toLong(),
                price = 0,
                owned = false,
            )
        }
        tags.values.removeAll { it.wall == tag.wall }
        tags[tag.id] = tag
        saveLocked()
        true
    }

    fun wallKey(lat: Double, lng: Double): String =
        String.format(java.util.Locale.US, "%.5f,%.5f", lat, lng)

    fun grantXp(amount: Int) = synchronized(lock) {
        ensureLoaded(); xp = (xp + amount).coerceAtLeast(0); saveLocked()
    }

    private fun saveLocked() {
        runCatching {
            val root = JSONObject().put("xp", xp)
            val art = JSONArray()
            for (a in artworks.values) {
                art.put(
                    JSONObject()
                        .put("id", a.id).put("title", a.title).put("artist", a.artist)
                        .put("seed", a.seed).put("price", a.price).put("owned", a.owned)
                        .put("sale", a.forSale).put("path", a.importedPath)
                )
            }
            val tagArray = JSONArray()
            for (t in tags.values) {
                tagArray.put(
                    JSONObject()
                        .put("id", t.id).put("art", t.artId).put("x", t.x.toDouble())
                        .put("y", t.y.toDouble()).put("wall", t.wall).put("lat", t.lat)
                        .put("lng", t.lng).put("author", t.author).put("at", t.at)
                        .put("surface", t.surface.key)
                )
            }
            root.put("art", art).put("tags", tagArray)
            prefs().edit().putString(KEY_STATE, root.toString()).apply()
        }.onFailure { PrismPlatform.log.warn("GeoTag", "Could not save the gallery: ${it.message}") }
    }
}
