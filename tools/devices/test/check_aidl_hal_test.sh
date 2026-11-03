#!/bin/bash
# check_aidl_hal_test.sh: tests of tools/devices/check_aidl_hal.py on the HB apps.
#
#   bash tools/devices/test/check_aidl_hal_test.sh
#
# Part 1, apps/HbBoot (while it exists; removed after the first HbProjector boot proof),
# checked against its own six-row allowlist written into the scratch dir. Part 2, the app named by hal_app of the
# Z9X HB (apps/HbProjector, assembled from devices/goat_elegant/app/HbProjector.layers when that recipe exists)
# with the registry's allowlist.
#
# Part 1 copies HbBoot into a scratch dir: the clean copy must pass, and each broken copy must fail: a banned call
# (DataBaseManager updateDbInt), a wrong code for getBootAnimState, a second '.transact(' in BootSequence.java, a raw
# integer transact code, a reflection bypass (Class.forName + getMethod + invoke on ServiceManager outside the
# transport), a call on a REVIEW row (DisplayManager uiHeartBeatDo), a call that is neither banned nor allowlisted
# (DisplayManager getProjectorPutMode), an allowlist row that is no call, and a run without --allowed (fail-closed:
# usage, exit 2).
# Part 2: the clean HbProjector copy must pass ('OK: N calls on M interfaces', N and M counted from the registry's
# allowed.tsv: the rows and their distinct interfaces, never a fixed number) and each broken copy must fail: a Call of
# DataBaseManager 2 updateDbInt (BANNED), a Call of a REVIEW row (DisplayManager 56 generateLogoByTrapezoid, even with an
# allowlist row; newAutoKst left REVIEW on 2026-10-29, 9.1), a wrong code (getBootAnimState 34), a second '.transact(' in GmpfClient.java, Parcel used in
# KstPoint.java, a raw integer transact code, an integer code passed to a typed helper, a Call that no allowlist row
# names (DisplayManager 10 isVideoPlaying), an allowlist row that is no Call, and a run without --allowed.
# Dex level (rule 7), both parts: the clean copy is compiled in the scratch dir with a throwaway key
# (apps/build_apk.sh) and must pass; a fake dexdump then adds one forbidden instruction to the real dump each time
# (Method.invoke, a second IBinder.transact, a const-string "ServiceManager" outside the transport class) and
# finally fails itself: each must fail.
# The tree is only read.
set -u
G=$(cd "$(dirname "$0")/../../.." && pwd)
HAL_APP=$G/$(python3 -B "$G/tools/devices/devices.py" get goat_elegant hal_app) || exit 2
HAL_ALLOWED=$G/$(python3 -B "$G/tools/devices/devices.py" get goat_elegant hal_allowed) || exit 2
R=$G/devices/goat_elegant/research
W=$(mktemp -d "${TMPDIR:-/tmp}/aidl_test.XXXXXX")
trap 'rm -rf "$W"' EXIT
PASS=0 FAIL=0
ANDROID_SDK=${ANDROID_SDK:-$HOME/Library/Android/sdk}
REAL_DEXDUMP=${BUILD_TOOLS:-$ANDROID_SDK/build-tools/36.0.0}/dexdump

