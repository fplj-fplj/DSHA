package com.deepseekharness.app.remote;

import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;

/** Windows File.renameTo cannot replace a file; Android's POSIX rename can. Emulate that
 * one platform operation, while exercising the real AtomicFile read/start/failure paths. */
@Implements(AtomicFile.class)
public final class PosixAtomicFileShadow {
  @RealObject private AtomicFile actual;
  private static final AtomicBoolean failNext = new AtomicBoolean();

  static void failNextCommit() {
    failNext.set(true);
  }

  @Resetter
  public static void reset() {
    failNext.set(false);
  }

  @Implementation
  protected void finishWrite(FileOutputStream stream) {
    if (stream == null) return;
    try {
      stream.getFD().sync();
      stream.close();
      if (failNext.getAndSet(false)) return; // Real AtomicFile's silent failed-rename behavior.
      File base = actual.getBaseFile();
      Files.move(
          new File(base + ".new").toPath(),
          base.toPath(),
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException failure) {
      throw new IllegalStateException("ATOMIC_FILE_FIXTURE_FAILURE", failure);
    }
  }
}
