package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.DialogInterface;
import com.deepseekharness.app.R;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public final class RootShellDialogsTest {
  @Test
  public void enablingRootRequiresAnActualClickEvenWithGlobalAutoApproval() {
    try (var controller = Robolectric.buildActivity(Activity.class)) {
      Activity activity = controller.get();
      activity.setTheme(R.style.Theme_DeepseekHarness);
      controller.setup();
      AtomicInteger enabled = new AtomicInteger();
      var dialog = RootShellDialogs.explain(activity, enabled::incrementAndGet);
      assertTrue(dialog.isShowing());
      assertEquals(0, enabled.get());
      dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertEquals(1, enabled.get());
    }
  }

  @Test
  public void cancellationDoesNotEnableRoot() {
    try (var controller = Robolectric.buildActivity(Activity.class)) {
      Activity activity = controller.get();
      activity.setTheme(R.style.Theme_DeepseekHarness);
      controller.setup();
      AtomicInteger enabled = new AtomicInteger();
      var dialog = RootShellDialogs.explain(activity, enabled::incrementAndGet);
      dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertEquals(0, enabled.get());
      dialog = RootShellDialogs.explain(activity, enabled::incrementAndGet);
      dialog.cancel();
      org.robolectric.shadows.ShadowLooper.idleMainLooper();
      assertEquals(0, enabled.get());
    }
  }
}
