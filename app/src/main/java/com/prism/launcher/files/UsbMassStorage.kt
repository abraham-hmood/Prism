package com.prism.launcher.files

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.prism.launcher.PrismLogger
import com.prism.launcher.storage.drive.BlockDevice
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB stick or disk, read directly over the USB host API -- no root, no kernel mount.
 *
 * ## Why
 *
 * Android only shows a USB drive when its volume daemon mounts it, and it mounts FAT and exFAT
 * (some builds NTFS, read-only). An ext4, NTFS, Btrfs or XFS drive on an unrooted phone never
 * becomes a volume. Every USB stick speaks the same protocol underneath, though -- Bulk-Only
 * Transport carrying SCSI commands -- and the USB host API lets an app speak it once the user
 * grants access. That gives Prism the raw sectors, and the readers in
 * com.prism.launcher.storage.drive do the rest.
 *
 * Read-only: only INQUIRY, TEST UNIT READY, READ CAPACITY and READ are ever sent.
 */
class UsbMassStorage private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint,
    override val sizeBytes: Long,
    override val sectorSize: Int,
    /** Vendor and product from INQUIRY, e.g. "SanDisk Ultra". */
    val model: String,
) : BlockDevice {

    private var tag = 1

    @Synchronized
    override fun read(offset: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        if (length <= 0 || offset >= sizeBytes) return out
        val firstLba = offset / sectorSize
        val lastLba = (minOf(offset + length, sizeBytes) - 1) / sectorSize
        var lba = firstLba
        var written = 0
        val skip = (offset - firstLba * sectorSize).toInt()
        var skipLeft = skip
        while (lba <= lastLba) {
            val blocks = minOf(lastLba - lba + 1, MAX_BLOCKS_PER_READ.toLong()).toInt()
            val data = readBlocks(lba, blocks)
            val from = skipLeft
            val count = minOf(data.size - from, length - written)
            System.arraycopy(data, from, out, written, count)
            written += count
            skipLeft = 0
            lba += blocks
        }
        return out
    }

    private fun readBlocks(lba: Long, blocks: Int): ByteArray {
        val bytes = blocks * sectorSize
        val cb = if (lba + blocks <= 0xFFFFFFFFL) {
            ByteArray(10).also { c ->
                c[0] = 0x28 // READ(10)
                putBe32(c, 2, lba)
                c[7] = (blocks shr 8).toByte(); c[8] = blocks.toByte()
            }
        } else {
            ByteArray(16).also { c ->
                c[0] = 0x88.toByte() // READ(16)
                putBe32(c, 2, lba ushr 32); putBe32(c, 6, lba)
                putBe32(c, 10, blocks.toLong())
            }
        }
        val data = ByteArray(bytes)
        if (!transfer(cb, data, attempts = 3)) throw IOException("The drive did not return sectors at $lba")
        return data
    }

    /** One Bulk-Only command: CBW out, data in, CSW in. Retries after clearing a stalled endpoint. */
    private fun transfer(cb: ByteArray, dataIn: ByteArray?, attempts: Int = 2): Boolean {
        repeat(attempts) { attempt ->
            if (runCommand(cb, dataIn)) return true
            PrismLogger.logWarning(TAG, "SCSI 0x%02X failed (attempt %d); clearing the endpoints".format(cb[0], attempt + 1))
            clearHalt(bulkIn); clearHalt(bulkOut)
        }
        return false
    }

    private fun runCommand(cb: ByteArray, dataIn: ByteArray?): Boolean {
        val length = dataIn?.size ?: 0
        val cbw = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x43425355) // "USBC"
            putInt(tag++)
            putInt(length)
            put(if (dataIn != null) 0x80.toByte() else 0)
            put(0) // LUN 0
            put(cb.size.toByte())
            put(cb.copyOf(16))
        }.array()
        if (connection.bulkTransfer(bulkOut, cbw, cbw.size, TIMEOUT_MS) != cbw.size) return false
        if (dataIn != null && length > 0) {
            var done = 0
            while (done < length) {
                val chunk = minOf(MAX_TRANSFER, length - done)
                // The offset overload arrived in Android 9; earlier releases read into a buffer.
                val got = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    connection.bulkTransfer(bulkIn, dataIn, done, chunk, TIMEOUT_MS)
                } else {
                    val buffer = ByteArray(chunk)
                    connection.bulkTransfer(bulkIn, buffer, chunk, TIMEOUT_MS).also { n ->
                        if (n > 0) System.arraycopy(buffer, 0, dataIn, done, n)
                    }
                }
                if (got <= 0) return readStatus() && done == length
                done += got
            }
        }
        return readStatus()
    }

    private fun readStatus(): Boolean {
        val csw = ByteArray(13)
        val got = connection.bulkTransfer(bulkIn, csw, csw.size, TIMEOUT_MS)
        if (got != 13) return false
        val signature = ByteBuffer.wrap(csw, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return signature == 0x53425355 && csw[12].toInt() == 0
    }

    private fun clearHalt(endpoint: UsbEndpoint) {
        // CLEAR_FEATURE(ENDPOINT_HALT) on the endpoint.
        runCatching { connection.controlTransfer(0x02, 0x01, 0, endpoint.address, null, 0, TIMEOUT_MS) }
    }

    override fun close() {
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
    }

    companion object {
        private const val TAG = "PrismUsbDrive"
        private const val TIMEOUT_MS = 5_000

        /** One bulk transfer at a time stays under every Android version's limit. */
        private const val MAX_TRANSFER = 16 * 1024

        /** 64 KiB per READ at 512-byte sectors: big enough to be fast, small enough to be safe. */
        private const val MAX_BLOCKS_PER_READ = 128

        /** The interface a USB stick presents: mass storage, SCSI, bulk-only. */
        fun massStorageInterface(device: UsbDevice): UsbInterface? =
            (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                    it.interfaceSubclass == 6 && it.interfaceProtocol == 0x50
            }

        /**
         * Opens [device], which the user must already have granted. Claiming the interface takes it
         * from the kernel: if Android had mounted part of this drive, that mount goes away.
         */
        fun open(manager: UsbManager, device: UsbDevice): UsbMassStorage {
            val usbInterface = massStorageInterface(device) ?: throw IOException("Not a USB storage device")
            var bulkIn: UsbEndpoint? = null
            var bulkOut: UsbEndpoint? = null
            for (i in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(i)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) bulkIn = endpoint else bulkOut = endpoint
            }
            if (bulkIn == null || bulkOut == null) throw IOException("The drive has no bulk endpoints")
            val connection = manager.openDevice(device) ?: throw IOException("Android would not open the drive")
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                throw IOException("Another app or Android itself is holding the drive")
            }
            val probe = UsbMassStorage(connection, usbInterface, bulkIn, bulkOut, 0, 512, "")
            try {
                val inquiry = ByteArray(36)
                probe.transfer(byteArrayOf(0x12, 0, 0, 0, 36, 0), inquiry)
                val model = (String(inquiry, 8, 8, Charsets.US_ASCII).trim() + " " +
                    String(inquiry, 16, 16, Charsets.US_ASCII).trim()).trim()
                // A drive that is still spinning up answers NOT READY a few times first.
                repeat(5) {
                    if (probe.transfer(ByteArray(6), null, attempts = 1)) return@repeat
                    Thread.sleep(300)
                }
                val capacity = ByteArray(8)
                if (!probe.transfer(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), capacity)) {
                    throw IOException("The drive would not report its size")
                }
                var lastLba = ByteBuffer.wrap(capacity, 0, 4).int.toLong() and 0xFFFFFFFFL
                var blockSize = ByteBuffer.wrap(capacity, 4, 4).int
                if (lastLba == 0xFFFFFFFFL) {
                    // Over 2 TiB: READ CAPACITY(16).
                    val big = ByteArray(32)
                    val cb = ByteArray(16).also { it[0] = 0x9E.toByte(); it[1] = 0x10; it[13] = 32 }
                    if (probe.transfer(cb, big)) {
                        lastLba = ByteBuffer.wrap(big, 0, 8).long
                        blockSize = ByteBuffer.wrap(big, 8, 4).int
                    }
                }
                if (blockSize !in setOf(512, 1024, 2048, 4096)) throw IOException("Unusual sector size $blockSize")
                PrismLogger.logInfo(TAG, "Opened $model: ${(lastLba + 1) * blockSize} bytes, $blockSize-byte sectors")
                return UsbMassStorage(connection, usbInterface, bulkIn, bulkOut, (lastLba + 1) * blockSize, blockSize, model)
            } catch (e: Exception) {
                probe.close()
                throw e
            }
        }

        private fun putBe32(b: ByteArray, at: Int, value: Long) {
            b[at] = (value shr 24).toByte(); b[at + 1] = (value shr 16).toByte()
            b[at + 2] = (value shr 8).toByte(); b[at + 3] = value.toByte()
        }
    }
}
