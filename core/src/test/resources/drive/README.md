# Drive test images

Real file system images for `DriveFileSystemTest`, gzipped.

| File | Made by | Source and license |
| --- | --- | --- |
| `fat12.img.gz`, `fat16.img.gz`, `fat32.img.gz` | pyfatfs, by Prism's own script | Generated for Prism: `hello.txt`, `MixedCase.Txt`, `Documents/A long file name with spaces.txt`, `Documents/Deep Folder/pattern.bin` (300 000 bytes, byte *i* = (7*i + 3) mod 251), `many/file-000.txt` .. `file-039.txt` |
| `ext4-view-disk1.img.gz` | mke2fs + Linux, 1 KiB blocks | `test_data/test_disk1.bin` from [ext4-view-rs](https://github.com/nicholasbishop/ext4-view-rs) (MIT / Apache-2.0); contents are created by that repository's `xtask` |
| `ntfs-testfs1.img.gz` | mkntfs + ntfs-3g | `testdata/testfs1` from [ntfs](https://github.com/ColinFinck/ntfs) (MIT / Apache-2.0); see its `create-testfs1.sh` |
| `go-exfat-test.exfat.gz` | — | `test/assets/test.exfat` from [go-exfat](https://github.com/dsoprea/go-exfat) (MIT) |
