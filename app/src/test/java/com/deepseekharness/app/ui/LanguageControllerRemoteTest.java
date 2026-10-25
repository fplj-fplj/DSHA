package com.deepseekharness.app.ui;

import static org.junit.Assert.*;
import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.os.LocaleList;
import android.view.View;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Exercise Android locale state; a stale remote owner reply must never rewrite the app preference. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class LanguageControllerRemoteTest {
  private Locale original;
  private Context context;

  @Before
  public void prepare() {
    original = Locale.getDefault();
    context = RuntimeEnvironment.getApplication();
  }

  @After
  public void reset() {
    Locale.setDefault(original);
    UiText.setLanguage("zh");
  }

  @Test
  public void recreatedRemotePageCannotRestoreOldEnglishOverCurrentJapaneseAppLocale() {
    LocaleManager manager = context.getSystemService(LocaleManager.class);
    manager.setApplicationLocales(LocaleList.forLanguageTags("ja"));
    UiText.setLanguage("en");
    assertEquals("ja", LanguageController.applyRemote(context, "en"));
    assertEquals("ja", manager.getApplicationLocales().toLanguageTags());
    assertEquals("ja", UiText.language());
    assertEquals("ja", context.getResources().getConfiguration().getLocales().get(0).getLanguage());
  }

  @Test
  public void oldRemoteActivityIntentCannotWriteItsPreviousLanguageBackToThePackage() {
    LocaleManager manager = context.getSystemService(LocaleManager.class);
    manager.setApplicationLocales(LocaleList.forLanguageTags("ja"));
    Intent oldIntent =
        new Intent(context, com.deepseekharness.app.remote.gateway.RemoteWebActivity.class)
            .putExtra("remote_ui_language", "en");
    try (var activity =
        Robolectric.buildActivity(
                com.deepseekharness.app.remote.gateway.RemoteWebActivity.class, oldIntent)
            .create()) {
      assertEquals("ja", manager.getApplicationLocales().toLanguageTags());
      assertEquals("ja", UiText.language());
    }
  }

  @Test
  public void remoteResourceDirectionFollowsArabicAndReturnsToLtrWithoutWritingPlatformLocales() {
    LocaleManager manager = context.getSystemService(LocaleManager.class);
    manager.setApplicationLocales(LocaleList.forLanguageTags("ar"));
    LanguageController.applyRemote(context, "en");
    assertEquals(
        View.LAYOUT_DIRECTION_RTL, context.getResources().getConfiguration().getLayoutDirection());
    assertEquals("ar", manager.getApplicationLocales().toLanguageTags());
    manager.setApplicationLocales(LocaleList.forLanguageTags("fr"));
    LanguageController.applyRemote(context, "ar");
    assertEquals(
        View.LAYOUT_DIRECTION_LTR, context.getResources().getConfiguration().getLayoutDirection());
    assertEquals("fr", manager.getApplicationLocales().toLanguageTags());
  }

  @Test
  @Config(sdk = 30)
  public void oldApiUsesFreshSameUidOwnerLanguageWithoutAppCompatLocaleMutation() {
    UiText.setLanguage("en");
    String before =
        androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags();
    assertEquals("ko", LanguageController.applyRemote(context, "ko"));
    assertEquals("ko", UiText.language());
    assertEquals(
        before, androidx.appcompat.app.AppCompatDelegate.getApplicationLocales().toLanguageTags());
    assertEquals("ko", context.getResources().getConfiguration().getLocales().get(0).getLanguage());
  }
}