copy() { rm -rf "$W/app"; mkdir -p "$W/app"; cp -R "$APP/src" "$W/app/src"; cp "$ALLOWED" "$W/allowed.tsv"; }
run() { # extra args...
  python3 -B "$G/tools/devices/check_aidl_hal.py" --app "$W/app" --codes "$R/aidl_codes.tsv" --banned "$R/banned.tsv" \
    "$@" > "$W/out" 2>&1
}
result() { # name want got
  if [ "$3" = "$2" ]; then PASS=$((PASS + 1)); echo "ok   $1: $(tail -1 "$W/out")"
  else FAIL=$((FAIL + 1)); echo "FAIL $1 (exit $3, want $2)"; sed 's/^/     /' "$W/out"; fi
}
expect() { # name want(0|1|2) [extra args...]
  n=$1 want=$2; shift 2
  run --allowed "$W/allowed.tsv" "$@"; result "$n" "$want" $?
}
reported() { # name pattern: the last run printed a line matching pattern
  grep -q -- "$2" "$W/out" || { FAIL=$((FAIL + 1)); echo "FAIL $1: no line matches '$2'"; }
}
edit() { # file python-replace old new (exactly one occurrence)
  python3 - "$W/app/$1" "$2" "$3" <<'PYEOF' || { echo "FAIL edit $1"; exit 1; }
import sys
p, old, new = sys.argv[1:]
s = open(p).read()
assert s.count(old) == 1, (p, old)
open(p, "w").write(s.replace(old, new))
PYEOF
}
key() { # a throwaway key pair for the dex builds, made once
  [ -f "$W/key/k.pk8" ] && return 0
  mkdir -p "$W/key"
  (umask 077
   openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=check_aidl_hal_test throwaway" \
     -keyout "$W/key/k.pem" -out "$W/key/k.x509.pem" 2>/dev/null &&
   openssl pkcs8 -topk8 -inform PEM -outform DER -nocrypt -in "$W/key/k.pem" -out "$W/key/k.pk8")
}
fake() { # name want pattern line: a dexdump that adds LINE after the first method header matching pattern
  cat > "$W/dexdump" <<DEXEOF
#!/bin/sh
"$REAL_DEXDUMP" "\$@" | awk -v pat='$3' -v add='$4' '{ print } !done && index(\$0, pat) { print add; done = 1 }'
DEXEOF
  chmod +x "$W/dexdump"
  DEXDUMP=$W/dexdump run --allowed "$W/allowed.tsv" --dex "$W/app.apk"; result "$1" "$2" $?
}
dex_suite() { # prefix build-dir method-of-a-client app-oncreate: the four rule 7 cases on $W/app.apk
  fake "$1dex_reflect_invoke" 1 "] $3:(" \
    '00aaaa: 6e30 0000 0000 |0000: invoke-virtual {v0, v1, v2}, Ljava/lang/reflect/Method;.invoke:(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object; // method@0000'
  reported "$1dex_reflect_invoke" "rule 7: $3 invokes java.lang.reflect.Method.invoke"
  fake "$1dex_second_binder_transact" 1 "] $3:(" \
    '00aaaa: 7256 0000 0000 |0000: invoke-interface {v1, v4, v2, v3, v6}, Landroid/os/IBinder;.transact:(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z // method@000c'
  reported "$1dex_second_binder_transact" "rule 7: $3 invokes Landroid/os/IBinder;.transact"
  fake "$1dex_service_manager_string" 1 "] $4:(" \
    '00aaaa: 1a00 0000 |0000: const-string v0, "android.os.ServiceManager" // string@0000'
  reported "$1dex_service_manager_string" "rule 7: $4: const-string 'android.os.ServiceManager'"
  sed -i.bak 's#^"[^"]*" "\$@"#exit 3; &#' "$W/dexdump"
  DEXDUMP=$W/dexdump run --allowed "$W/allowed.tsv" --dex "$W/app.apk"; result "$1dex_tool_fails" 1 $?
  reported "$1dex_tool_fails" 'rule 7: dexdump failed'
}
build() { # build-dir: compile $W/app with the throwaway key into $W/app.apk
  key
  KEY_PK8=$W/key/k.pk8 KEY_PEM=$W/key/k.x509.pem BUILD_DIR=$W/$1 sh "$G/apps/build_apk.sh" "$W/app" "$W/app.apk" \
    > "$W/build.log" 2>&1
}

