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

/*
 *  arch/arm/mach-vexpress/include/mach/memory.h
 *
 *  Copyright (C) 2003 ARM Limited
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

/*
 * The macros below define a section size of 256MB and a non-linear virtual to
 * physical mapping:
 *
 * 512MB @ 0x40000000 -> PAGE_OFFSET
 * 256MB @ 0xA0000000 -> PAGE_OFFSET + 0x20000000
 */

#ifndef __ASM_ARCH_MEMORY_H
#define __ASM_ARCH_MEMORY_H

#define PHYS_OFFSET		UL(CONFIG_MEMORY_START_ADDRESS)

#ifdef CONFIG_SPARSEMEM

#ifndef __ASSEMBLY__
extern unsigned long lx_mem_size;
extern unsigned long lx_mem2_addr;
extern unsigned long lx_mem2_size;
extern unsigned long lx_mem3_addr;
extern unsigned long lx_mem3_size;
extern unsigned long lx_mem4_addr;
extern unsigned long lx_mem4_size;
extern unsigned long lx_mem_left_size;
extern unsigned long lx_mem2_left_size;
extern unsigned long lx_mem3_left_size;
extern unsigned long miu01_adj;
#endif

#define MAX_PHYSMEM_BITS        32
#define SECTION_SIZE_BITS       29
#define INVALID_PHY_ADDR        UL(-1)

#ifdef CONFIG_CMA
#define _PMD_SIZE_    (1UL << 21)
#define REM_TO_PMD_SIZE(x) ((x)&(_PMD_SIZE_-1))
#define ALIGN_UPTO_PMD_SIZE(x) (((x)+_PMD_SIZE_-1)&(~(_PMD_SIZE_-1)))
#define VIRT_HOLE(x, y) (ALIGN_UPTO_PMD_SIZE(REM_TO_PMD_SIZE(x) + REM_TO_PMD_SIZE(y)) - ((REM_TO_PMD_SIZE(x) + REM_TO_PMD_SIZE(y))))

/* page offset of LX_MEM */
#define PAGE_OFFSET0    \
        (PHYS_OFFSET==INVALID_PHY_ADDR ? (PAGE_OFFSET) : (PAGE_OFFSET + VIRT_HOLE(PHYS_OFFSET, PAGE_OFFSET)))

/* page offset of LX_MEM2 */
#define PAGE_OFFSET1    \
        (lx_mem2_addr==INVALID_PHY_ADDR ? (PAGE_OFFSET0 + lx_mem_size) :  \
        (PAGE_OFFSET0 + lx_mem_size + VIRT_HOLE(lx_mem2_addr, PAGE_OFFSET0 + lx_mem_size)))

#define PAGE_OFFSET1_LEFT    \
        (lx_mem2_addr==INVALID_PHY_ADDR ? (PAGE_OFFSET0 + lx_mem_left_size) :  \
        (PAGE_OFFSET0 + lx_mem_left_size + VIRT_HOLE(lx_mem2_addr, PAGE_OFFSET0 + lx_mem_left_size)))


/* page offset of LX_MEM3*/
#define PAGE_OFFSET2    \
        (lx_mem3_addr==INVALID_PHY_ADDR ? (PAGE_OFFSET1 + lx_mem2_size) :  \
        (PAGE_OFFSET1 + lx_mem2_size + VIRT_HOLE(lx_mem3_addr, PAGE_OFFSET1 + lx_mem2_size)))

#define PAGE_OFFSET2_LEFT    \
        (lx_mem3_addr==INVALID_PHY_ADDR ? (PAGE_OFFSET1_LEFT + lx_mem2_left_size) :  \
        (PAGE_OFFSET1_LEFT + lx_mem2_left_size + VIRT_HOLE(lx_mem3_addr, PAGE_OFFSET1_LEFT + lx_mem2_left_size)))

