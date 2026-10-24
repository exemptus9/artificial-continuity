package org.continuity.recall;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Opens the visible voice screen; never starts a microphone from a locked tile. */
public final class VoiceTileService extends TileService {
  @Override public void onStartListening() {
    Tile tile = getQsTile();
    if (tile != null) {
      tile.setState(Tile.STATE_INACTIVE);
      tile.setLabel("Temporal Voice");
      if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("Open voice controls");
      tile.updateTile();
    }
  }
  @Override public void onClick() {
    super.onClick();
    if (isLocked()) unlockAndRun(this::openScreen);
    else openScreen();
  }
  @SuppressWarnings("deprecation")
  private void openScreen() {
    Intent intent = new Intent(this, VoiceActivity.class)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (Build.VERSION.SDK_INT >= 34) {
      PendingIntent open = PendingIntent.getActivity(this, 71, intent,
          PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
      startActivityAndCollapse(open);
    } else startActivityAndCollapse(intent);
  }
}