# ------------------------------------------------------------------ part 1: apps/HbBoot
APP=$G/apps/HbBoot
ALLOWED=$W/hbboot_allowed.tsv
J=src/org/lumen/hb/boot
display() { # add IDisplayManager to enum Iface and one call on it: NAME CODE METHOD
  edit $J/HbGmpf.java 'SYSTEM("xgimi.hardware.gmpf.ISystemManager");' \
    'SYSTEM("xgimi.hardware.gmpf.ISystemManager"), DISPLAY("xgimi.hardware.gmpf.IDisplayManager");'
  edit $J/HbGmpf.java 'SET_SYSTEM_UI_STATE(Iface.SYSTEM, 36, "setSystemUIState");' \
    "SET_SYSTEM_UI_STATE(Iface.SYSTEM, 36, \"setSystemUIState\"), $1(Iface.DISPLAY, $2, \"$3\");"
}
if [ -d "$APP/src" ]; then
  echo "part 1: apps/HbBoot"
  printf '%s\t%s\t%s\t%s\n' '# HbBoot allowlist, scratch copy of its last registry rows' '' '' '' \
    - 0x00FFFFFF getInterfaceVersion guard - 0x00FFFFFE getInterfaceHash guard \
    PowerManager 2 goToBootCompleted 'rows C and E' SystemManager 32 setBootAnimState 'rows A and D' \
    SystemManager 33 getBootAnimState read SystemManager 36 setSystemUIState 'row B' > "$ALLOWED"

  copy
  expect clean 0

  copy
  edit $J/HbGmpf.java 'SYSTEM("xgimi.hardware.gmpf.ISystemManager");' \
    'SYSTEM("xgimi.hardware.gmpf.ISystemManager"), DB("xgimi.hardware.gmpf.IDataBaseManager");'
  edit $J/HbGmpf.java 'SET_SYSTEM_UI_STATE(Iface.SYSTEM, 36, "setSystemUIState");' \
    'SET_SYSTEM_UI_STATE(Iface.SYSTEM, 36, "setSystemUIState"), UPDATE_DB_INT(Iface.DB, 2, "updateDbInt");'
  expect banned_call 1
  reported banned_call 'rule 4: Call.UPDATE_DB_INT.* is banned'

  copy
  edit $J/HbGmpf.java 'GET_BOOT_ANIM_STATE(Iface.SYSTEM, 33, "getBootAnimState")' 'GET_BOOT_ANIM_STATE(Iface.SYSTEM, 34, "getBootAnimState")'
  expect wrong_code 1
  reported wrong_code 'rule 3: Call.GET_BOOT_ANIM_STATE'

  copy
  edit $J/BootSequence.java 'private final HbGmpf gmpf = new HbGmpf();' \
    'private final HbGmpf gmpf = new HbGmpf();
    static void poke(android.os.IBinder b) throws android.os.RemoteException { b.transact(1, null, null, 0); }'
  expect second_transact 1
  reported second_transact 'rule 1:'

  copy
  edit $J/HbGmpf.java 'b.transact(c.code, data, reply, 0)' 'b.transact(33, data, reply, 0)'
  expect raw_integer_code 1
  reported raw_integer_code 'rule 1: .*found .33.'

  copy
  edit $J/BootSequence.java 'private final HbGmpf gmpf = new HbGmpf();' \
    'private final HbGmpf gmpf = new HbGmpf();
    static Object peek() throws Exception {
        return Class.forName("android.os.Service" + "Manager").getMethod("check" + "Service", String.class)
                .invoke(null, "x");
    }'
  expect reflection_bypass 1
  reported reflection_bypass 'rule 1: .*BootSequence.java:.*Class.forName( outside'
  reported reflection_bypass 'rule 1: .*BootSequence.java:.*getMethod( outside'
  reported reflection_bypass 'rule 1: .*BootSequence.java:.*\.invoke( outside'

  copy
  display UI_HEART_BEAT_DO 100 uiHeartBeatDo
  expect review_row_call 1
  reported review_row_call 'rule 4: Call.UI_HEART_BEAT_DO.* is under review'

  copy
  display GET_PUT_MODE 3 getProjectorPutMode
  expect not_allowlisted 1
  reported not_allowlisted 'rule 6: Call.GET_PUT_MODE: DisplayManager 3 getProjectorPutMode is not a row'
  if grep -q 'rule [1-5]:' "$W/out"; then FAIL=$((FAIL + 1)); echo "FAIL not_allowlisted: refused by another rule too"; fi

  copy
  printf 'SystemManager\t33\tgetBootAnimState\ta second copy\nPowerManager\t11\tgetBootReason\tnot a call\n' >> "$W/allowed.tsv"
  expect stale_allowlist_row 1
  reported stale_allowlist_row 'rule 6: .*PowerManager 11 getBootReason is no Call'

  copy
  run; result no_allowed_option 2 $?

  copy
  cp "$APP/AndroidManifest.xml" "$W/app/"
  if build build1; then
    expect dex_clean 0 --dex "$W/app.apk"
    reported dex_clean 'OK: 6 calls'
    dex_suite "" build1 org.lumen.hb.boot.BootSequence.run org.lumen.hb.boot.App.onCreate
  else
    FAIL=$((FAIL + 1)); echo "FAIL dex_build: the scratch copy did not compile"; sed 's/^/     /' "$W/build.log" | tail -20
  fi
else
  echo "part 1: apps/HbBoot is gone, skipped"
fi

# ------------------------------------------------------------------ part 2: the hal_app (apps/HbProjector)
APP=$HAL_APP
# an app built from a recipe: the test runs on its assembled tree, as the build
if [ -n "$(ls "$G"/devices/goat_elegant/app/*.layers 2>/dev/null)" ]; then
  python3 -B "$G/tools/devices/assemble_app.py" --device goat_elegant --app "$(basename "$HAL_APP")" --out "$W/hal_app" --quiet \
    || { echo "FAIL p2: the recipe of ${HAL_APP#$G/} does not assemble"; exit 1; }
  APP=$W/hal_app
fi
ALLOWED=$HAL_ALLOWED
H=src/org/lumen/projector/hal
LAST='GET_SYSTEM_TEMPERATURE(Iface.TEMPERATURE, 6, "getSystemTemperature");'
LAST_OPEN='GET_SYSTEM_TEMPERATURE(Iface.TEMPERATURE, 6, "getSystemTemperature"),'
echo "part 2: ${HAL_APP#$G/}"
# the expected summary, from the allowlist itself: rows (comments and blank lines skipped) and distinct interfaces
# ('-' marks a meta call, no interface of its own)
N_CALLS=$(grep -v '^#' "$ALLOWED" | grep -c .)
N_IFACES=$(grep -v '^#' "$ALLOWED" | grep . | cut -f1 | grep -vx -- '-' | sort -u | grep -c .)
WANT_OK="OK: $N_CALLS calls on $N_IFACES interfaces"

