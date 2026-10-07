# Third-party tools (unmodified copies)

| File | Source | sha256 | License |
|---|---|---|---|
| `avbtool.py` | AOSP `external/avb/avbtool.py`, tag android-14.0.0_r67 as checked out in the LineageOS 21 tree on the build laptop (`~/lineage/external/avb`, commit d2c46084c676), avbtool 1.3.0 | `f8e82d9eb64093972cc2e04fbcec5c86a10cb2cac9f871c7a55490bb3b6f48eb` | MIT (header of the file) |

`avbtool.py` is the same tool AOSP's `apexer` and `sign_apex` call to add and verify the AVB hashtree
footer of an APEX payload. A copy lives here so the Mac (which holds the keys) and the laptop run the
same version; `apexlib.py` refuses a copy whose sha256 differs from the value above. It needs only
Python 3 and the `openssl` command line tool.
