package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.AppLocaleDispatch;
import com.deepseekharness.app.util.UiLanguagePreference;
import com.deepseekharness.app.util.UiText;
import java.util.Locale;

/**
 * 切换界面语言只重建 Activity；终端进程、Web 服务和业务任务保留。
 *
 * <p>语言来源有两条：用户显式选择支持的 BCP-47 语言，或「跟随系统」（默认）。
 * 后者用 {@link com.deepseekharness.app.util.SystemLanguage} 解析，而不是
 * {@code Locale.getDefault()} —— 本类会把默认 Locale 改成界面语言，读它会让
 * 「跟随系统」在切换一次之后自我锁死。
 */
public final class LanguageController {
  private LanguageController() {}

  /** Apply the resolved language to background resources and Activity delegates. */
  public static void apply(Context context) {
    String language = new ConfigStore(context).getUiLanguage();
    applyResources(context, language);
    Context app = context.getApplicationContext();
    AppLocaleDispatch.apply(
        Build.VERSION.SDK_INT,
        language,
        new AppLocaleDispatch.Writer() {
          @Override
          public void platform(String value) {
            Api33.setApplicationLocales(app, value);
          }

          @Override
          public void compat(String value) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(value));
          }
        });
  }

  /** Initial remote process preferences are read once; this path never writes app locales. */
  public static void initializeRemote(Context context) {
    UiText.setLanguage(new ConfigStore(context).getUiLanguage());
    applyRemote(context, null);
  }

  /** Read current platform/owner state, then update only this process; old launch Intents are unused. */
  public static String applyRemote(Context context, String currentOwnerLanguage) {
    String platform = Build.VERSION.SDK_INT >= 33 ? Api33.applicationLanguage(context) : null;
    String language =
        com.deepseekharness.app.util.RemoteLocalePolicy.resolve(
            currentOwnerLanguage, platform, UiText.language());
    applyResources(context, language);
    return language;
  }

  private static void applyResources(Context context, String language) {
    if (!UiLanguagePreference.isSupportedLanguage(language))
      throw new IllegalArgumentException("UI_LANGUAGE");
    UiText.setLanguage(language);
    Locale locale = Locale.forLanguageTag(language);
    Locale.setDefault(locale);
    Context app = context.getApplicationContext();
    Configuration configuration = new Configuration(app.getResources().getConfiguration());
    configuration.setLocale(locale);
    configuration.setLayoutDirection(locale);
    // 通知、悬浮界面和后台任务使用 Application 资源；Activity 由 AppCompat 更新。
    app.getResources().updateConfiguration(configuration, app.getResources().getDisplayMetrics());
    // Remote pages are ComponentActivity instances in another process, without an AppCompat
    // delegate.
    if (context.getResources() != app.getResources()) {
      Configuration activityConfiguration =
          new Configuration(context.getResources().getConfiguration());
      activityConfiguration.setLocale(locale);
      activityConfiguration.setLayoutDirection(locale);
      context
          .getResources()
          .updateConfiguration(activityConfiguration, context.getResources().getDisplayMetrics());
    }
  }

  /** Loaded only on API 33+, where Application can write the locale before any AppCompat Activity exists. */
  private static final class Api33 {
    static String applicationLanguage(Context context) {
      if (Build.VERSION.SDK_INT < 33) throw new IllegalStateException("LOCALE_MANAGER_API");
      android.app.LocaleManager manager = context.getSystemService(android.app.LocaleManager.class);
      if (manager == null) return null;
      android.os.LocaleList locales = manager.getApplicationLocales();
      return locales.isEmpty() ? null : locales.get(0).toLanguageTag();
    }

    static void setApplicationLocales(Context context, String language) {
      if (Build.VERSION.SDK_INT < 33) throw new IllegalStateException("LOCALE_MANAGER_API");
      android.app.LocaleManager manager = context.getSystemService(android.app.LocaleManager.class);
      if (manager == null) throw new IllegalStateException("LOCALE_MANAGER_UNAVAILABLE");
      manager.setApplicationLocales(new android.os.LocaleList(Locale.forLanguageTag(language)));
    }
  }

  /**
   * 保存语言偏好并立即应用。
   *
   * <p>接受 {@code system}：此时按当前系统语言落地为支持的语言，但保存的仍是
   * {@code system}，这样系统语言以后变了应用会跟着变。
   */
  public static void select(Context context, String preference) {
    if (!UiLanguagePreference.supported(preference)) return;
    ConfigStore config = new ConfigStore(context);
    if (preference.equals(config.getUiLanguagePreference())) return;
    config.setUiLanguage(preference);
    apply(context);
  }
}
