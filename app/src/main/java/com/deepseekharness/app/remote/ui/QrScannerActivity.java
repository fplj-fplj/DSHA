package com.deepseekharness.app.remote.ui;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Camera2 + local QR decoder. No Google Play services and no camera frame leaves this Activity. */
public final class QrScannerActivity extends AppCompatActivity {
  static final String RESULT = "dsha.remote.pairing.scan";
  private final Object cameraLock = new Object();
  private TextureView preview;
  private TextView status;
  private CameraDevice camera;
  private CameraCaptureSession capture;
  private ImageReader images;
  private Surface previewSurface;
  private HandlerThread cameraThread;
  private Handler cameraHandler;
  private boolean opening;
  private volatile boolean active, delivered;
  private volatile int cameraEpoch;
  private long lastDecode;
  private int autofocusMode = CaptureRequest.CONTROL_AF_MODE_OFF;
  private final androidx.activity.result.ActivityResultLauncher<String> permission =
      registerForActivityResult(
          new androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
          granted -> {
            if (granted) openCamera();
            else unavailable();
          });

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    setContentView(R.layout.build195_remote_scanner);
    WorkspacePage.insets(findViewById(R.id.remote_scanner_root));
    status = findViewById(R.id.remote_scanner_status);
    preview = findViewById(R.id.remote_camera_preview);
    findViewById(R.id.remote_scan_manual).setOnClickListener(v -> finish());
    preview.setSurfaceTextureListener(
        new TextureView.SurfaceTextureListener() {
          @Override
          public void onSurfaceTextureAvailable(
              @NonNull SurfaceTexture texture, int width, int height) {
            openCamera();
          }

          @Override
          public void onSurfaceTextureSizeChanged(
              @NonNull SurfaceTexture texture, int width, int height) {}

          @Override
          public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            closeCamera();
            return true;
          }

          @Override
          public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {}
        });
  }

  @Override
  protected void onStart() {
    super.onStart();
    active = true;
    cameraThread = new HandlerThread("remote-qr-camera");
    cameraThread.start();
    cameraHandler = new Handler(cameraThread.getLooper());
    if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.CAMERA);
    else if (preview.isAvailable()) openCamera();
  }

  @SuppressLint("MissingPermission") // Runtime permission is checked before every open.
  private void openCamera() {
    if (!active
        || delivered
        || !preview.isAvailable()
        || cameraHandler == null
        || androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) return;
    final int epoch;
    synchronized (cameraLock) {
      if (camera != null || opening) return;
      opening = true;
      epoch = cameraEpoch;
    }
    try {
      CameraManager cameras = (CameraManager) getSystemService(CAMERA_SERVICE);
      if (cameras == null) throw new IllegalStateException();
      String selected = null;
      for (String id : cameras.getCameraIdList()) {
        Integer facing =
            cameras.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
        if (selected == null) selected = id;
        if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
          selected = id;
          break;
        }
      }
      if (selected == null) throw new IllegalStateException();
      CameraCharacteristics characteristics = cameras.getCameraCharacteristics(selected);
      int[] focusModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
      autofocusMode = CaptureRequest.CONTROL_AF_MODE_OFF;
      if (focusModes != null)
        for (int mode : focusModes)
          if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) autofocusMode = mode;
      var map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
      if (map == null) throw new IllegalStateException();
      Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
      if (sizes == null || sizes.length == 0) throw new IllegalStateException();
      Size size =
          Arrays.stream(sizes)
              .filter(s -> s.getWidth() <= 1280 && s.getHeight() <= 1280)
              .filter(s -> Arrays.asList(map.getOutputSizes(SurfaceTexture.class)).contains(s))
              .max(Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()))
              .orElseThrow(IllegalStateException::new);
      ImageReader reader =
          ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.YUV_420_888, 2);
      synchronized (cameraLock) {
        if (!active || epoch != cameraEpoch) {
          reader.close();
          opening = false;
          return;
        }
        images = reader;
      }
      reader.setOnImageAvailableListener(frame -> decode(frame, epoch), cameraHandler);
      SurfaceTexture texture = preview.getSurfaceTexture();
      if (texture == null) throw new IllegalStateException();
      texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
      previewSurface = new Surface(texture);
      cameras.openCamera(
          selected,
          new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice device) {
              synchronized (cameraLock) {
                opening = false;
                if (!active || epoch != cameraEpoch) {
                  device.close();
                  return;
                }
                camera = device;
                startCapture(device, epoch);
              }
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice device) {
              device.close();
              failCamera(epoch);
            }

            @Override
            public void onError(@NonNull CameraDevice device, int error) {
              device.close();
              failCamera(epoch);
            }
          },
          cameraHandler);
    } catch (Exception unavailable) {
      closeCamera();
      unavailable();
    }
  }

  @SuppressWarnings("deprecation") // Camera2 works on the complete supported API range.
  private void startCapture(CameraDevice device, int epoch) {
    try {
      if (images == null || previewSurface == null) return;
      List<Surface> targets = List.of(previewSurface, images.getSurface());
      device.createCaptureSession(
          targets,
          new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
              synchronized (cameraLock) {
                if (!active || epoch != cameraEpoch || camera != device) {
                  session.close();
                  return;
                }
                capture = session;
                try {
                  CaptureRequest.Builder request =
                      device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                  for (Surface target : targets) request.addTarget(target);
                  request.set(CaptureRequest.CONTROL_AF_MODE, autofocusMode);
                  session.setRepeatingRequest(request.build(), null, cameraHandler);
                } catch (Exception failed) {
                  closeCamera();
                  unavailable();
                }
              }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
              session.close();
              failCamera(epoch);
            }
          },
          cameraHandler);
    } catch (Exception failed) {
      closeCamera();
      unavailable();
    }
  }

  private void decode(ImageReader reader, int epoch) {
    try (Image image = reader.acquireLatestImage()) {
      if (image == null || !active || delivered || epoch != cameraEpoch) return;
      long now = android.os.SystemClock.elapsedRealtime();
      if (now - lastDecode < 400) return;
      lastDecode = now;
      Image.Plane plane = image.getPlanes()[0];
      ByteBuffer buffer = plane.getBuffer();
      int width = image.getWidth(), height = image.getHeight();
      byte[] luminance = new byte[Math.multiplyExact(width, height)];
      int row = plane.getRowStride(), pixel = plane.getPixelStride();
      int base = buffer.position();
      for (int y = 0; y < height; y++)
        for (int x = 0; x < width; x++)
          luminance[y * width + x] = buffer.get(base + y * row + x * pixel);
      String text = QrFrameDecoder.decode(luminance, width, height);
      PairingTicket.parse(text, System.currentTimeMillis());
      final String result = text;
      delivered = true;
      runOnUiThread(
          () -> {
            if (!active || epoch != cameraEpoch || isFinishing() || isDestroyed()) {
              delivered = false;
              return;
            }
            setResult(RESULT_OK, new Intent().putExtra(RESULT, result));
            finish();
          });
    } catch (com.google.zxing.NotFoundException ignored) {
      // No QR in this frame. Continue without logging camera data.
    } catch (IllegalArgumentException invalid) {
      runOnUiThread(
          () -> {
            if (active) status.setText(R.string.remote_code_invalid);
          });
    } catch (RuntimeException unavailable) {
      // A stopped ImageReader can finish its final callback after onStop.
    }
  }

  private void unavailable() {
    runOnUiThread(
        () -> {
          if (active && status != null) status.setText(R.string.remote_camera_unavailable);
        });
  }

  private void failCamera(int epoch) {
    synchronized (cameraLock) {
      if (epoch != cameraEpoch) return;
      closeCamera();
    }
    unavailable();
  }

  private void closeCamera() {
    synchronized (cameraLock) {
      cameraEpoch++;
      opening = false;
      if (capture != null) {
        capture.close();
        capture = null;
      }
      if (camera != null) {
        camera.close();
        camera = null;
      }
      if (images != null) {
        images.close();
        images = null;
      }
      if (previewSurface != null) {
        previewSurface.release();
        previewSurface = null;
      }
    }
  }

  @Override
  protected void onStop() {
    active = false;
    closeCamera();
    if (cameraThread != null) {
      cameraThread.quitSafely();
      cameraThread = null;
    }
    cameraHandler = null;
    super.onStop();
  }
}
