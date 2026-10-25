package com.deepseekharness.app.remote.ui;

import static org.junit.Assert.*;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class WorkspaceEntryTest {
  @Test
  public void normalLauncherAndMainReturnDoNotRequestPhoneSetup() {
    assertFalse(WorkspaceEntry.requiresLocal(null));
    assertFalse(WorkspaceEntry.requiresLocal(new Intent()));
    assertFalse(
        WorkspaceEntry.requiresLocal(
            new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)));
    assertFalse(WorkspaceEntry.requiresLocal(new Intent().putExtra("open_launch", true)));
  }

  @Test
  public void localChoiceAndNotificationDestinationsRetainSetupGate() {
    for (String extra :
        new String[] {
          WorkspaceEntry.LOCAL,
          "open_web",
          "open_plugins",
          "open_terminal",
          "open_install",
          "start_web_after_setup"
        }) {
      assertTrue(extra, WorkspaceEntry.requiresLocal(new Intent().putExtra(extra, true)));
      assertFalse(extra, WorkspaceEntry.requiresLocal(new Intent().putExtra(extra, false)));
    }
  }
}