copy
expect p2_clean 0
reported p2_clean "$WANT_OK"

copy
edit $H/AidlCaller.java 'FACTORY("xgimi.hardware.gmpf.IGmFactoryManager");' \
  'FACTORY("xgimi.hardware.gmpf.IGmFactoryManager"), DB("xgimi.hardware.gmpf.IDataBaseManager");'
edit $H/AidlCaller.java "$LAST" "$LAST_OPEN UPDATE_DB_INT(Iface.DB, 2, \"updateDbInt\");"
expect p2_banned_call 1
reported p2_banned_call 'rule 4: Call.UPDATE_DB_INT: DataBaseManager 2 updateDbInt is banned'

copy
edit $H/AidlCaller.java "$LAST" "$LAST_OPEN GENERATE_LOGO_BY_TRAPEZOID(Iface.DISPLAY, 56, \"generateLogoByTrapezoid\");"
printf 'DisplayManager\t56\tgenerateLogoByTrapezoid\tapproved by nobody\n' >> "$W/allowed.tsv"
expect p2_review_row_call 1
reported p2_review_row_call 'rule 4: Call.GENERATE_LOGO_BY_TRAPEZOID: DisplayManager 56 generateLogoByTrapezoid is under review'

copy
edit $H/AidlCaller.java 'GET_BOOT_ANIM_STATE(Iface.SYSTEM, 33, "getBootAnimState")' 'GET_BOOT_ANIM_STATE(Iface.SYSTEM, 34, "getBootAnimState")'
expect p2_wrong_code 1
reported p2_wrong_code 'rule 3: Call.GET_BOOT_ANIM_STATE'

copy
edit $H/GmpfClient.java 'public final class GmpfClient extends AidlCaller {' \
  'public final class GmpfClient extends AidlCaller {
    static void poke(android.os.IBinder b) throws android.os.RemoteException { b.transact(1, null, null, 0); }'
expect p2_second_transact 1
reported p2_second_transact "rule 1: '.transact(' must be in exactly one file"

copy
edit $H/KstPoint.java 'public final class KstPoint {' \
  'public final class KstPoint {
    static void poke(android.os.Parcel p) { p.writeInt(1); }'
expect p2_parcel_in_kstpoint 1
reported p2_parcel_in_kstpoint 'rule 1: src/org/lumen/projector/hal/KstPoint.java:.*: Parcel outside the transport file'

copy
edit $H/AidlCaller.java 'b.transact(c.code, data, reply, 0)' 'b.transact(33, data, reply, 0)'
expect p2_raw_integer_code 1
reported p2_raw_integer_code 'rule 1: .*found .33.'

copy
edit $H/GmpfClient.java 'callI32(Call.SET_SYSTEM_UI_STATE, SYSTEM_UI_STATE_READY)' 'callI32(36, SYSTEM_UI_STATE_READY)'
expect p2_helper_integer_code 1
reported p2_helper_integer_code 'rule 5: src/org/lumen/projector/hal/GmpfClient.java:.*callI32(36, SYSTEM_UI_STATE_READY) passes no Call'

copy
edit $H/AidlCaller.java "$LAST" "$LAST_OPEN IS_VIDEO_PLAYING(Iface.DISPLAY, 10, \"isVideoPlaying\");"
expect p2_not_allowlisted 1
reported p2_not_allowlisted 'rule 6: Call.IS_VIDEO_PLAYING: DisplayManager 10 isVideoPlaying is not a row'
if grep -q 'rule [1-5]:' "$W/out"; then FAIL=$((FAIL + 1)); echo "FAIL p2_not_allowlisted: refused by another rule too"; fi

copy
printf 'DisplayManager\t10\tisVideoPlaying\tnot a call\n' >> "$W/allowed.tsv"
expect p2_stale_allowlist_row 1
reported p2_stale_allowlist_row 'rule 6: .*DisplayManager 10 isVideoPlaying is no Call'

copy
run; result p2_no_allowed_option 2 $?

copy
cp "$APP/AndroidManifest.xml" "$W/app/"
cp -R "$APP/res" "$W/app/res"
if build build2; then
  expect p2_dex_clean 0 --dex "$W/app.apk"
  reported p2_dex_clean "$WANT_OK"
  dex_suite p2_ build2 org.lumen.projector.hal.GmpfClient.getHdrType org.lumen.projector.App.onCreate
else
  FAIL=$((FAIL + 1)); echo "FAIL p2_dex_build: the scratch copy did not compile"; sed 's/^/     /' "$W/build.log" | tail -20
fi

echo "check_aidl_hal_test: $PASS passed, $FAIL failed"
[ $FAIL = 0 ]
