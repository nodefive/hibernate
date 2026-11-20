package com.unl0cker.hibernate;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: one tap hibernates every target app. */
public class HibernateTile extends TileService implements Hibernator.Listener {

    @Override
    public void onStartListening() {
        Hibernator.get().addListener(this);
        update();
    }

    @Override
    public void onStopListening() {
        Hibernator.get().removeListener(this);
    }

    @Override
    public void onClick() {
        Hibernator h = Hibernator.get();
        if (h.isRunning()) {
            h.cancel();
            return;
        }
        CloseService service = CloseService.instance();
        if (service != null) {
            service.dismissShade();
        }
        h.start(this, Hibernator.Source.TILE, null);
    }

    @Override
    public void onHibernateChanged() {
        update();
    }

    private void update() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        Hibernator h = Hibernator.get();
        tile.setState(h.isRunning() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(h.isRunning()
                ? getString(R.string.tile_running, h.done(), h.total())
                : null);
        tile.updateTile();
    }
}
