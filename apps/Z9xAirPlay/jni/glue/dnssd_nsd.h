/*
 * Accessors for the TXT records built by dnssd_nsd.c (NsdManager backend).
 * Copyright (C) 2026 Z9X project
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#ifndef Z9X_DNSSD_NSD_H
#define Z9X_DNSSD_NSD_H

#include "dnssd.h"

#ifdef __cplusplus
extern "C" {
#endif

/* service: DNSSD_SERVICE_RAOP (0) or DNSSD_SERVICE_AIRPLAY (1) */
int z9x_dnssd_txt_count(dnssd_t *dnssd, int service);
const char *z9x_dnssd_txt_key(dnssd_t *dnssd, int service, int index);
const char *z9x_dnssd_txt_val(dnssd_t *dnssd, int service, int index);
const char *z9x_dnssd_raop_servname(dnssd_t *dnssd);

#ifdef __cplusplus
}
#endif

#endif
