package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.storage.NetworkFieldKind
import com.prism.launcher.storage.NetworkStorageCodec
import com.prism.launcher.storage.NetworkStorageTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the connection catalogue and the settings record.
 *
 * Two failure modes are worth a test each. A duplicate type id makes the picker's "current
 * selection" ambiguous, and a separator inside a password silently splits one saved connection into
 * two broken rows on the next launch -- neither shows up until a user has already lost the entry.
 */
class NetworkStorageTypesTest {

    // ── Catalogue ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `catalogue has at least fifteen transports`() {
        assertTrue(NetworkStorageTypes.all.size >= 15, "only ${NetworkStorageTypes.all.size} types")
    }

    @Test
    fun `the requested transports are all present`() {
        val required = listOf(
            "ftps", "sftp", "smb", "nfs", "iscsi", "cloud", "dfs", "s3", "webdav", "ftp",
        )
        for (id in required) {
            assertNotNull(NetworkStorageTypes.byId(id), "missing transport \"$id\"")
        }
        // NAS, SAN, Cloud Storage, Distributed File Systems and Object Storage are the human names
        // in the picker; each id above is their stable protocol id.
        assertEquals("NAS (SMB/CIFS)", NetworkStorageTypes.byId("smb")?.title)
        assertEquals("SAN (iSCSI)", NetworkStorageTypes.byId("iscsi")?.title)
        assertEquals("Object Storage", NetworkStorageTypes.byId("s3")?.title)
    }

    @Test
    fun `ids are unique and ports are usable`() {
        val ids = NetworkStorageTypes.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "duplicate transport id: $ids")
        for (type in NetworkStorageTypes.all) {
            assertTrue(type.defaultPort in 1..65535, "${type.id} has port ${type.defaultPort}")
            assertTrue(type.title.isNotBlank(), "${type.id} has no title")
            assertTrue(type.summary.isNotBlank(), "${type.id} has no summary")
        }
    }

    @Test
    fun `field keys are unique within a type`() {
        for (type in NetworkStorageTypes.all) {
            val keys = type.fields.map { it.key }
            assertEquals(keys.size, keys.toSet().size, "${type.id} repeats a field key: $keys")
        }
    }

    @Test
    fun `lookup ignores case and surrounding space`() {
        assertEquals("sftp", NetworkStorageTypes.byId(" SFTP ")?.id)
        assertEquals(null, NetworkStorageTypes.byId("gopher"))
        assertEquals("GOPHER", NetworkStorageTypes.titleFor("gopher"))
    }

    // ── Validation ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `name host and port are required`() {
        val ftp = NetworkStorageTypes.byId("ftp")!!
        val problems = NetworkStorageTypes.problems(ftp, "", "", 0, emptyMap())
        assertEquals(3, problems.size, problems.toString())
        assertTrue(problems[0].contains("name"))
        assertTrue(problems[1].contains("host"))
        assertTrue(problems[2].contains("port"))
    }

    @Test
    fun `every required field is reported for every type that has one`() {
        for (type in NetworkStorageTypes.all) {
            val required = type.fields.filter { it.required }
            val problems = NetworkStorageTypes.problems(
                type, "Share", "host.example.com", type.defaultPort, emptyMap(),
            )
            for (field in required) {
                assertTrue(
                    problems.any { it.contains(field.label) },
                    "${type.id} did not report missing ${field.key}: $problems",
                )
            }
            if (required.isEmpty()) {
                assertTrue(problems.isEmpty(), "${type.id} complained unexpectedly: $problems")
            }
        }
    }

    @Test
    fun `a default satisfies a required field`() {
        val dfs = NetworkStorageTypes.byId("dfs")!!
        // namespace has no default and is required, so an empty map must fail...
        assertTrue(NetworkStorageTypes.problems(dfs, "n", "h", 8020, emptyMap()).isNotEmpty())
        // ...and providing it must pass.
        assertTrue(
            NetworkStorageTypes.problems(dfs, "n", "h", 8020, mapOf("namespace" to "hdfs://nn")).isEmpty(),
        )
    }

    // ── Building ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `build drops fields the type does not have`() {
        val sftp = NetworkStorageTypes.byId("sftp")!!
        val storage = NetworkStorageTypes.build(
            type = sftp,
            name = "  Home NAS  ",
            host = " 192.168.1.4 ",
            port = 22,
            username = " me ",
            password = " secret ",
            extras = mapOf("path" to "/media", "bucket" to "leftover"),
        )
        assertEquals("Home NAS", storage.name)
        assertEquals("192.168.1.4", storage.host)
        assertEquals("me", storage.username)
        assertEquals("/media", storage.extras["path"])
        assertEquals(null, storage.extras["bucket"])
    }

    @Test
    fun `build keeps an explicit toggle off`() {
        val ftp = NetworkStorageTypes.byId("ftp")!!
        val storage = NetworkStorageTypes.build(
            ftp, "Share", "host", 21, "u", "p", mapOf("passive" to "false"),
        )
        assertEquals("false", storage.extras["passive"])
    }

    @Test
    fun `build leaves credentials off for a type that has none`() {
        val s3 = NetworkStorageTypes.byId("s3")!!
        val storage = NetworkStorageTypes.build(
            s3, "Backups", "s3.amazonaws.com", 443, "u", "p",
            mapOf("bucket" to "prism"),
        )
        assertEquals("", storage.username)
        assertEquals("", storage.password)
        assertEquals("prism", storage.extras["bucket"])
    }

    @Test
    fun `build falls back to the host for an unnamed connection`() {
        val tftp = NetworkStorageTypes.byId("tftp")!!
        val storage = NetworkStorageTypes.build(tftp, "   ", "192.168.1.9", 69, "", "", emptyMap())
        assertEquals("192.168.1.9", storage.name)
    }

    @Test
    fun `secret fields are marked`() {
        val secretKeys = NetworkStorageTypes.all.flatMap { type ->
            type.fields.filter { it.kind == NetworkFieldKind.SECRET }.map { "${type.id}:${it.key}" }
        }
        assertTrue(secretKeys.contains("s3:secretKey"), secretKeys.toString())
        assertTrue(secretKeys.contains("cloud:token"), secretKeys.toString())
    }

    // ── Extras encoding ───────────────────────────────────────────────────────────────────────

    @Test
    fun `extras survive separators and unicode`() {
        val values = mapOf(
            "path" to "/media/My Films & TV",
            "password" to "a=b::c;;;d%20+e",
            "note" to "café ☕\nsecond line",
            "toggle" to "false",
        )
        assertEquals(values, NetworkStorageTypes.decodeExtras(NetworkStorageTypes.encodeExtras(values)))
    }

    /** The property that keeps one record from being read as two. */
    @Test
    fun `encoded extras contain no record separators`() {
        val encoded = NetworkStorageTypes.encodeExtras(mapOf("k" to "a::b;;;c&d=e"))
        assertTrue(!encoded.contains(";;;"), encoded)
        assertTrue(!encoded.contains("::"), encoded)
        assertTrue(!encoded.contains("\n"), encoded)
    }

    @Test
    fun `decode extras ignores junk`() {
        assertEquals(emptyMap(), NetworkStorageTypes.decodeExtras(""))
        assertEquals(emptyMap(), NetworkStorageTypes.decodeExtras("=novalue"))
        assertEquals(mapOf("a" to "1"), NetworkStorageTypes.decodeExtras("a=1&&b"))
    }

    // ── Settings record ───────────────────────────────────────────────────────────────────────

    private fun storage(
        id: String = "s1",
        name: String = "Share",
        protocol: String = "ftp",
        host: String = "files.example.com",
        port: Int = 21,
        username: String = "me",
        password: String = "pw",
        extras: Map<String, String> = emptyMap(),
    ) = PrismSettings.NetworkStorage(id, name, protocol, host, port, username, password, extras)

    @Test
    fun `record round-trips`() {
        val list = listOf(
            storage(),
            storage(
                id = "s2", name = "Backups :: nightly", protocol = "s3",
                host = "s3.amazonaws.com", port = 443, username = "", password = "",
                extras = mapOf("bucket" to "prism;;;backups", "region" to "eu-west-1"),
            ),
        )
        assertEquals(list, NetworkStorageCodec.decode(NetworkStorageCodec.encode(list)))
    }

    @Test
    fun `record reads the older seven-field format`() {
        val decoded = NetworkStorageCodec.decode("id1::Old share::ftp::10.0.0.2::2121::user::pass")
        assertEquals(1, decoded.size)
        val entry = decoded.first()
        assertEquals("id1", entry.id)
        assertEquals("Old share", entry.name)
        assertEquals("10.0.0.2", entry.host)
        assertEquals(2121, entry.port)
        assertEquals(emptyMap(), entry.extras)
    }

    @Test
    fun `a corrupt port costs one row, not the list`() {
        val decoded = NetworkStorageCodec.decode(
            "a::Bad::ftp::h::not-a-port::u::p;;;b::Good::ftp::h::21::u::p",
        )
        assertEquals(2, decoded.size)
        assertEquals(0, decoded[0].port)
        assertEquals(21, decoded[1].port)
    }

    @Test
    fun `truncated records are skipped`() {
        assertEquals(emptyList(), NetworkStorageCodec.decode("only::four::fields"))
        assertEquals(emptyList(), NetworkStorageCodec.decode(""))
    }

    @Test
    fun `blank ids are replaced rather than colliding`() {
        val decoded = NetworkStorageCodec.decode("::Name::ftp::h::21::u::p")
        assertEquals(1, decoded.size)
        assertTrue(decoded.first().id.isNotBlank())
    }
}