/* page offset of LX_MEM4*/		
#define PAGE_OFFSET3    \
        (lx_mem4_addr==INVALID_PHY_ADDR ? (PAGE_OFFSET2 + lx_mem3_size) :  \
        (PAGE_OFFSET2 + lx_mem3_size + VIRT_HOLE(lx_mem4_addr, PAGE_OFFSET2 + lx_mem3_size)))

#define PAGE_OFFSET3_LEFT    \
        (lx_mem4_addr==INVALID_PHY_ADDR ? (PAGE_OFFSET2_LEFT + lx_mem3_left_size) :  \
        (PAGE_OFFSET2_LEFT + lx_mem3_left_size + VIRT_HOLE(lx_mem4_addr, PAGE_OFFSET2_LEFT + lx_mem3_left_size)))

#else
/* page offset of LX_MEM */
#define PAGE_OFFSET0    PAGE_OFFSET

/* page offset of LX_MEM2 */
#define PAGE_OFFSET1    (PAGE_OFFSET + lx_mem_size)
#define PAGE_OFFSET1_LEFT    (PAGE_OFFSET + lx_mem_left_size)

/* page offset of LX_MEM3*/
#define PAGE_OFFSET2    (PAGE_OFFSET1 + lx_mem2_size)
#define PAGE_OFFSET2_LEFT    (PAGE_OFFSET1_LEFT + lx_mem2_left_size)

/* page offset of LX_MEM4*/
#define PAGE_OFFSET3    (PAGE_OFFSET2 + lx_mem3_size)
#define PAGE_OFFSET3_LEFT    (PAGE_OFFSET2_LEFT + lx_mem3_left_size)
#endif

#ifdef CONFIG_CMA
#define __phys_to_virt(phys)                                            \
        ((phys) >= lx_mem2_addr ? ( (phys) >= lx_mem3_addr ?( lx_mem3_addr > ( lx_mem2_addr + lx_mem2_size)?(phys) - lx_mem3_addr + PAGE_OFFSET2 : (phys) - lx_mem2_addr + PAGE_OFFSET1 ): ((phys) - lx_mem2_addr + PAGE_OFFSET1) ):    \
         (phys) - PHYS_OFFSET + PAGE_OFFSET0)

#define __virt_to_phys(virt)                                            \
         ((virt) >= PAGE_OFFSET1_LEFT ?  ( (virt) >= PAGE_OFFSET2_LEFT? ((virt) - PAGE_OFFSET2_LEFT + lx_mem3_addr) : ((virt) - PAGE_OFFSET1_LEFT + lx_mem2_addr) ): \
          (virt) - PAGE_OFFSET0 + PHYS_OFFSET )
#else
#define __phys_to_virt(phys)						\
({									\
	unsigned long _virt;						\
	if (phys >= lx_mem3_addr) {					\
		if(lx_mem3_addr > lx_mem2_addr + lx_mem2_size)		\
			_virt = phys - lx_mem3_addr + PAGE_OFFSET2;	\
		else							\
			_virt = phys - lx_mem2_addr + PAGE_OFFSET1;	\
	}								\
	else if (phys >= lx_mem2_addr)					\
		_virt = phys - lx_mem2_addr + PAGE_OFFSET1;		\
	else								\
		_virt = phys - PHYS_OFFSET + PAGE_OFFSET0;		\
	_virt;								\
})

#define __virt_to_phys(virt)							\
({										\
	phys_addr_t _phy;							\
	if (virt >= PAGE_OFFSET2 && lx_mem3_addr != INVALID_PHY_ADDR)		\
		_phy = virt - PAGE_OFFSET2 + lx_mem3_addr;			\
	else if (virt >= PAGE_OFFSET1 && lx_mem2_addr != INVALID_PHY_ADDR)	\
		_phy = virt - PAGE_OFFSET1 + lx_mem2_addr;			\
	else									\
		_phy = virt - PAGE_OFFSET0 + PHYS_OFFSET;			\
	_phy;									\
})
#endif

#endif
#endif
