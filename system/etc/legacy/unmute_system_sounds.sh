#!/system/bin/sh
# Z9X: unmute STREAM_SYSTEM (UI sounds) after boot. One raise/lower pair on the system stream unmutes
# all streams aliased to music and leaves the music volume where it was (at 100 lower first).
v=$(cmd media_session volume --stream 3 --get 2>/dev/null | grep -oE "volume is [0-9]+" | grep -oE "[0-9]+")
if [ "$v" = "100" ]; then
  cmd media_session volume --stream 1 --adj lower
  cmd media_session volume --stream 1 --adj raise
else
  cmd media_session volume --stream 1 --adj raise
  cmd media_session volume --stream 1 --adj lower
fi
