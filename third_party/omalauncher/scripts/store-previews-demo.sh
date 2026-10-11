#!/usr/bin/env bash
# Runs in the guest, for scripts/store-previews.sh: demo data for the store widgets that
# otherwise only say how to set them up. `gh` answers like a signed-in account, and
# Home Assistant's states are files that curl reads. With "off", puts everything back.
set -u
demo=/tmp/store-previews-demo
ha=~/.config/omarchy-proot/home-assistant

if [ "${1:-}" = off ]; then
  rm -rf "$demo" /usr/local/bin/gh
  [ -f "$ha.before-demo" ] && mv "$ha.before-demo" "$ha" || rm -f "$ha"
  exit 0
fi

mkdir -p "$demo/api/states" /usr/local/bin "${ha%/*}"
cat > /usr/local/bin/gh <<'EOF'
#!/bin/sh
case "$*" in
  auth*) exit 0 ;;
  api*) echo 4 ;;
  *review-requested*) echo 2 ;;
  search*) cat <<'JSON'
[{"title":"Add previews for every widget","url":"https://github.com/","repository":{"name":"omarchy-widgets"},"updatedAt":"2026-10-07T09:00:00Z"},
 {"title":"Store: categories, search and a detail page","url":"https://github.com/","repository":{"name":"omarchy-widgets"},"updatedAt":"2026-10-06T09:00:00Z"},
 {"title":"Fix wrapping of long command lines","url":"https://github.com/","repository":{"name":"omarchy-proot"},"updatedAt":"2026-10-05T09:00:00Z"},
 {"title":"Bump the checker to owl 2","url":"https://github.com/","repository":{"name":"omarchy-widgets"},"updatedAt":"2026-10-04T09:00:00Z"}]
JSON
    ;;
esac
EOF
chmod +x /usr/local/bin/gh

state() { printf '{"entity_id":"%s","state":"%s","attributes":{"friendly_name":"%s","unit_of_measurement":"%s"}}' "$1" "$2" "$3" "${4:-}" > "$demo/api/states/$1"; }
state light.living_room on "Living room"
state light.kitchen off "Kitchen"
state sensor.indoor 21.5 "Indoor" "°C"
state sensor.power 342 "Power use" "W"
state lock.front_door locked "Front door"
state switch.coffee_machine on "Coffee machine"
[ -f "$ha" ] && [ ! -f "$ha.before-demo" ] && mv "$ha" "$ha.before-demo"
printf 'file://%s\ndemo\nlight.living_room\nlight.kitchen\nsensor.indoor\nsensor.power\nlock.front_door\nswitch.coffee_machine\n' "$demo" > "$ha"
