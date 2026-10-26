#!/system/bin/sh
APP=/data/user/0/com.prism.launcher
LIB=/data/app/~~SknTWHxL1fU3RGbTwTEmtw==/com.prism.launcher-l2tz5qds3GYysORpnjGuhg==/lib/arm64
export PROOT_TMP_DIR=$APP/cache/proot
export PROOT_LOADER=$LIB/libproot-loader.so
export TMPDIR=$APP/cache
export PRISM_EDITOR_PORT=9999
export PRISM_EDITOR_TOKEN=abc
export PRISM_EDITOR_ASSETS=$APP/files/editor/node
mkdir -p $PROOT_TMP_DIR
$LIB/libproot.so --kill-on-exit -0 -l \
  -r $APP/files/editor/node-rootfs \
  -b /dev -b /proc -b /sys \
  -b $APP/files:$APP/files \
  -b /storage/emulated/0:/storage/emulated/0 \
  -w $APP/files \
  /usr/bin/env HOME=/root USER=root PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin LANG=C.UTF-8 TMPDIR=/tmp \
  /usr/local/bin/node $APP/files/editor/node/node-extension-host.js
