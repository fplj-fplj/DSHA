package com.deepseekharness.app.util;

/** Remote UI reads live application language; it never republishes an old Intent as a preference. */
public final class RemoteLocalePolicy {
  private RemoteLocalePolicy() {}

  public static String resolve(
      String currentOwnerLanguage, String platformLanguage, String processLanguage) {
    String platform = UiLanguagePreference.canonicalLanguage(platformLanguage);
    if (platform != null) return platform;
    if (UiLanguagePreference.isSupportedLanguage(currentOwnerLanguage)) return currentOwnerLanguage;
    return UiLanguagePreference.isSupportedLanguage(processLanguage)
        ? processLanguage
        : UiLanguagePreference.ZH;
  }
}
