/*
 * Copyright (C) 2026 Z9X project. Licensed under the Apache License, Version 2.0.
 */
package org.lumen.tvinput;

/**
 * Compatibility name for the same GLOBAL_BUTTON handling. The canonical component in
 * global_keys.xml is org.lumen.tvinput/.GlobalKeyReceiver; the remote-topic RRO draft used
 * org.lumen.tvinput/.KeyReceiver. Declaring both makes either RRO work. Not exported.
 */
public class KeyReceiver extends GlobalKeyReceiver {
}
