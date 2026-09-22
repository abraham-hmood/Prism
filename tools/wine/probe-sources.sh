#!/usr/bin/env bash
#
# Checks that WineInstaller.DEFAULT_SOURCES still resolves.
#
# Run it when a Windows-layer install starts failing for everybody at once, which is what it looks
# like when a repository prunes a build or moves a path. It resolves each source the way the installer
# does -- reading the repository's own Packages index rather than trusting a filename -- and reports
# what it would download.
#
# Not part of any build. Needs curl, and ar for the .deb inspection.
set -u

UBUNTU_BASE="https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release"
UBUNTU_TARBALL="ubuntu-base-22.04.5-base-arm64.tar.gz"
BOX64_REPO="https://ryanfortner.github.io/box64-debs/debian"
WINE_REPO="https://dl.winehq.org/wine-builds/ubuntu"
WINE_INDEX="$WINE_REPO/dists/jammy/main/binary-amd64/Packages"

status() { curl -sIL -o /dev/null -w '%{http_code}' "$1"; }

# newest VERSION and FILENAME of a package in a Packages index, by the same digit-run ordering
# WineInstaller.compareVersions uses -- sort -V is close enough for a diagnostic.
resolve() {
  local index="$1" package="$2"
  curl -sL "$index" | awk -v p="$package" '
    /^Package: /  { pkg = $2 }
    /^Version: /  { if (pkg == p) ver = $2 }
    /^Filename: / { if (pkg == p && ver != "") { print ver "\t" $2; ver = "" } }
  ' | sort -V | tail -1
}

echo "=== rootfs ==="
echo "  $(status "$UBUNTU_BASE/$UBUNTU_TARBALL")  $UBUNTU_TARBALL"

echo "=== box64 ==="
box64=$(resolve "$BOX64_REPO/Packages" box64)
echo "  version:  $(echo "$box64" | cut -f1)"
echo "  file:     $(echo "$box64" | cut -f2)"
echo "  reaches:  $(status "$BOX64_REPO/$(echo "$box64" | cut -f2 | sed 's#^\./##')")"

echo "=== wine (the launcher and its libraries must be the same version) ==="
for package in wine-stable wine-stable-amd64; do
  entry=$(resolve "$WINE_INDEX" "$package")
  echo "  $package"
  echo "    version: $(echo "$entry" | cut -f1)"
  echo "    reaches: $(status "$WINE_REPO/$(echo "$entry" | cut -f2)")"
done

echo "=== compression of the .deb data members ==="
echo "(the installer unpacks these with the container's own dpkg-deb, so zstd is expected)"
tmp=$(mktemp -d)
curl -sL -r 0-4095 "$BOX64_REPO/$(resolve "$BOX64_REPO/Packages" box64 | cut -f2 | sed 's#^\./##')" \
  -o "$tmp/box64.deb"
echo "  box64: $(ar t "$tmp/box64.deb" 2>/dev/null | tr '\n' ' ')"
rm -rf "$tmp"
