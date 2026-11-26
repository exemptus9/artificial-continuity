package org.continuity.recall;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import org.totalrecall.listening.RecallListenActivity;
import org.totalrecall.listening.RecallService;

/** Optional Android quick-settings entry. Recording starts from the visible consent screen. */
public final class ListenTile extends TileService {
  @Override public void onStartListening() {
    super.onStartListening();
    Tile tile = getQsTile();
    if (tile == null) return;
    boolean active = RecallService.isListening();
    tile.setState(active ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
    tile.setLabel(active ? "Pause Recall" : RecallService.pausedAt(this)>0?"Resume Recall":"Recall listening");
    tile.updateTile();
  }
  @Override public void onClick() {
    super.onClick();
    if (RecallService.isListening()) {
      RecallService.command(this, RecallService.USER_PAUSE);
      Tile tile = getQsTile();
      if (tile != null) {
        tile.setState(Tile.STATE_INACTIVE);
        tile.setLabel("Resume Recall");
        tile.updateTile();
      }
      return;
    }
    Intent open = new Intent(this, RecallListenActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    long since=RecallService.pausedAt(this);if(since>0)open.putExtra("overlayStart",true).putExtra("resumeAt",since);
    if (Build.VERSION.SDK_INT >= 34) {
      startActivityAndCollapse(PendingIntent.getActivity(this, 0, open,
          PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    } else {
      startActivityAndCollapse(open);
    }
  }
}
