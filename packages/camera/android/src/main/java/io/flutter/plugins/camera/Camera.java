package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import static io.flutter.plugins.camera.CameraUtils.computeBestPreviewSize;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import androidx.annotation.NonNull;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Camera {
  /**
   * Camera state: Showing camera preview.
   */
  private static final int STATE_PREVIEW = 0;

  /**
   * Camera state: Waiting for the focus to be locked.
   */
  private static final int STATE_WAITING_LOCK = 1;

  /**
   * Camera state: Waiting for the exposure to be precapture state.
   */
  private static final int STATE_WAITING_PRECAPTURE = 2;

  /**
   * Camera state: Waiting for the exposure state to be something other than precapture.
   */
  private static final int STATE_WAITING_NON_PRECAPTURE = 3;

  /**
   * Camera state: Picture was taken.
   */
  private static final int STATE_PICTURE_TAKEN = 4;

  private final SurfaceTextureEntry flutterTexture;
  private final CameraManager cameraManager;
  private final OrientationEventListener orientationEventListener;
  private final boolean isFrontFacing;
  private final int sensorOrientation;
  private final String cameraName;
  private final Size captureSize;
  private final Size previewSize;
  private final boolean enableAudio;
  private final boolean mFlashSupported;

  private CameraDevice cameraDevice;
  private CameraCaptureSession mCaptureSession;
  private ImageReader pictureImageReader;
  private ImageReader imageStreamReader;
  private DartMessenger dartMessenger;
  private CaptureRequest.Builder mPreviewRequestBuilder;
  private MediaRecorder mediaRecorder;
  private boolean recordingVideo;
  private CamcorderProfile recordingProfile;
  private int currentOrientation = ORIENTATION_UNKNOWN;

  private static final String TAG = "Camera2BasicFragment";


  private boolean mAutoFocus;
  private int mFlash = Constants.FLASH_OFF;

  private CameraCharacteristics mCameraCharacteristics;
  private int mWhiteBalance = Constants.WB_AUTO;

  // Mirrors camera.dart
  public enum ResolutionPreset {
    low,
    medium,
    high,
    veryHigh,
    ultraHigh,
    max,
  }

  public Camera(
      final Activity activity,
      final SurfaceTextureEntry flutterTexture,
      final DartMessenger dartMessenger,
      final String cameraName,
      final String resolutionPreset,
      final boolean enableAudio,
      final boolean autoFocusEnabled,
      final int flashMode
  )
      throws CameraAccessException {
    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }

    this.cameraName = cameraName;
    this.enableAudio = enableAudio;
    this.flutterTexture = flutterTexture;
    this.dartMessenger = dartMessenger;
    this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    this.mAutoFocus = autoFocusEnabled;
    this.mFlash = flashMode;

    orientationEventListener =
        new OrientationEventListener(activity.getApplicationContext()) {
          @Override
          public void onOrientationChanged(int i) {
            if (i == ORIENTATION_UNKNOWN) {
              return;
            }
            // Convert the raw deg angle to the nearest multiple of 90.
            currentOrientation = (int) Math.round(i / 90.0) * 90;
          }
        };
    orientationEventListener.enable();

    mCameraCharacteristics = cameraManager.getCameraCharacteristics(cameraName);
    StreamConfigurationMap streamConfigurationMap =
            mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

    // Check if the flash is supported.
    Boolean available = mCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
    mFlashSupported = available == null ? false : available;
    //noinspection ConstantConditions
    sensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    //noinspection ConstantConditions
    isFrontFacing =
            mCameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
    ResolutionPreset preset = ResolutionPreset.valueOf(resolutionPreset);
    recordingProfile =
        CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset);
    captureSize = new Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    previewSize = computeBestPreviewSize(cameraName, preset);
  }

  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    if (mediaRecorder != null) {
      mediaRecorder.release();
    }
    mediaRecorder = new MediaRecorder();

    // There's a specific order that mediaRecorder expects. Do not change the order
    // of these function calls.
    if (enableAudio) mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
    mediaRecorder.setOutputFormat(recordingProfile.fileFormat);
    if (enableAudio) mediaRecorder.setAudioEncoder(recordingProfile.audioCodec);
    mediaRecorder.setVideoEncoder(recordingProfile.videoCodec);
    mediaRecorder.setVideoEncodingBitRate(recordingProfile.videoBitRate);
    if (enableAudio) mediaRecorder.setAudioSamplingRate(recordingProfile.audioSampleRate);
    mediaRecorder.setVideoFrameRate(recordingProfile.videoFrameRate);
    mediaRecorder.setVideoSize(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    mediaRecorder.setOutputFile(outputFilePath);
    mediaRecorder.setOrientationHint(getMediaOrientation());

    mediaRecorder.prepare();
  }


  private void preparePictureImageReader() {
    if (pictureImageReader != null) {
      pictureImageReader.close();
    }
    pictureImageReader =
            ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

  }

  private void prepareImageStreamReader() {
    if (imageStreamReader != null) {
      imageStreamReader.close();
    }

    imageStreamReader =
            ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

  }

  @SuppressLint("MissingPermission")
  public void open(@NonNull final Result result) throws CameraAccessException {
    preparePictureImageReader();
    prepareImageStreamReader();

    cameraManager.openCamera(
        cameraName,
        new CameraDevice.StateCallback() {
          @Override
          public void onOpened(@NonNull CameraDevice device) {
            cameraDevice = device;
            try {
              startPreview();
            } catch (CameraAccessException e) {
              result.error("CameraAccess", e.getMessage(), null);
              close();
              return;
            }
            Map<String, Object> reply = new HashMap<>();
            reply.put("textureId", flutterTexture.id());
            reply.put("previewWidth", previewSize.getWidth());
            reply.put("previewHeight", previewSize.getHeight());
            result.success(reply);
          }

          @Override
          public void onClosed(@NonNull CameraDevice camera) {
            dartMessenger.sendCameraClosingEvent();
            super.onClosed(camera);
          }

          @Override
          public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            close();
            dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
          }

          @Override
          public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
            close();
            String errorDescription;
            switch (errorCode) {
              case ERROR_CAMERA_IN_USE:
                errorDescription = "The camera device is in use already.";
                break;
              case ERROR_MAX_CAMERAS_IN_USE:
                errorDescription = "Max cameras in use";
                break;
              case ERROR_CAMERA_DISABLED:
                errorDescription = "The camera device could not be opened due to a device policy.";
                break;
              case ERROR_CAMERA_DEVICE:
                errorDescription = "The camera device has encountered a fatal error";
                break;
              case ERROR_CAMERA_SERVICE:
                errorDescription = "The camera service has encountered a fatal error.";
                break;
              default:
                errorDescription = "Unknown camera error";
            }
            dartMessenger.send(DartMessenger.EventType.ERROR, errorDescription);
          }
        },
        null);
  }

  private void writeToFile(ByteBuffer buffer, File file) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      while (0 < buffer.remaining()) {
        outputStream.getChannel().write(buffer);
      }
    }
  }

  SurfaceTextureEntry getFlutterTexture() {
    return flutterTexture;
  }

  public void takePicture(String filePath, @NonNull final Result result) {
    final File file = new File(filePath);

    if (file.exists()) {
      result.error(
          "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
      return;
    }

    pictureImageReader.setOnImageAvailableListener(
        reader -> {
          try (Image image = reader.acquireLatestImage()) {
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            writeToFile(buffer, file);
            result.success(null);
          } catch (IOException e) {
            result.error("IOError", "Failed saving image", null);
          }
        },
        null);

    try {
      final CaptureRequest.Builder captureBuilder =
          cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      captureBuilder.addTarget(pictureImageReader.getSurface());

      captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
              mPreviewRequestBuilder.get(CaptureRequest.CONTROL_AF_MODE));

      if(mFlashSupported) {
        switch (mFlash) {
          case Constants.FLASH_OFF:
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_OFF);
            break;
          case Constants.FLASH_ON:
            captureBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            captureBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            break;
          case Constants.FLASH_TORCH:
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON);
            captureBuilder.set(CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_TORCH);
            break;
          case Constants.FLASH_AUTO:
          case Constants.FLASH_RED_EYE:
            captureBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            break;
        }
      }

      captureBuilder.set(
              CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
              CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);

      captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());


      mCaptureSession.capture(
          captureBuilder.build(),
          new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureFailed(
                @NonNull CameraCaptureSession session,
                @NonNull CaptureRequest request,
                @NonNull CaptureFailure failure) {
              String reason;
              switch (failure.getReason()) {
                case CaptureFailure.REASON_ERROR:
                  reason = "An error happened in the framework";
                  break;
                case CaptureFailure.REASON_FLUSHED:
                  reason = "The capture has failed due to an abortCaptures() call";
                  break;
                default:
                  reason = "Unknown reason";
              }
              result.error("captureFailure", reason, null);
            }
          },
          null);
    } catch (CameraAccessException e) {
      result.error("cameraAccess", e.getMessage(), null);
    }
  }

  private void createCaptureSession(int templateType, Surface... surfaces)
      throws CameraAccessException {
    createCaptureSession(templateType, null, surfaces);
  }

  private void createCaptureSession(
      int templateType, Runnable onSuccessCallback, Surface... surfaces)
      throws CameraAccessException {
    // Close any existing capture session.
    closeCaptureSession();

    // Create a new capture builder.
    mPreviewRequestBuilder = cameraDevice.createCaptureRequest(templateType);

    // Build Flutter surface to render to
    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
    Surface flutterSurface = new Surface(surfaceTexture);
    mPreviewRequestBuilder.addTarget(flutterSurface);

    List<Surface> remainingSurfaces = Arrays.asList(surfaces);
    if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
      // If it is not preview mode, add all surfaces as targets.
      for (Surface surface : remainingSurfaces) {
        mPreviewRequestBuilder.addTarget(surface);
      }
    }

    // Prepare the callback
    CameraCaptureSession.StateCallback callback =
        new CameraCaptureSession.StateCallback() {
          @Override
          public void onConfigured(@NonNull CameraCaptureSession session) {
            try {
              if (cameraDevice == null) {
                dartMessenger.send(
                    DartMessenger.EventType.ERROR, "The camera was closed during configuration.");
                return;
              }
              mCaptureSession = session;

              updateAutoFocus();
              updateFlash();
              updateWhiteBalance();

              mPreviewRequestBuilder.set(
                  CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
              mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, null);


              if (onSuccessCallback != null) {
                onSuccessCallback.run();
              }
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
              dartMessenger.send(DartMessenger.EventType.ERROR, e.getMessage());
            }
          }

          @Override
          public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            dartMessenger.send(
                DartMessenger.EventType.ERROR, "Failed to configure camera session.");
          }
        };

    // Collect all surfaces we want to render to.
    List<Surface> surfaceList = new ArrayList<>();
    surfaceList.add(flutterSurface);
    surfaceList.addAll(remainingSurfaces);
    // Start the session
    cameraDevice.createCaptureSession(surfaceList, callback, null);
  }


  /**
   * Updates the internal state of white balance to {@link #mWhiteBalance}.
   */
  void updateWhiteBalance() {
    switch (mWhiteBalance) {
      case Constants.WB_AUTO:
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_AUTO);
        break;
      case Constants.WB_CLOUDY:
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT);
        break;
      case Constants.WB_FLUORESCENT:
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT);
        break;
      case Constants.WB_INCANDESCENT:
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT);
        break;
      case Constants.WB_SHADOW:
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_SHADE);
        break;
      case Constants.WB_SUNNY:
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE,
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
        break;
    }
  }

  void setFlash(int flash) {
    if (mFlash == flash) {
      return;
    }
    int saved = mFlash;
    mFlash = flash;
    if (mPreviewRequestBuilder != null) {
      updateFlash();
      if (mCaptureSession != null) {
        try {
          mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(),
                  null, null);
        } catch (CameraAccessException e) {
          mFlash = saved; // Revert
        }
      }
    }
  }


  /**
   * Updates the internal state of flash to {@link #mFlash}.
   */
  void updateFlash() {
    if(mFlashSupported) {
      switch (mFlash) {
        case Constants.FLASH_OFF:
          mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                  CaptureRequest.CONTROL_AE_MODE_ON);
          mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                  CaptureRequest.FLASH_MODE_OFF);
          break;
        case Constants.FLASH_ON:
          mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                  CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
          mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                  CaptureRequest.FLASH_MODE_OFF);
          break;
        case Constants.FLASH_TORCH:
          mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                  CaptureRequest.CONTROL_AE_MODE_ON);
          mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                  CaptureRequest.FLASH_MODE_TORCH);
          break;
        case Constants.FLASH_AUTO:
          mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                  CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
          mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                  CaptureRequest.FLASH_MODE_OFF);
          break;
        case Constants.FLASH_RED_EYE:
          mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                  CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE);
          mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                  CaptureRequest.FLASH_MODE_OFF);
          break;
      }
    }
  }

  //NEW THINGIES
  void updateAutoFocus() {
    if (mAutoFocus) {
      int[] modes = mCameraCharacteristics.get(
              CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
      // Auto focus is not supported
      if (modes == null || modes.length == 0 ||
              (modes.length == 1 && modes[0] == CameraCharacteristics.CONTROL_AF_MODE_OFF)) {
        mAutoFocus = false;
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_OFF);
      } else {
        mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
      }
    } else {
      mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
              CaptureRequest.CONTROL_AF_MODE_OFF);
    }
  }

  void setAutoFocus(boolean autoFocus) {
    if (mAutoFocus == autoFocus) {
      return;
    }
    Log.d( "AUTO FOCUD", "setAutoFocus");

    mAutoFocus = autoFocus;
    if (mPreviewRequestBuilder != null) {
      updateAutoFocus();
      if (mCaptureSession != null) {
        try {
          //mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), mCaptureCallback, null);
          mCaptureSession.setRepeatingRequest(mPreviewRequestBuilder.build(), null, null);

        } catch (CameraAccessException e) {
          mAutoFocus = !mAutoFocus; // Revert
        }
      }
    }
  }

  public void startVideoRecording(String filePath, Result result) {
    if (new File(filePath).exists()) {
      result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
      return;
    }
    try {
      prepareMediaRecorder(filePath);
      recordingVideo = true;
      createCaptureSession(
          CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface());
      result.success(null);
    } catch (CameraAccessException | IOException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      recordingVideo = false;
      mediaRecorder.stop();
      mediaRecorder.reset();
      startPreview();
      result.success(null);
    } catch (CameraAccessException | IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.resume();
      } else {
        result.error(
            "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void startPreview() throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());
  }

  public void startPreviewWithImageStream(EventChannel imageStreamChannel)
      throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_RECORD, imageStreamReader.getSurface());

    imageStreamChannel.setStreamHandler(
        new EventChannel.StreamHandler() {
          @Override
          public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
            setImageStreamImageAvailableListener(imageStreamSink);
          }

          @Override
          public void onCancel(Object o) {
            imageStreamReader.setOnImageAvailableListener(null, null);
          }
        });
  }

  private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
    imageStreamReader.setOnImageAvailableListener(
        reader -> {
          Image img = reader.acquireLatestImage();
          if (img == null) return;

          List<Map<String, Object>> planes = new ArrayList<>();
          for (Image.Plane plane : img.getPlanes()) {
            ByteBuffer buffer = plane.getBuffer();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes, 0, bytes.length);

            Map<String, Object> planeBuffer = new HashMap<>();
            planeBuffer.put("bytesPerRow", plane.getRowStride());
            planeBuffer.put("bytesPerPixel", plane.getPixelStride());
            planeBuffer.put("bytes", bytes);

            planes.add(planeBuffer);
          }

          Map<String, Object> imageBuffer = new HashMap<>();
          imageBuffer.put("width", img.getWidth());
          imageBuffer.put("height", img.getHeight());
          imageBuffer.put("format", img.getFormat());
          imageBuffer.put("planes", planes);

          imageStreamSink.success(imageBuffer);
          img.close();
        },
        null);
  }

  private void closeCaptureSession() {
    if (mCaptureSession != null) {
      mCaptureSession.close();
      mCaptureSession = null;
    }
  }

  public void close() {
    closeCaptureSession();

    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }
    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }
  }

  public void dispose() {
    close();
    flutterTexture.release();
    orientationEventListener.disable();
  }

  private int getMediaOrientation() {
    final int sensorOrientationOffset =
        (currentOrientation == ORIENTATION_UNKNOWN)
            ? 0
            : (isFrontFacing) ? -currentOrientation : currentOrientation;
    return (sensorOrientationOffset + sensorOrientation + 360) % 360;
  }
}
