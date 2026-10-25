package com.deepseekharness.app.remote.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.remote.RemoteHost;
import com.deepseekharness.app.ui.MainActivity;
import com.deepseekharness.app.ui.LaunchFragment;
import com.deepseekharness.app.ui.WelcomeActivity;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** Exercises the actual launcher Activity with no runtime instance or bootstrap allowed. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = WorkspaceLauncherGateTest.App.class,
    shadows = WorkspaceLauncherGateTest.NoRuntime.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class WorkspaceLauncherGateTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class NoRuntime {
    @Implementation
    protected static HarnessController get(Context context) {
      throw new AssertionError(
          "Main home/computer entry must not initialize Ubuntu/HarnessController");
    }
  }

  @Before
  public void uninitializedPhone() {
    var config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setWelcomed(false);
    config.setCheckUpdate(false);
  }

  @Test
  public void freshLauncherStaysOnMainAndComputerEntryDoesNotRequireUbuntu() {
    var app = RuntimeEnvironment.getApplication();
    Intent launcher =
        new Intent(app, MainActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER);
    var host = Robolectric.buildActivity(MainActivity.class, launcher);
    host.get().setTheme(R.style.Theme_DeepseekHarness);
    host.create().start().resume();
    host.get().getSupportFragmentManager().executePendingTransactions();
    var shadow =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(
            host.get());
    assertNull("Launcher must stay on the Main home screen", shadow.getNextStartedActivity());
    assertFalse(host.get().isFinishing());
    var home = host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    assertTrue(home instanceof LaunchFragment);
    assertTrue(home.requireArguments().getBoolean(LaunchFragment.ARG_PREPARE_LOCAL));
    assertEquals(
        android.view.View.GONE, host.get().findViewById(R.id.launch_controls).getVisibility());
    assertEquals(
        android.view.View.GONE, host.get().findViewById(R.id.launch_log_panel).getVisibility());
    assertNotNull(host.get().findViewById(R.id.launch_workspaces));
    assertNull(
        "Home no longer has a background-task action",
        host.get()
            .findViewById(
                app.getResources().getIdentifier("btn_tasks", "id", app.getPackageName())));
    host.get().findViewById(R.id.launch_workspaces).performClick();
    assertEquals(
        WorkspaceActivity.class.getName(),
        shadow.getNextStartedActivity().getComponent().getClassName());
    assertFalse("Computer page can return to the same Main home", host.get().isFinishing());
    host.pause().stop().destroy();
  }

  @Test
  public void phoneSetupOnHomeRequestsTheExistingLocalGate() {
    var host = Robolectric.buildActivity(MainActivity.class);
    host.get().setTheme(R.style.Theme_DeepseekHarness);
    host.create().start().resume();
    host.get().getSupportFragmentManager().executePendingTransactions();
    host.get().findViewById(R.id.launch_start).performClick();
    Intent local =
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(
                host.get())
            .getNextStartedActivity();
    assertEquals(MainActivity.class.getName(), local.getComponent().getClassName());
    assertTrue(WorkspaceEntry.requiresLocal(local));
    host.pause().stop().destroy();
  }

  @Test
  public void phoneSelectionRetainsExistingLocalWelcomeGate() {
    var host =
        Robolectric.buildActivity(
            MainActivity.class, WorkspaceEntry.local(RuntimeEnvironment.getApplication()));
    host.get().setTheme(R.style.Theme_DeepseekHarness);
    host.create();
    assertEquals(
        WelcomeActivity.class.getName(),
        org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowActivity>extract(
                host.get())
            .getNextStartedActivity()
            .getComponent()
            .getClassName());
    assertTrue(host.get().isFinishing());
    // onDestroy's terminal cleanup has no guest to close in this fresh test app.
    host.destroy();
  }

  @Test
  public void repeatedComputerUsesLatestLiveEndpointsAndDistinctComputerStaysSeparate() {
    RemoteHost old =
        new RemoteHost(
            "computerA", "PC A", "", List.of("/ip4/192.168.1.2/tcp/1000/p2p/computerA"), List.of());
    RemoteHost changed =
        new RemoteHost(
            "computerA", "PC A", "", List.of("/ip4/192.168.1.3/tcp/2000/p2p/computerA"), List.of());
    RemoteHost other =
        new RemoteHost(
            "computerB", "PC B", "", List.of("/ip4/192.168.1.4/tcp/3000/p2p/computerB"), List.of());
    List<RemoteHost> rows = WorkspaceActivity.uniqueHosts(List.of(old, other, changed));
    assertEquals(2, rows.size());
    assertSame(changed, rows.get(0));
    assertSame(other, rows.get(1));
  }

  @Test
  public void explicitLocalNotificationDestinationIsPreservedEvenWithLauncherAction() {
    Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    assertFalse(WorkspaceEntry.requiresLocal(launcher));
    for (String destination : List.of("open_web", "open_plugins", "open_terminal", "open_install"))
      assertTrue(WorkspaceEntry.requiresLocal(new Intent(launcher).putExtra(destination, true)));
  }
}
