#!/bin/sh
set -eu
src=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
out=${1:-"$src/total-recall-desk_0.10.0_all.deb"}
stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT HUP INT TERM
install -d -m 755 "$stage/DEBIAN" "$stage/usr/share/total-recall-listening" "$stage/usr/share/applications" "$stage/usr/share/doc/total-recall-desk" "$stage/usr/bin"
install -m 644 "$src/recall_desk.py" "$stage/usr/share/total-recall-listening/"
install -m 644 "$src/total-recall-desk.desktop" "$stage/usr/share/applications/"
install -m 644 "$src/README.md" "$stage/usr/share/doc/total-recall-desk/"
cat > "$stage/usr/bin/total-recall-desk" <<'LAUNCH'
#!/bin/sh
exec /usr/bin/python3 -I /usr/share/total-recall-listening/recall_desk.py "$@"
LAUNCH
chmod 755 "$stage/usr/bin/total-recall-desk"
cat > "$stage/DEBIAN/control" <<'PKG'
Package: total-recall-desk
Version: 0.10.0
Architecture: all
Maintainer: Total Recall project <maintainer@localhost>
Section: utils
Priority: optional
Depends: python3 (>= 3.11), python3-tk, openssl, libqrencode4, iproute2, alsa-utils | pulseaudio-utils
Description: Private desktop companion to Total Recall for Android
 Import, search and play selected voice captures. Optionally pair the
 Galaxy app to this computer over local HTTPS or connect a read-only
 search tool to the Lexidaimon OpenClaw agent.
PKG
chmod 755 "$stage"
dpkg-deb --build --root-owner-group "$stage" "$out"
dpkg-deb --info "$out" >/dev/null
dpkg-deb --contents "$out" >/dev/null
