package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import java.util.ArrayList;
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

/** 实际Main/FragmentManager旋转恢复，后台核对被刻意延后；不运行guest或模型请求。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = MainEntryLayoutLifecycleTest.App.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {
      MainEntryLayoutLifecycleTest.Controller.class,
      MainEntryLayoutLifecycleTest.Environment.class,
      com.deepseekharness.app.runtime.RuntimeToolsRegistryAndroidTest.HostFileSystem.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainEntryLayoutLifecycleTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static boolean unresolved;
    static int stopRequests, startRequests;
    static final List<Runnable> checks = new ArrayList<>();

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected void restoreRunningWebSession(Runnable completed) {
      if (completed != null) checks.add(completed);
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      return false;
    }

    @Implementation
    protected boolean hasUnresolvedWebSession() {
      return unresolved;
    }

    @Implementation
    protected void stopWeb(java.util.function.Consumer<String> status) {
      stopRequests++;
    }

    @Implementation
    protected boolean startProtectedWeb(
        com.deepseekharness.app.util.WebStartProtection.Ticket ticket,
        boolean safe,
        boolean recover,
        java.util.function.Consumer<String> status) {
      startRequests++;
      throw new AssertionError("Restoring a gated page must not start a guest");
    }
  }

  @Implements(value = EnvironmentUiStatus.class, isInAndroidSdk = false)
  public static final class Environment {
    @Implementation
    protected static EnvironmentUiStatus.Snapshot get(Context context) {
      return new EnvironmentUiStatus.Snapshot(true, true, false, false);
    }
  }

  public static final class SavedPage extends Fragment {
    static int viewsCreated;

    public SavedPage() {
      super(android.R.layout.simple_list_item_1);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
      viewsCreated++;
      return super.onCreateView(inflater, container, state);
    }
  }

  @Before
  public void prepare() throws Exception {
    var app = (App) RuntimeEnvironment.getApplication();
    // Robolectric has no APK native library directory. The real controller records this existing
    // directory without loading a library; host descriptor syscalls use the established NOFOLLOW
    // port.
    app.getApplicationInfo().nativeLibraryDir = app.getCacheDir().getAbsolutePath();
    String installed = com.deepseekharness.app.core.InstallationIdentity.loadOrCreate(app);
    assertEquals(installed, app.installationId());
    assertEquals(installed, com.deepseekharness.app.core.InstallationIdentity.loadOrCreate(app));
    Controller.value = org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    Controller.unresolved = false;
    Controller.stopRequests = Controller.startRequests = 0;
    Controller.checks.clear();
    SavedPage.viewsCreated = 0;
    ConfigStore config = new ConfigStore(RuntimeEnvironment.getApplication());
    config.setWelcomed(true);
    config.setCheckUpdate(false);
  }

  @Test
  public void pendingCheckKeepsRealContainerAndRotatedFragmentAttached() throws Exception {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    var first = host.get();
    assertNotNull(first.findViewById(R.id.fragment_container));
    assertFalse(Controller.checks.isEmpty());
    first
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new SavedPage(), "saved-page")
        .commitNow();
    assertSame(
        first.findViewById(R.id.fragment_container),
        first
            .getSupportFragmentManager()
            .findFragmentByTag("saved-page")
            .requireView()
            .getParent());
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    var restoredHost = Robolectric.buildActivity(MainActivity.class).create(saved).start().resume();
    var restored = restoredHost.get();
    Fragment page = restored.getSupportFragmentManager().findFragmentByTag("saved-page");
    assertNotNull(page);
    assertNull("核对未完成不能重新创建旧终端等页面", page.getView());
    var release = MainActivity.class.getDeclaredMethod("releaseGatedPages", boolean.class);
    release.setAccessible(true);
    release.invoke(restored, true);
    assertNotNull(page.getView());
    assertSame(restored.findViewById(R.id.fragment_container), page.requireView().getParent());
    assertNotNull(restored.findViewById(R.id.bottom_nav));
    restoredHost.pause().stop().destroy();
  }

  @Test
  public void homeBackDuringEntryCheckGoesToDesktopWithoutFinishingOrOpeningAnotherMain() {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    var main = host.get();
    org.robolectric.shadows.ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(main);
    while (shadow.getNextStartedActivity() != null) {}
    main.getOnBackPressedDispatcher().onBackPressed();
    assertTrue(shadow.isTaskMovedToBack());
    assertFalse(main.isFinishing());
    var home = shadow.getNextStartedActivity();
    assertNotNull(home);
    assertEquals(android.content.Intent.ACTION_MAIN, home.getAction());
    assertTrue(home.hasCategory(android.content.Intent.CATEGORY_HOME));
    assertNull(home.getComponent());
    assertNull(shadow.getNextStartedActivity());
    host.pause().stop().destroy();
  }

  @Test
  public void nestedPageBackPopsThePageBeforeLeavingMain() {
    var host = Robolectric.buildActivity(MainActivity.class).create().start().resume();
    var main = host.get();
    org.robolectric.shadows.ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(main);
    main.getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new SavedPage(), "nested")
        .addToBackStack("nested")
        .commit();
    main.getSupportFragmentManager().executePendingTransactions();
    assertEquals(1, main.getSupportFragmentManager().getBackStackEntryCount());
    main.getOnBackPressedDispatcher().onBackPressed();
    assertEquals(0, main.getSupportFragmentManager().getBackStackEntryCount());
    assertFalse(shadow.isTaskMovedToBack());
    assertFalse(main.isFinishing());
    host.pause().stop().destroy();
  }

  @Test
  public void readyGateReplacesRestoredPreparationPageWithoutAnotherTabClick() throws Exception {
    var restored = restoreLaunch(true);
    try {
      showMain(restored, false);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertTrue(current instanceof LaunchFragment);
      assertNotSame(restored.page, current);
      assertFalse(preparationPage(current));
      assertEquals(
          View.VISIBLE, current.requireView().findViewById(R.id.launch_controls).getVisibility());
      assertNull(nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void setupGateReplacesRestoredRuntimePageBeforeItCanResume() throws Exception {
    var restored = restoreLaunch(false);
    try {
      showMain(restored, true);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertNotSame(restored.page, current);
      assertTrue(preparationPage(current));
      assertEquals(
          View.GONE, current.requireView().findViewById(R.id.launch_controls).getVisibility());
      assertNull("Preparing the landing page must not start Extract", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unchangedGateKeepsRestoredLaunchInstanceAndItsView() throws Exception {
    var restored = restoreLaunch(false);
    try {
      showMain(restored, false);
      assertSame(
          restored.page,
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container));
      View view = restored.page.requireView();
      ((com.google.android.material.bottomnavigation.BottomNavigationView)
              restored.main.findViewById(R.id.bottom_nav))
          .setSelectedItemId(R.id.nav_launch);
      restored.main.getSupportFragmentManager().executePendingTransactions();
      assertSame(
          restored.page,
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container));
      assertSame(view, restored.page.requireView());
      assertNull(nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unknownSessionReplacesMatchingRestoredLaunchAndShowsErrorWithNormalStop()
      throws Exception {
    // Construct only the real controller's display collaborators. Restore/start/stop ports remain
    // isolated above; no guest, maintenance, shell, or model operation is dispatched.
    Controller.value = new HarnessController(RuntimeEnvironment.getApplication());
    var restored = restoreLaunch(false);
    try {
      Controller.unresolved = true;
      showMain(restored, false);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertTrue(current instanceof LaunchFragment);
      assertNotSame(restored.page, current);
      assertNotNull(current.getView());
      assertSame(
          restored.main.findViewById(R.id.fragment_container), current.requireView().getParent());
      assertEquals(
          View.VISIBLE, current.requireView().findViewById(R.id.launch_controls).getVisibility());
      var refresh = LaunchFragment.class.getDeclaredMethod("refreshRunState");
      refresh.setAccessible(true);
      refresh.invoke(current);
      assertEquals(
          com.deepseekharness.app.util.UiText.choose(
              "原 Web 实例状态待确认，请查看诊断或正常停止后重试",
              "The previous Web instance is unconfirmed. Inspect diagnostics or stop it normally before retrying."),
          ((android.widget.TextView) current.requireView().findViewById(R.id.launch_run_state))
              .getText()
              .toString());
      assertTrue(current.requireView().findViewById(R.id.launch_stop).isEnabled());
      assertTrue(current.requireView().findViewById(R.id.launch_stop).performClick());
      assertEquals(1, Controller.stopRequests);
      assertEquals(0, Controller.startRequests);
      assertNull("Unknown session stays on its diagnostic/stop page", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void requiredSetupReplacesMatchingRestoredPreparationPageWithAVisibleLandingPage()
      throws Exception {
    var restored = restoreLaunch(true);
    try {
      showMain(restored, true);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertNotSame(restored.page, current);
      assertTrue(preparationPage(current));
      assertNotNull(current.getView());
      assertSame(
          restored.main.findViewById(R.id.fragment_container), current.requireView().getParent());
      assertEquals(
          View.GONE, current.requireView().findViewById(R.id.launch_controls).getVisibility());
      assertEquals(
          View.VISIBLE, current.requireView().findViewById(R.id.launch_workspaces).getVisibility());
      assertEquals(0, Controller.startRequests);
      assertNull("Landing page does not automatically extract", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unknownSessionDoesNotRecreateTheRestoredBusinessPageWhileShowingLaunch()
      throws Exception {
    var restored = restorePage(new SavedPage());
    try {
      Controller.unresolved = true;
      SavedPage.viewsCreated = 0;
      showMain(restored, false);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertTrue(current instanceof LaunchFragment);
      assertNotNull(current.getView());
      assertFalse(restored.page.isAdded());
      assertNull(restored.page.getView());
      assertEquals(
          "Unknown environment must not recreate an old terminal/plugin view",
          0,
          SavedPage.viewsCreated);
      assertEquals(0, Controller.startRequests);
      assertNull(nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void unknownSessionRebindsStalePreparationPageWithoutExtractOrAutomaticWebEntry()
      throws Exception {
    var restored = restoreLaunch(true);
    try {
      Controller.unresolved = true;
      showMain(restored, false);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertNotSame(restored.page, current);
      assertFalse(preparationPage(current));
      assertNull("Unknown session must remain on Main", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  @Test
  public void openWebIntentSurvivesReplacementOfStalePreparationPage() throws Exception {
    var restored = restoreLaunch(true);
    try {
      restored.main.getIntent().putExtra("open_web", true);
      showMain(restored, false);
      Fragment current =
          restored.main.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
      assertNotSame(restored.page, current);
      assertFalse(preparationPage(current));
      var pending = LaunchFragment.class.getDeclaredField("pendingAutoEnter");
      pending.setAccessible(true);
      assertTrue("The replacement must keep the explicit request", pending.getBoolean(current));
      assertFalse(restored.main.getIntent().hasExtra("open_web"));
      assertNull("No runtime is ready, so no Web page may open", nextActivity(restored.main));
    } finally {
      restored.host.pause().stop().destroy();
    }
  }

  private static RestoredLaunch restoreLaunch(boolean preparation) {
    LaunchFragment first = new LaunchFragment();
    Bundle args = new Bundle();
    args.putBoolean(LaunchFragment.ARG_PREPARE_LOCAL, preparation);
    first.setArguments(args);
    var restored = restorePage(first);
    assertTrue(restored.page instanceof LaunchFragment);
    assertEquals(preparation, preparationPage(restored.page));
    return restored;
  }

  private static RestoredLaunch restorePage(Fragment first) {
    Intent launcher =
        new Intent(RuntimeEnvironment.getApplication(), MainActivity.class)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER);
    var firstHost =
        Robolectric.buildActivity(MainActivity.class, launcher).create().start().resume();
    firstHost
        .get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, first, "restored-launch")
        .commitNow();
    Bundle saved = new Bundle();
    firstHost.pause().saveInstanceState(saved).stop().destroy();
    var host =
        Robolectric.buildActivity(MainActivity.class, new Intent(launcher))
            .create(saved)
            .start()
            .resume();
    MainActivity main = host.get();
    Fragment page = main.getSupportFragmentManager().findFragmentByTag("restored-launch");
    assertNotNull(page);
    assertNull("The gate must hold the restored view", page.getView());
    while (nextActivity(main) != null) {}
    return new RestoredLaunch(host, main, page, saved);
  }

  private static boolean preparationPage(Fragment page) {
    return page.getArguments() != null
        && page.getArguments().getBoolean(LaunchFragment.ARG_PREPARE_LOCAL, false);
  }

  private static Intent nextActivity(MainActivity main) {
    org.robolectric.shadows.ShadowActivity shadow = org.robolectric.shadow.api.Shadow.extract(main);
    return shadow.getNextStartedActivity();
  }

  private static void showMain(RestoredLaunch restored, boolean preparation) throws Exception {
    var setup = MainActivity.class.getDeclaredField("localSetupRequired");
    setup.setAccessible(true);
    setup.setBoolean(restored.main, preparation);
    var probe = MainActivity.class.getDeclaredField("restoreProbeStarted");
    probe.setAccessible(true);
    probe.setBoolean(restored.main, true);
    var show =
        MainActivity.class.getDeclaredMethod(
            "showMain", Bundle.class, ConfigStore.class, HarnessController.class);
    show.setAccessible(true);
    show.invoke(restored.main, restored.saved, new ConfigStore(restored.main), Controller.value);
    restored.main.getSupportFragmentManager().executePendingTransactions();
  }

  private record RestoredLaunch(
      org.robolectric.android.controller.ActivityController<MainActivity> host,
      MainActivity main,
      Fragment page,
      Bundle saved) {}
}
