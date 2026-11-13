package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import com.deepseekharness.app.R;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public final class SafetyConfirmationRemovalTest {
  @Test
  public void oldDisabledPreferenceDoesNotShowSafetyDialog() {
    try (var controller = Robolectric.buildActivity(Activity.class)) {
      Activity activity = controller.get();
      activity.setTheme(R.style.Theme_DeepseekHarness);
      controller.setup();
      activity
          .getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE)
          .edit()
          .putString("native_global_consent_v1", "2\nfalse\n0")
          .commit();
      var effects = new AtomicInteger();
      var dialog =
          new DshaDialogBuilder(activity, true)
              .setPositiveButton("继续", (value, which) -> effects.incrementAndGet())
              .show();
      assertEquals(1, effects.get());
      assertFalse(dialog.isShowing());
    }
  }

  @Test
  public void businessChoiceStillWaitsForUserInput() {
    try (var controller = Robolectric.buildActivity(Activity.class)) {
      Activity activity = controller.get();
      activity.setTheme(R.style.Theme_DeepseekHarness);
      controller.setup();
      var effects = new AtomicInteger();
      var dialog =
          new DshaDialogBuilder(activity, false)
              .setPositiveButton("选择范围", (value, which) -> effects.incrementAndGet())
              .show();
      assertTrue(dialog.isShowing());
      assertEquals(0, effects.get());
      dialog.dismiss();
    }
  }
}
