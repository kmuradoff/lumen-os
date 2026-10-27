/*
 * Shim for AOSP <cutils/properties.h>: property_get() on top of bionic's
 * __system_property_get(), same contract as libcutils:
 * returns the value length; when the property is unset or empty and
 * default_value != NULL, copies default_value (truncated to
 * PROPERTY_VALUE_MAX - 1) and returns its length.
 */
#pragma once
#include <string.h>
#include <sys/system_properties.h>

#define PROPERTY_KEY_MAX   PROP_NAME_MAX
#define PROPERTY_VALUE_MAX PROP_VALUE_MAX

static inline int property_get(const char *key, char *value, const char *default_value)
{
    int len = __system_property_get(key, value);
    if (len > 0)
        return len;
    if (default_value) {
        len = (int)strnlen(default_value, PROPERTY_VALUE_MAX - 1);
        memcpy(value, default_value, len);
        value[len] = '\0';
    } else {
        value[0] = '\0';
        len = 0;
    }
    return len;
}
