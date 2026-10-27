/* Compile-only check (armv7, ILP32): struct layouts the AOSP Android 11
 * @1.0-impl VendorInterface expects from bt_vendor_lib.h. */
#include <stddef.h>
#include "bt_vendor_lib.h"
#include "bt_hci_bdroid.h"
_Static_assert(sizeof(void *) == 4, "32-bit target");
_Static_assert(sizeof(bt_vendor_interface_t) == 16, "size, init, op, cleanup");
_Static_assert(offsetof(bt_vendor_interface_t, init) == 4, "init");
_Static_assert(offsetof(bt_vendor_interface_t, op) == 8, "op");
_Static_assert(offsetof(bt_vendor_interface_t, cleanup) == 12, "cleanup");
_Static_assert(sizeof(bt_vendor_callbacks_t) == 40, "10 members");
_Static_assert(offsetof(bt_vendor_callbacks_t, alloc) == 20, "alloc");
_Static_assert(offsetof(bt_vendor_callbacks_t, xmit_cb) == 28, "xmit_cb");
_Static_assert(offsetof(bt_vendor_callbacks_t, epilog_cb) == 32, "epilog_cb");
_Static_assert(offsetof(bt_vendor_callbacks_t, a2dp_offload_cb) == 36, "a2dp_offload_cb");
_Static_assert(sizeof(HC_BT_HDR) == 8, "HC_BT_HDR header");
_Static_assert(BT_VND_OP_POWER_CTRL == 0 && BT_VND_OP_FW_CFG == 1 && BT_VND_OP_USERIAL_OPEN == 3 &&
               BT_VND_OP_EPILOG == 9 && BT_VND_OP_A2DP_OFFLOAD_STOP == 11, "opcode numbering");
_Static_assert(CH_MAX == 4, "fd array size");
