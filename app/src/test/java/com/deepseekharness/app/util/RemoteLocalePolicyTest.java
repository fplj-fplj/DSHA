package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import org.junit.Test;

public final class RemoteLocalePolicyTest {
  @Test
  public void rebuiltRemotePageReadsCurrentPlatformInsteadOfItsOldProcessLanguage() {
    assertEquals("ja", RemoteLocalePolicy.resolve(null, "ja", "en"));
    assertEquals("zh-Hant", RemoteLocalePolicy.resolve(null, "zh-TW", "en"));
    assertEquals("ar", RemoteLocalePolicy.resolve(null, "ar-EG", "zh"));
  }

  @Test
  public void currentPlatformLocaleWinsAnyDelayedOwnerReply() {
    assertEquals("ja", RemoteLocalePolicy.resolve("en", "ja", "en"));
  }

  @Test
  public void sameUidOwnerStateUpdatesTheOldApiProcessWithoutPlatformLocales() {
    for (String language : UiLanguagePreference.LANGUAGES)
      assertEquals(language, RemoteLocalePolicy.resolve(language, null, "en"));
  }

  @Test
  public void unsupportedOrPreferenceValuesCannotBecomeRemoteUiLanguages() {
    for (String value : new String[] {"system", "invalid", "javascript:alert(1)", "en';x='", ""}) {
      assertEquals("ko", RemoteLocalePolicy.resolve(value, value, "ko"));
      assertEquals("zh", RemoteLocalePolicy.resolve(value, value, value));
    }
  }

  @Test
  public void api30ReusedProcessChangesOnlyWhenFreshOwnerReplyArrives() {
    String previous = RemoteLocalePolicy.resolve(null, null, "en");
    assertEquals("en", previous);
    assertEquals("ja", RemoteLocalePolicy.resolve("ja", null, previous));
    assertEquals("en", previous);
  }
}
