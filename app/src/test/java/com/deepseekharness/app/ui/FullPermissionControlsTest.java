package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.ContextThemeWrapper;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DeviceGrantsTest;
import com.deepseekharness.app.util.NativeConsentPolicy;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual MaterialSwitch refresh and resume events must be read-only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class FullPermissionControlsTest {
  private static final String KEY = "native_global_consent_v1";
  private SharedPreferences preferences;
  private Context context;
  private String previousLanguage;

  @Before
  public void reset() throws Exception {
    new DeviceGrantsTest().isolateProcessStateForIndependentPreferenceFixtures();
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
    context =
        new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_DeepseekHarness);
    preferences = context.getSharedPreferences("dsha_device_grants", Context.MODE_PRIVATE);
    preferences.edit().clear().commit();
  }

  @After
  public void restoreLanguage() {
    UiText.setLanguage(previousLanguage);
  }

  private FullPermissionControls controls(AtomicInteger changes) {
    var host = new LinearLayout(context);
    host.addView(new TextView(context));
    host.addView(new TextView(context));
    var owner =
        new Fragment() {
          @Override
          public Context getContext() {
            return context;
          }
        };
    return new FullPermissionControls(owner, host, changes::incrementAndGet);
  }

  private static <T> T field(FullPermissionControls controls, String name, Class<T> type)
      throws Exception {
    var field = FullPermissionControls.class.getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(controls));
  }

  @Test
  public void creatingRefreshingAndResumingTheCheckedSwitchNeverPersists() throws Exception {
    String original = new NativeConsentPolicy(true, 17).encode();
    preferences.edit().putString(KEY, original).commit();
    var writes = new AtomicInteger();
    SharedPreferences.OnSharedPreferenceChangeListener listener =
        (store, key) -> writes.incrementAndGet();
    preferences.registerOnSharedPreferenceChangeListener(listener);
    var changes = new AtomicInteger();
    try (var controls = controls(changes)) {
      var toggle = field(controls, "global", MaterialSwitch.class);
      assertTrue(toggle.isChecked());
      for (int i = 0; i < 10; i++) {
        controls.refresh();
        controls.pause();
        controls.resume();
        controls.cancelForLocationChange();
      }
      org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowLooper>extract(
              android.os.Looper.getMainLooper())
          .idle();
      assertEquals(original, preferences.getString(KEY, null));
      assertEquals(0, writes.get());
      assertEquals(0, changes.get());
      var status = field(controls, "status", TextView.class).getText().toString();
      assertRootExplanation(status);
      assertFalse(status.contains("revision"));
      assertFalse(status.contains("NONE"));
    }
    preferences.unregisterOnSharedPreferenceChangeListener(listener);
  }

  @Test
  public void oldOffPreferenceDoesNotChangeOrdinaryCapabilityPolicy() throws Exception {
    String off = new NativeConsentPolicy(false, 18).encode();
    preferences.edit().putString(KEY, off).commit();
    var changes = new AtomicInteger();
    try (var controls = controls(changes)) {
      controls.refresh();
      var toggle = field(controls, "global", MaterialSwitch.class);
      assertTrue(toggle.isChecked());
      assertFalse(toggle.isEnabled());
      assertEquals(off, preferences.getString(KEY, null));
      assertEquals(0, changes.get());
      assertRootExplanation(field(controls, "status", TextView.class).getText().toString());
    }
  }

  @Test
  public void unconfiguredSettingSkipsSafetyConfirmationsWithoutCreatingARecord() throws Exception {
    try (var controls = controls(new AtomicInteger())) {
      assertTrue(field(controls, "global", MaterialSwitch.class).isChecked());
      assertRootExplanation(field(controls, "status", TextView.class).getText().toString());
      assertFalse(preferences.contains(KEY));
    }
  }

  private static void assertRootExplanation(String text) {
    assertTrue(text.contains("保留 Root 命令限制"));
    assertTrue(text.contains("不能通过全局免确认跳过检查"));
    assertTrue(text.contains("普通通道和文件接口沿用用户授权设置"));
  }
}
