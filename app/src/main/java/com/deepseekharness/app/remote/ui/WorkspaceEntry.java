package com.deepseekharness.app.remote.ui;

import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.ui.MainActivity;

/** Explicit phone actions retain the local setup gate; normal launches open Main. */
public final class WorkspaceEntry {
  public static final String LOCAL = "dsha.workspace.local";

  private WorkspaceEntry() {}

  public static boolean requiresLocal(Intent intent) {
    return intent != null
        && (intent.getBooleanExtra(LOCAL, false)
            || intent.getBooleanExtra("open_web", false)
            || intent.getBooleanExtra("open_plugins", false)
            || intent.getBooleanExtra("open_terminal", false)
            || intent.getBooleanExtra("open_install", false)
            || intent.getBooleanExtra("start_web_after_setup", false));
  }

  public static Intent local(Context context) {
    return MainActivity.homeIntent(context).putExtra(LOCAL, true);
  }
}
