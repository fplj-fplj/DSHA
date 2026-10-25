package com.deepseekharness.app.remote.ui;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import java.util.List;
import java.util.Map;

/** Pure frame decoder shared by the camera path and real generated-QR fixtures. */
final class QrFrameDecoder {
  private QrFrameDecoder() {}

  static String decode(byte[] luminance, int width, int height) throws NotFoundException {
    if (width < 1
        || height < 1
        || width > 1280
        || height > 1280
        || luminance.length != Math.multiplyExact(width, height))
      throw new IllegalArgumentException("REMOTE_QR_FRAME_INVALID");
    var source = new PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false);
    MultiFormatReader reader = new MultiFormatReader();
    reader.setHints(
        Map.of(
            DecodeHintType.POSSIBLE_FORMATS,
            List.of(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER,
            Boolean.TRUE));
    try {
      return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source))).getText();
    } finally {
      reader.reset();
    }
  }
}
