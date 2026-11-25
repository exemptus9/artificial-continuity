#!/bin/sh
set -eu
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out=${1:-"$src/total-recall-desk_0.12.1_all.deb"}
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT HUP INT TERM
install -d -m 755 "$stage/DEBIAN" "$stage/usr/share/total-recall-listening" "$stage/usr/share/applications" "$stage/usr/share/doc/total-recall-desk" "$stage/usr/bin"
install -d -m 755 "$stage/usr/share/total-recall-listening/web"
install -m 644 "$src/recall_desk.py" "$src/recall_archive.py" "$src/recall_web.py" "$src/archive_backup.py" "$stage/usr/share/total-recall-listening/"
install -m 644 "$src/web/index.html" "$src/web/app.css" "$src/web/app.js" "$stage/usr/share/total-recall-listening/web/"
install -m 644 "$src/total-recall-desk.desktop" "$stage/usr/share/applications/"
install -m 644 "$src/README.md" "$src/CLOUD.md" "$src/CLOUD_RECOVERY.md" "$stage/usr/share/doc/total-recall-desk/"
cat > "$stage/usr/bin/total-recall-desk" <<'LAUNCH'
#!/bin/sh
exec /usr/bin/python3 -I /usr/share/total-recall-listening/recall_desk.py "$@"
LAUNCH
chmod 755 "$stage/usr/bin/total-recall-desk"
cat > "$stage/DEBIAN/control" <<'PKG'
Package: total-recall-desk
Version: 0.12.1
Architecture: all
Maintainer: Total Recall project <maintainer@localhost>
Section: utils
Priority: optional
Depends: python3 (>= 3.11), python3-tk, ffmpeg, openssl, libqrencode4, iproute2, alsa-utils | pulseaudio-utils
Description: Private desktop companion to Total Recall for Android
 Search your original voice captures and optional session journals in a
 private browser archive. Receive resumable phone transfers over paired
 HTTPS and back up original media to a separate drive.
PKG
chmod 755 "$stage"
dpkg-deb --build --root-owner-group "$stage" "$out"
dpkg-deb --info "$out" >/dev/null
dpkg-deb --contents "$out" >/dev/null
