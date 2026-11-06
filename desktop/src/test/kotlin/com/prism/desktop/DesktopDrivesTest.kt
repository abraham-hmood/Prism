package com.prism.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Finding USB drives in what lsblk and PowerShell print. */
class DesktopDrivesTest {

    @Test
    fun `lsblk - removable partitions are listed, internal disks are not`() {
        // util-linux 2.39 prints booleans; older versions print "1"/"0", which the second disk uses.
        val json = """
            {"blockdevices": [
              {"name":"nvme0n1","path":"/dev/nvme0n1","type":"disk","fstype":null,"label":null,"size":512110190592,"mountpoint":null,"rm":false,"hotplug":false,"tran":"nvme","model":"Samsung SSD",
               "children":[{"name":"nvme0n1p2","path":"/dev/nvme0n1p2","type":"part","fstype":"ext4","label":null,"size":511,"mountpoint":"/","rm":false,"hotplug":false,"tran":null,"model":null}]},
              {"name":"sdb","path":"/dev/sdb","type":"disk","fstype":null,"label":null,"size":"62008590336","mountpoint":null,"rm":"1","hotplug":"1","tran":"usb","model":"Ultra Fit",
               "children":[
                 {"name":"sdb1","path":"/dev/sdb1","type":"part","fstype":"ntfs","label":"BACKUP","size":"32000000000","mountpoint":"/media/dashiel/BACKUP","rm":"1","hotplug":"1","tran":null,"model":null},
                 {"name":"sdb2","path":"/dev/sdb2","type":"part","fstype":"ext4","label":"linuxdata","size":"30000000000","mountpoint":null,"rm":"1","hotplug":"1","tran":null,"model":null}
               ]},
              {"name":"sdc","type":"disk","fstype":"vfat","label":"CAMERA","size":7948206080,"mountpoint":null,"rm":true,"hotplug":true,"tran":"usb","model":"SD Card"}
            ]}
        """.trimIndent()
        val drives = DesktopDrives.parseLsblk(json)
        assertEquals(listOf("/dev/sdb1", "/dev/sdb2", "/dev/sdc"), drives.map { it.device })
        val ntfs = drives[0]
        assertEquals("ntfs", ntfs.fileSystem)
        assertEquals("BACKUP", ntfs.title)
        assertEquals("/dev/sdb", ntfs.disk)
        assertEquals(32_000_000_000L, ntfs.sizeBytes)
        assertEquals("/media/dashiel/BACKUP", ntfs.mountPoint?.path?.replace('\\', '/'))
        assertNull(drives[1].mountPoint)
        assertEquals("ext4", drives[1].fileSystem)
        // A card with no partition table is one drive, named from the disk (no PATH column on old lsblk).
        assertEquals("vfat", drives[2].fileSystem)
        assertEquals("CAMERA", drives[2].title)
    }

    @Test
    fun `Windows - letters, Linux partitions and reserved ones`() {
        val json = """[
            {"disk":5,"model":"SanDisk Ultra","part":1,"letter":"E","size":32000000000,"fs":"NTFS","label":"BACKUP","gpt":"{ebd0a0a2-b9e5-4433-87c0-68b6b72699c7}","mbr":""},
            {"disk":5,"model":"SanDisk Ultra","part":2,"letter":"\u0000","size":30000000000,"fs":"","label":"","gpt":"{0fc63daf-8483-4772-8e79-3d69d8477de4}","mbr":""},
            {"disk":5,"model":"SanDisk Ultra","part":3,"letter":"\u0000","size":16777216,"fs":"","label":"","gpt":"{e3c9e316-0b5c-4db8-817d-f92df00215ae}","mbr":""},
            {"disk":6,"model":"Card Reader","part":1,"letter":"\u0000","size":7948206080,"fs":"FAT32","label":"CAMERA","gpt":"","mbr":"12"}
        ]"""
        val drives = DesktopDrives.parseWindows(json)
        assertEquals(3, drives.size, "the reserved partition is skipped")
        assertEquals("E:\\", drives[0].mountPoint?.path)
        assertFalse(drives[0].needsWsl)
        assertTrue(drives[1].needsWsl, "a Linux file system goes through WSL")
        assertNull(drives[1].mountPoint)
        assertEquals("win:5:2", drives[1].id)
        assertFalse(drives[2].needsWsl, "FAT32 without a letter just needs one assigned")
        // One object rather than an array, which ConvertTo-Json produces for a single row.
        assertEquals(1, DesktopDrives.parseWindows(json.trim().removePrefix("[").substringBefore("},") + "}").size)
        assertTrue(DesktopDrives.parseWindows("").isEmpty())
    }
}
