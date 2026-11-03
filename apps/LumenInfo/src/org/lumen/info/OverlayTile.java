package org.lumen.info;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick settings tile: switches the overlay on and off. */
public final class OverlayTile extends TileService {
    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        OverlayService.toggle(this);
        update();
    }

    private void update() {
        Tile t = getQsTile();
        if (t == null) return;
        t.setState(new Prefs(this).enabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        t.updateTile();
    }
}
