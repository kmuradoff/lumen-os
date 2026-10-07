# SPDX-License-Identifier: GPL-3.0-or-later
# Z9xAirPlay native build (ndk-build, NDK r29). Run through jni/build_native.sh, which
# passes Z9X_ROOT (a space-free symlink to gsi/apps) and Z9X_BUILD (patched source copies).

APP_ABI := arm64-v8a armeabi-v7a
APP_PLATFORM := android-34
APP_STL := c++_static
APP_OPTIM := release
APP_STRIP_MODE := --strip-unneeded

# One module tree, built in parallel; every module is static except libz9xairplay.so.
APP_MODULES := z9xairplay

APP_CFLAGS += -O2 -fvisibility=hidden -ffunction-sections -fdata-sections \
              -fno-omit-frame-pointer -fstack-protector-strong \
              -ffile-prefix-map=$(Z9X_ROOT)=. -ffile-prefix-map=$(NDK_ROOT)=ndk \
              -Wno-deprecated-declarations
APP_CPPFLAGS += -std=c++17 -fno-exceptions -fno-rtti -fvisibility-inlines-hidden
APP_LDFLAGS += -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,relro -Wl,-z,now \
               -Wl,--build-id=sha1 -Wl,--no-undefined -Wl,-z,max-page-size=16384

# NDK r29 already links with 16 KB max-page-size; the explicit flag above keeps that
# true for any older NDK too. APP_SUPPORT_FLEXIBLE_PAGE_SIZES stays at its default.
