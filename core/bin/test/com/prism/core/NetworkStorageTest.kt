package com.prism.core

import com.prism.launcher.NetworkStorage
import com.prism.launcher.PrismSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the FTP listing parser and URL construction.
 *
 * The parser is the part worth testing: it consumes free-form server output, and its failure mode
 * is a listing full of plausible-looking junk entries rather than an error. A banner line parsed
 * as a filename produces a folder the user can click that cannot exist.
 */
class NetworkStorageTest {

    private fun storage(
        protocol: String = "ftp",
        user: String = "",
        pass: String = "",
    ) = PrismSettings.NetworkStorage(
        id = "s1", name = "Share", protocol = protocol,
        host = "files.example.com", port = 21, username = user, password = pass,
    )

    // ── URL construction ─────────────────────────────────────────────────────────────────────

    @Test
    fun `root url ends in a slash`() {
        assertEquals(
            "ftp://files.example.com:21/;type=d",
            NetworkStorage.urlFor(storage(), "", directory = true),
        )
    }

    @Test
    fun `directory urls carry the type hint`() {
        // Without ;type=d some servers serve a directory as a zero-byte file.
        val url = NetworkStorage.urlFor(storage(), "docs/reports", directory = true)
        assertEquals("ftp://files.example.com:21/docs/reports/;type=d", url)
    }

    @Test
    fun `file urls have no trailing slash or hint`() {
        assertEquals(
            "ftp://files.example.com:21/docs/a.txt",
            NetworkStorage.urlFor(storage(), "docs/a.txt", directory = false),
        )
    }

    /** Credentials must be percent-encoded, or a password with an @ breaks the URL entirely. */
    @Test
    fun `credentials are encoded`() {
        val url = NetworkStorage.urlFor(
            storage(user = "me@work", pass = "p@ss word"), "", directory = true,
        )
        assertTrue(url.startsWith("ftp://me%40work:p%40ss+word@files.example.com:21/"), url)
    }

    @Test
    fun `no credentials means no at sign`() {
        assertFalse(NetworkStorage.urlFor(storage(), "", true).contains("@"))
    }

    // ── Unix listings ────────────────────────────────────────────────────────────────────────

    @Test
    fun `unix directory line parses`() {
        val e = NetworkStorage.parseListingLine("drwxr-xr-x 2 owner group 4096 Jan  2 10:30 reports")
        assertEquals("reports", e?.name)
        assertTrue(e!!.isDirectory)
    }

    @Test
    fun `unix file line parses with size`() {
        val e = NetworkStorage.parseListingLine("-rw-r--r-- 1 owner group 1234 Jan  2 10:30 notes.txt")
        assertEquals("notes.txt", e?.name)
        assertFalse(e!!.isDirectory)
        assertEquals(1234L, e.size)
    }

    /** Names containing spaces must survive -- splitting on whitespace naively truncates them. */
    @Test
    fun `names with spaces survive`() {
        val e = NetworkStorage.parseListingLine(
            "-rw-r--r-- 1 owner group 10 Jan  2 10:30 my important file.txt"
        )
        assertEquals("my important file.txt", e?.name)
    }

    @Test
    fun `symlinks are listed as files`() {
        val e = NetworkStorage.parseListingLine("lrwxrwxrwx 1 o g 7 Jan  2 10:30 link")
        assertEquals("link", e?.name)
        assertFalse(e!!.isDirectory)
    }

    // ── Windows listings ─────────────────────────────────────────────────────────────────────

    @Test
    fun `windows directory line parses`() {
        val e = NetworkStorage.parseListingLine("01-02-24  10:30AM       <DIR>          Reports")
        assertEquals("Reports", e?.name)
        assertTrue(e!!.isDirectory)
    }

    @Test
    fun `windows file line parses`() {
        val e = NetworkStorage.parseListingLine("01-02-24  10:30AM             1234 notes.txt")
        assertEquals("notes.txt", e?.name)
        assertFalse(e!!.isDirectory)
        assertEquals(1234L, e.size)
    }

    // ── Junk rejection ───────────────────────────────────────────────────────────────────────

    /** The property that keeps the listing clean. */
    @Test
    fun `non-listing lines are rejected`() {
        assertNull(NetworkStorage.parseListingLine(""))
        assertNull(NetworkStorage.parseListingLine("   "))
        assertNull(NetworkStorage.parseListingLine("total 48"))
        assertNull(NetworkStorage.parseListingLine("220 Welcome to the FTP service"))
        assertNull(NetworkStorage.parseListingLine("Connected."))
        // A truncated unix line is not half-parsed into a wrong name.
        assertNull(NetworkStorage.parseListingLine("drwxr-xr-x 2 owner"))
    }

    // ── Protocol routing ─────────────────────────────────────────────────────────────────────

    /** Unsupported protocols say so rather than returning an empty listing. */
    @Test
    fun `unsupported protocols report why`() {
        val webdav = NetworkStorage.browse(storage(protocol = "webdav"))
        assertTrue(webdav is NetworkStorage.Result.Unsupported)
        assertTrue((webdav as NetworkStorage.Result.Unsupported).reason.contains("PROPFIND"))

        val p2p = NetworkStorage.browse(storage(protocol = "p2p"))
        assertTrue(p2p is NetworkStorage.Result.Unsupported)

        val nonsense = NetworkStorage.browse(storage(protocol = "gopher"))
        assertTrue(nonsense is NetworkStorage.Result.Unsupported)
    }
}
