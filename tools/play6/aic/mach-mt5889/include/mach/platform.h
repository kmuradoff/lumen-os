/* SPDX-License-Identifier: GPL-2.0-only OR BSD-3-Clause */
/******************************************************************************
 *
 * This file is provided under a dual license.  When you use or
 * distribute this software, you may choose to be licensed under
 * version 2 of the GNU General Public License ("GPLv2 License")
 * or BSD License.
 *
 * GPLv2 License
 *
 * Copyright(C) 2019 MediaTek Inc.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of version 2 of the GNU General Public License as
 * published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See http://www.gnu.org/licenses/gpl-2.0.html for more details.
 *
 * BSD LICENSE
 *
 * Copyright(C) 2019 MediaTek Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 *  * Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *  * Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *  * Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 *****************************************************************************/

#ifndef	__PLATFORM_H__
#define	__PLATFORM_H__

//------------------------------------------------------------------------------
//  Include Files
//------------------------------------------------------------------------------

//------------------------------------------------------------------------------
//  Macros
//------------------------------------------------------------------------------

/*
 * Physical base addresses
 */
#define chip_CA7_MPIC		(0x16000000)

#define chip_MPCORE_SCU		(chip_CA7_MPIC + 0x4000)
#define chip_MPCORE_GIC_CPU	(chip_CA7_MPIC + 0x0100)
#define chip_MPCORE_GIT		(chip_CA7_MPIC + 0x0200)
#define chip_MPCORE_TWD		(chip_CA7_MPIC + 0x0600)
#define chip_MPCORE_GIC_DIST	(chip_CA7_MPIC + 0x1000)

//------------------------------------------------------------------------------
//
//  Define:  agate_BASE_REG_TIMER_PA
//
//  Locates the timer register base.
//
#define chip_BASE_REG_TIMER0_PA              (0x1F006040)
#define chip_BASE_REG_TIMER1_PA              (0x1F006080)
#define chip_BASE_REG_TIMER2_PA              (0xA0007780)
#ifdef CONFIG_MP_STATIC_TIMER_CLOCK_SOURCE
#define chip_BASE_REG_RTC1_PA                (0x1F002600)
#define chip_READ_REG_RTC1_PA                (0x1F00262C)
#define chip_READ_REG_RTC1_VA                (0xFD00262C)
#define chip_BASE_REG_PM_SLEEP_PA            (0x1F001C00)
#endif

//------------------------------------------------------------------------------
//  Function prototypes
//------------------------------------------------------------------------------
int Mstar_ehc_platform_init(void);

#endif // __PLATFORM_H__

/* 	END */
