package com.prism.core

import com.prism.launcher.storage.BridgedStorage
import com.prism.launcher.storage.BridgedStorageCodec
import com.prism.launcher.storage.HostedStorage
import com.prism.launcher.storage.HostedStorageCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hosted and bridged storage are persisted JSON. A restart must not change a share's identity,
 * credentials or bridge owner, so the codec is tested without a device.
 */
class HostedStorageTest {

    @Test
    fun `hosted folder round trips with protocol fields`() {
        val original = HostedStorage(
            id = "share-1",
            name = "Family photos",
            typeId = "webdav",
            folderPath = "/sdcard/DCIM",
            port = 443,
            username = "bremo",
            password = "secret",
            extras = mapOf("basePath" to "/remote.php/dav/files/bremo", "trustSelfSigned" to "true"),
            sharedWithTrusted = false,
        )

        val decoded = HostedStorageCodec.decode(HostedStorageCodec.encode(listOf(original))).single()
        assertEquals(original, decoded)
        assertFalse(decoded.sharedWithTrusted)
        assertEquals("WebDAV", decoded.protocolTitle)
    }

    @Test
    fun `bridged folder keeps its owner and current address`() {
        val share = HostedStorage(
            id = "share-2",
            name = "Backups",
            typeId = "sftp",
            folderPath = "/home/bremo/backups",
            port = 22,
        )
        val original = BridgedStorage(
            ownerFingerprint = "aabbccdd",
            ownerName = "Bremo's desktop",
            host = "10.8.0.9",
            share = share,
        )

        val decoded = BridgedStorageCodec.decode(BridgedStorageCodec.encode(listOf(original))).single()
        assertEquals("aabbccdd", decoded.ownerFingerprint)
        assertEquals("10.8.0.9", decoded.host)
        assertEquals(share, decoded.share)
        assertTrue(decoded.subtitle.contains("Bremo's desktop"))
    }

    @Test
    fun `malformed records are skipped rather than taking the list down`() {
        assertTrue(HostedStorageCodec.decode("not json").isEmpty())
        assertTrue(BridgedStorageCodec.decode("[{\"owner\":\"x\"}]").isEmpty())
    }
}
