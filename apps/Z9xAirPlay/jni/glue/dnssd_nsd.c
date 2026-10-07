/*
 * dnssd backend for UxPlay on Android: the TXT records are built here, registration is
 * done by Java NsdManager (NsdPublisher), so nothing competes with the system mDNS
 * responder or Google Cast on UDP 5353.
 *
 * Adapted from jqssun/android-airplay-server v0.0.31 app/src/main/cpp/android_dnssd_shim.c
 * (GPL-3.0). Modified for Z9xAirPlay: real DNS-SD TXT rdata for /info "txtAirPlay" /
 * "txtRAOP", key sets identical to UxPlay master lib/dns_sd/dns_sd.c, and the
 * dnssd_get_service_fd / dnssd_process_service entry points that master's dnssd.h declares.
 *
 * Copyright (C) jqssun / android-airplay-server contributors
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-only
 */

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "global.h"
#include "utils.h"

#include "dnssd_nsd.h"
#include "z9x_common.h"

#define MAX_SERVNAME 256
#define MAX_TXT_ENTRIES 32
#define MAX_TXT_KEY 32
#define MAX_TXT_VAL 200
#define MAX_TXT_RDATA 1300

typedef struct {
    char key[MAX_TXT_KEY];
    char val[MAX_TXT_VAL];
} txt_entry_t;

typedef struct {
    txt_entry_t entries[MAX_TXT_ENTRIES];
    int count;
    unsigned char rdata[MAX_TXT_RDATA];
    int rdata_len;
} txt_record_t;

typedef struct {
    txt_record_t raop;
    txt_record_t airplay;
    char raop_servname[MAX_SERVNAME];
} dnssd_private_t;

static dnssd_private_t *priv_of(dnssd_t *dnssd) {
    assert(dnssd && dnssd->dnssd_private);
    return (dnssd_private_t *) dnssd->dnssd_private;
}

static void txt_reset(txt_record_t *rec) {
    rec->count = 0;
    rec->rdata_len = 0;
}

/* Same semantics as TXTRecordSetValue: replace an existing key, else append. */
static void txt_set(txt_record_t *rec, const char *key, const char *val) {
    if (!val) val = "";
    for (int i = 0; i < rec->count; i++) {
        if (strcmp(rec->entries[i].key, key) == 0) {
            snprintf(rec->entries[i].val, MAX_TXT_VAL, "%s", val);
            return;
        }
    }
    if (rec->count >= MAX_TXT_ENTRIES) {
        Z9X_LOGE("dnssd: too many TXT entries, dropping %s", key);
        return;
    }
    snprintf(rec->entries[rec->count].key, MAX_TXT_KEY, "%s", key);
    snprintf(rec->entries[rec->count].val, MAX_TXT_VAL, "%s", val);
    rec->count++;
}

/* DNS-SD TXT rdata: a sequence of <len><key=value> strings (RFC 6763 section 6). */
static void txt_build_rdata(txt_record_t *rec) {
    int pos = 0;
    for (int i = 0; i < rec->count; i++) {
        size_t klen = strlen(rec->entries[i].key);
        size_t vlen = strlen(rec->entries[i].val);
        size_t len = klen + 1 + vlen;
        if (len > 255 || pos + 1 + (int) len > MAX_TXT_RDATA) {
            Z9X_LOGE("dnssd: TXT entry %s does not fit", rec->entries[i].key);
            continue;
        }
        rec->rdata[pos++] = (unsigned char) len;
        memcpy(rec->rdata + pos, rec->entries[i].key, klen);
        pos += (int) klen;
        rec->rdata[pos++] = '=';
        memcpy(rec->rdata + pos, rec->entries[i].val, vlen);
        pos += (int) vlen;
    }
    rec->rdata_len = pos;
}

void *dnssd_private_init(dnssd_t *dnssd, int *error) {
    (void) dnssd;
    if (error) *error = DNSSD_ERROR_NOERROR;
    dnssd_private_t *priv = (dnssd_private_t *) calloc(1, sizeof(dnssd_private_t));
    if (!priv && error) *error = DNSSD_ERROR_OUTOFMEM;
    return priv;
}

void dnssd_private_destroy(void *priv) {
    free(priv);
}

void dnssd_error_text(int *error, const char *appname) {
    Z9X_LOGE("%s: dnssd (NsdManager backend) error %d", appname ? appname : "dnssd",
             error ? *error : -1);
}

