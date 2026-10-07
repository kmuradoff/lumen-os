# SPDX-License-Identifier: GPL-3.0-or-later
# Z9xAirPlay native modules. Driven by jni/build_native.sh, which defines:
#   Z9X_ROOT   space-free symlink to gsi/apps (GNU make cannot handle "XGIMI PLAY 6")
#   Z9X_BUILD  $(Z9X_ROOT)/Z9xAirPlay/build/native (patched copies of UxPlay lib/ and ALAC)
# third_party/ trees are only read, never modified.

LOCAL_PATH := $(call my-dir)

ifeq ($(Z9X_ROOT),)
$(error Z9X_ROOT is not set: run jni/build_native.sh)
endif
ifeq ($(Z9X_BUILD),)
$(error Z9X_BUILD is not set: run jni/build_native.sh)
endif

TP    := $(Z9X_ROOT)/third_party
BSSL  := $(TP)/boringssl
PLIST := $(TP)/libplist
UXLIB := $(Z9X_BUILD)/uxplay-lib
ALAC  := $(Z9X_BUILD)/alac-codec
GLUE  := $(LOCAL_PATH)/glue

# ---------------------------------------------------------------------------------------
# 1. z9x_crypto: BoringSSL libcrypto (Apache-2.0), static, from its pre-generated lists.
include $(BSSL)/gen/sources.mk

include $(CLEAR_VARS)
LOCAL_MODULE := z9x_crypto
Z9X_BSSL_ASM := $(filter-out %-apple.S %-win.S,$(boringssl_bcm_sources_asm) $(boringssl_crypto_sources_asm))
LOCAL_SRC_FILES := $(addprefix $(BSSL)/,$(boringssl_bcm_sources) $(boringssl_crypto_sources) $(Z9X_BSSL_ASM))
LOCAL_C_INCLUDES := $(BSSL)/include
LOCAL_EXPORT_C_INCLUDES := $(BSSL)/include
LOCAL_CFLAGS := -DBORINGSSL_IMPLEMENTATION -DOPENSSL_SMALL -Wno-unused-parameter
LOCAL_CPPFLAGS := -std=c++17
LOCAL_ASFLAGS := -DBORINGSSL_IMPLEMENTATION
include $(BUILD_STATIC_LIBRARY)

# ---------------------------------------------------------------------------------------
# 2. z9x_plist: libplist 2.8.0 (LGPL-2.1), C part only.
include $(CLEAR_VARS)
LOCAL_MODULE := z9x_plist
LOCAL_SRC_FILES := \
    $(addprefix $(PLIST)/src/, base64.c bplist.c bytearray.c common.c hashtable.c jplist.c \
        jsmn.c oplist.c out-default.c out-limd.c out-plutil.c plist.c ptrarray.c time64.c \
        xplist.c) \
    $(addprefix $(PLIST)/libcnary/, node.c node_list.c)
LOCAL_C_INCLUDES := $(PLIST)/include $(PLIST)/src $(PLIST)/libcnary/include
LOCAL_EXPORT_C_INCLUDES := $(PLIST)/include
LOCAL_CFLAGS := -std=gnu11 -D_GNU_SOURCE -DHAVE_STRNDUP -DHAVE_MEMMEM -DHAVE_STRPTIME \
    -DHAVE_TM_TM_GMTOFF -DHAVE_TM_TM_ZONE -DHAVE_LOCALTIME_R -DPACKAGE_VERSION=\"2.8.0\" \
    -Wno-unused-parameter -Wno-sign-compare
include $(BUILD_STATIC_LIBRARY)

# ---------------------------------------------------------------------------------------
# 3. z9x_alac: Apple ALAC reference decoder (Apache-2.0), decoder files only (patched copy).
include $(CLEAR_VARS)
LOCAL_MODULE := z9x_alac
LOCAL_SRC_FILES := $(addprefix $(ALAC)/, ALACDecoder.cpp ALACBitUtilities.c ag_dec.c dp_dec.c \
    matrix_dec.c EndianPortable.c)
LOCAL_C_INCLUDES := $(ALAC)
LOCAL_EXPORT_C_INCLUDES := $(ALAC)
LOCAL_CFLAGS := -DTARGET_RT_LITTLE_ENDIAN=1 -Wno-unused-variable -Wno-unused-but-set-variable \
    -Wno-unused-parameter
include $(BUILD_STATIC_LIBRARY)

# ---------------------------------------------------------------------------------------
# 4. z9x_uxplay: UxPlay protocol library (GPL-3.0; lib/ LGPL-2.1+ origins), llhttp (MIT),
#    playfair (GPL-3.0). lib/dns_sd and lib/mdnsd are NOT built: mDNS goes through Java
#    NsdManager via glue/dnssd_nsd.c.
include $(CLEAR_VARS)
LOCAL_MODULE := z9x_uxplay
LOCAL_SRC_FILES := \
    $(addprefix $(UXLIB)/, airplay_video.c byteutils.c compat.c crypto.c dnssd.c \
        fairplay_playfair.c http_request.c http_response.c httpd.c logger.c mirror_buffer.c \
        netutils.c pairing.c raop.c raop_buffer.c raop_ntp.c raop_rtp.c raop_rtp_mirror.c \
        srp.c utils.c) \
    $(addprefix $(UXLIB)/llhttp/, api.c http.c llhttp.c) \
    $(addprefix $(UXLIB)/playfair/, hand_garble.c modified_md5.c omg_hax.c playfair.c sap_hash.c)
LOCAL_C_INCLUDES := $(UXLIB) $(UXLIB)/llhttp $(UXLIB)/playfair
LOCAL_EXPORT_C_INCLUDES := $(UXLIB) $(UXLIB)/llhttp $(UXLIB)/playfair
LOCAL_CFLAGS := -std=gnu11 -DPLIST_210 -DPLIST_230 -DNOHOLD -D__STDC_CONSTANT_MACROS \
    -D__STDC_LIMIT_MACROS -D_GNU_SOURCE -Wno-unused-variable -Wno-unused-but-set-variable \
    -Wno-unused-function -Wno-sign-compare -Wno-unused-parameter
LOCAL_STATIC_LIBRARIES := z9x_plist z9x_crypto
include $(BUILD_STATIC_LIBRARY)

# ---------------------------------------------------------------------------------------
# 5. libz9xairplay.so: our JNI glue (GPL-3.0). Only JNI_OnLoad is exported; the Java
#    natives are bound with RegisterNatives (see glue/z9x_jni.cpp).
include $(CLEAR_VARS)
LOCAL_MODULE := z9xairplay
LOCAL_SRC_FILES := \
    $(GLUE)/z9x_jni.cpp \
    $(GLUE)/raop_callbacks.cpp \
    $(GLUE)/dnssd_nsd.c \
    $(GLUE)/video_decoder.cpp \
    $(GLUE)/audio_engine.cpp
LOCAL_C_INCLUDES := $(GLUE)
LOCAL_CFLAGS := -Wall -Wextra -Wno-unused-parameter -DPLIST_210 -DPLIST_230
LOCAL_CONLYFLAGS := -std=gnu11
LOCAL_STATIC_LIBRARIES := z9x_uxplay z9x_plist z9x_alac z9x_crypto
LOCAL_LDLIBS := -llog -landroid -lmediandk -laaudio -lm
LOCAL_LDFLAGS := -Wl,--version-script=$(LOCAL_PATH)/exports.map
include $(BUILD_SHARED_LIBRARY)
