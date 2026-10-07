#!/usr/bin/env python3
"""Validate a res/xml/global_keys.xml for the framework RRO before it goes into the image.

  check_global_keys.py global_keys.xml [aosp14_keycodes.txt]

Mirrors GlobalKeyManager.loadGlobalKeys (frameworks/base/.../policy/GlobalKeyManager.java:123-162):
root <global_keys version="1">, <key keyCode=".." component="pkg/.Cls" [dispatchWhenNonInteractive]>.
Nothing here can crash system_server (all parse errors are caught or Log.wtf, which is never fatal
in system_server), but a mistake silently drops keys, so fail loudly at build time instead.
Also checks that TvFrameworkOverlay's 5 entries are kept, because an RRO replaces the whole file.
"""
import sys
import xml.etree.ElementTree as ET

TV_ENTRIES = {
    'KEYCODE_DVR': 'com.google.android.tv/.receiver.GlobalKeyReceiver',
    'KEYCODE_GUIDE': 'com.google.android.tv/.receiver.GlobalKeyReceiver',
    'KEYCODE_TV': 'com.google.android.tv/.receiver.GlobalKeyReceiver',
    'KEYCODE_TV_INPUT': 'com.google.android.tv/.receiver.GlobalKeyReceiver',
    'KEYCODE_PAIRING': 'com.android.tv.settings/.GlobalKeyReceiver',
}


def main():
    path = sys.argv[1]
    names = None
    if len(sys.argv) > 2:
        names = {l.strip() for l in open(sys.argv[2]) if l.strip() and not l.strip().isdigit()}
    root = ET.parse(path).getroot()
    errs, seen = [], {}
    if root.tag != 'global_keys':
        errs.append(f'root is <{root.tag}>, must be <global_keys>')
    if root.get('version') != '1':
        errs.append('version must be exactly "1" (aapt2 then stores it as an int)')
    for k in root:
        if k.tag != 'key':
            continue
        code, comp = k.get('keyCode'), k.get('component')
        if not code or not comp:
            errs.append(f'key without keyCode/component: {k.attrib}')
            continue
        if not code.startswith('KEYCODE_') or (names is not None and code[8:] not in names):
            errs.append(f'unknown key code {code}')
        pkg, _, cls = comp.partition('/')
        if not pkg or not cls:
            errs.append(f'component {comp} is not "package/.Class"')
        if code in seen:
            errs.append(f'{code} mapped twice (last wins): {seen[code]} and {comp}')
        seen[code] = comp
        dwni = k.get('dispatchWhenNonInteractive')
        if dwni not in (None, 'true', 'false'):
            errs.append(f'{code}: dispatchWhenNonInteractive must be true/false')
    for code, comp in TV_ENTRIES.items():
        if code not in seen:
            errs.append(f'TvFrameworkOverlay entry {code} -> {comp} is missing (it would be lost)')
    pairing = [k for k in root if k.get('keyCode') == 'KEYCODE_PAIRING']
    if pairing and pairing[0].get('dispatchWhenNonInteractive') != 'true':
        errs.append('KEYCODE_PAIRING must keep dispatchWhenNonInteractive="true"')
    for e in errs:
        print('ERROR', e)
    print(f'{len(seen)} keys, {len(errs)} errors')
    sys.exit(1 if errs else 0)


if __name__ == '__main__':
    main()