int dnssd_register_raop(dnssd_t *dnssd, unsigned short port) {
    (void) port;
    dnssd_private_t *priv = priv_of(dnssd);
    txt_record_t *rec = &priv->raop;
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);

    txt_reset(rec);
    txt_set(rec, "ch", RAOP_CH);
    txt_set(rec, "cn", RAOP_CN);
    txt_set(rec, "da", RAOP_DA);
    txt_set(rec, "et", RAOP_ET);
    txt_set(rec, "vv", RAOP_VV);
    txt_set(rec, "ft", features);
    txt_set(rec, "am", GLOBAL_MODEL);
    txt_set(rec, "md", RAOP_MD);
    txt_set(rec, "rhd", RAOP_RHD);
    switch (dnssd->pin_pw) {
    case 1:  /* on-screen pin: sf bit 3 */
        txt_set(rec, "pw", "true");
        txt_set(rec, "sf", "0x8c");
        break;
    case 2:  /* password: sf bit 7 */
    case 3:
        txt_set(rec, "pw", "true");
        txt_set(rec, "sf", "0x84");
        break;
    default:
        txt_set(rec, "pw", "false");
        txt_set(rec, "sf", RAOP_SF);
        break;
    }
    txt_set(rec, "sr", RAOP_SR);
    txt_set(rec, "ss", RAOP_SS);
    txt_set(rec, "sv", RAOP_SV);
    txt_set(rec, "tp", RAOP_TP);
    txt_set(rec, "txtvers", RAOP_TXTVERS);
    txt_set(rec, "vs", RAOP_VS);
    txt_set(rec, "vn", RAOP_VN);
    txt_set(rec, "pk", dnssd->pk);
    txt_build_rdata(rec);

    /* service name "<12 hex digits>@<name>" */
    if (utils_hwaddr_raop(priv->raop_servname, sizeof(priv->raop_servname),
                          dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }
    if (sizeof(priv->raop_servname) < strlen(priv->raop_servname) + 1 + (size_t) dnssd->name_len + 1) {
        return -2;
    }
    strncat(priv->raop_servname, "@", sizeof(priv->raop_servname) - strlen(priv->raop_servname) - 1);
    strncat(priv->raop_servname, dnssd->name, sizeof(priv->raop_servname) - strlen(priv->raop_servname) - 1);
    return 0;
}

int dnssd_register_airplay(dnssd_t *dnssd, unsigned short port) {
    (void) port;
    dnssd_private_t *priv = priv_of(dnssd);
    txt_record_t *rec = &priv->airplay;
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd->features1, dnssd->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd->hw_addr, dnssd->hw_addr_len) < 0) {
        return -1;
    }

    txt_reset(rec);
    txt_set(rec, "deviceid", device_id);
    txt_set(rec, "features", features);
    txt_set(rec, "pw", dnssd->pin_pw ? "true" : "false");
    txt_set(rec, "flags", "0x4");
    txt_set(rec, "model", GLOBAL_MODEL);
    txt_set(rec, "pk", dnssd->pk);
    txt_set(rec, "pi", AIRPLAY_PI);
    txt_set(rec, "srcvers", AIRPLAY_SRCVERS);
    txt_set(rec, "vv", AIRPLAY_VV);
    txt_build_rdata(rec);
    return 0;
}

/* NsdManager registrations are owned by Java. */
void dnssd_unregister_raop(dnssd_t *dnssd) { (void) dnssd; }
void dnssd_unregister_airplay(dnssd_t *dnssd) { (void) dnssd; }

const char *dnssd_get_raop_txt(dnssd_t *dnssd, int *length) {
    txt_record_t *rec = &priv_of(dnssd)->raop;
    if (length) *length = rec->rdata_len;
    return (const char *) rec->rdata;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd, int *length) {
    txt_record_t *rec = &priv_of(dnssd)->airplay;
    if (length) *length = rec->rdata_len;
    return (const char *) rec->rdata;
}

/* No dns_sd.h daemon socket to service with the NsdManager backend. */
int dnssd_get_service_fd(dnssd_t *dnssd, int service) {
    (void) dnssd; (void) service;
    return -1;
}

int dnssd_process_service(dnssd_t *dnssd, int service) {
    (void) dnssd; (void) service;
    return 0;
}

/* ---- accessors for the JNI layer ---- */

static txt_record_t *rec_of(dnssd_t *dnssd, int service) {
    dnssd_private_t *priv = priv_of(dnssd);
    return service == DNSSD_SERVICE_AIRPLAY ? &priv->airplay : &priv->raop;
}

int z9x_dnssd_txt_count(dnssd_t *dnssd, int service) {
    return rec_of(dnssd, service)->count;
}

const char *z9x_dnssd_txt_key(dnssd_t *dnssd, int service, int index) {
    txt_record_t *rec = rec_of(dnssd, service);
    return (index >= 0 && index < rec->count) ? rec->entries[index].key : NULL;
}

const char *z9x_dnssd_txt_val(dnssd_t *dnssd, int service, int index) {
    txt_record_t *rec = rec_of(dnssd, service);
    return (index >= 0 && index < rec->count) ? rec->entries[index].val : NULL;
}

const char *z9x_dnssd_raop_servname(dnssd_t *dnssd) {
    return priv_of(dnssd)->raop_servname;
}
