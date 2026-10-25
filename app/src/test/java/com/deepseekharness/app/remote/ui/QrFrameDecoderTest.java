package com.deepseekharness.app.remote.ui;

import static org.junit.Assert.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.NotFoundException;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.Test;

public final class QrFrameDecoderTest {
  @Test
  public void generatedComputerQrDecodesPortraitLandscapeAndPairingIdentity() throws Exception {
    int size = 640;
    for (String code :
        java.util.List.of(
            PairingTicketTest.url(PairingTicketTest.json()),
            PairingTicketTest.refreshUrl(PairingTicketTest.refreshJson()))) {
      var bits = new QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size);
      byte[] frame = new byte[size * size];
      for (int y = 0; y < size; y++)
        for (int x = 0; x < size; x++) frame[y * size + x] = (byte) (bits.get(x, y) ? 0 : 255);
      for (int orientation = 0; orientation < 4; orientation++) {
        String decoded = QrFrameDecoder.decode(frame, size, size);
        assertEquals(
            PairingTicketTest.PEER, PairingTicket.parse(decoded, PairingTicketTest.NOW).peerId);
        byte[] rotated = new byte[frame.length];
        for (int y = 0; y < size; y++)
          for (int x = 0; x < size; x++) rotated[x * size + size - y - 1] = frame[y * size + x];
        frame = rotated;
      }
    }
  }

  @Test
  public void blankFrameDoesNotProducePairingOrFalseConnection() throws Exception {
    byte[] frame = new byte[640 * 480];
    java.util.Arrays.fill(frame, (byte) 255);
    try {
      QrFrameDecoder.decode(frame, 640, 480);
      fail("Blank frame decoded");
    } catch (NotFoundException expected) {
    }
  }
}
