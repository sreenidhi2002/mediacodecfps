package com.example.testingsurfacetexture;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.text.SimpleDateFormat;
import java.util.Date;


public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private TextureView textureView;
    private Button recordButton;

    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;
    private MediaCodec mediaCodec;
    private EncoderSurface encoderSurface;
    private MediaMuxer mediaMuxer;

    private boolean isRecording = false;
    private Size videoSize;
    private String cameraId;
    private int trackIndex;
    private boolean muxerStarted;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.textureView);
        recordButton = findViewById(R.id.recordButton);

        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRecording) {
                    stopRecordingVideo();
                } else {
                    startRecordingVideo();
                }
            }
        });

        textureView.setSurfaceTextureListener(surfaceTextureListener);

        checkPermissions();
    }

    private void checkPermissions() {
        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, permissions, 101);
        }
    }

    private void startRecordingVideo() {
        Log.d(TAG, "startRecordingVideo: starting video recording");
        if (cameraDevice == null || !textureView.isAvailable() || videoSize == null) {
            Log.e(TAG, "startRecordingVideo: CameraDevice is null, TextureView is not available, or VideoSize is null");
            return;
        }
        try {
            closePreviewSession();
            setUpMediaCodec();

            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface previewSurface = new Surface(texture);
            Surface recordSurface = mediaCodec.createInputSurface();

            // First configure the preview surface
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            previewRequestBuilder.addTarget(previewSurface);

            Log.d(TAG, "startRecordingVideo: creating capture session for preview");
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigured: Capture session configured for preview");
                            if (cameraDevice == null) {
                                Log.e(TAG, "onConfigured: CameraDevice is null when onConfigured called for preview");
                                return;
                            }
                            cameraCaptureSession = session;
                            updatePreview();

                            // Now add the recording surface
                            addRecordingSurface(previewSurface, recordSurface);
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "onConfigureFailed: Camera configuration failed for preview");
                            Toast.makeText(MainActivity.this, "Camera configuration failed for preview", Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException | IOException e) {
            Log.e(TAG, "Error starting recording", e);
            e.printStackTrace();
        }
    }

    private void addRecordingSurface(Surface previewSurface, Surface recordSurface) {
        try {
            previewRequestBuilder.addTarget(recordSurface);
            Log.d(TAG, "addRecordingSurface: adding recording surface");
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigured: Capture session configured with recording surface");
                            if (cameraDevice == null) {
                                Log.e(TAG, "onConfigured: CameraDevice is null when onConfigured called for recording surface");
                                return;
                            }
                            cameraCaptureSession = session;
                            updatePreview();
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    isRecording = true;
                                    mediaCodec.start();
                                    recordButton.setText("Stop Recording");
                                    Log.d(TAG, "startRecordingVideo: video recording started");
                                }
                            });
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "onConfigureFailed: Camera configuration failed with recording surface");
                            Toast.makeText(MainActivity.this, "Camera configuration failed with recording surface", Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error adding recording surface", e);
            e.printStackTrace();
        }
    }




    private void stopRecordingVideo() {
        Log.d(TAG, "stopRecordingVideo: stopping video recording");
        try {
            if (mediaCodec != null) {
                mediaCodec.stop();
                mediaCodec.release();
            }
            if (encoderSurface != null) {
                encoderSurface.release();
            }
            if (mediaMuxer != null) {
                mediaMuxer.stop();
                mediaMuxer.release();
            }
        } catch (Exception e) {
            Log.e(TAG, "stopRecordingVideo: Error stopping recording", e);
        }

        isRecording = false;
        recordButton.setText("Start Recording");

        startPreview();
    }

    public static String getCurrentDateTimeFileName() {
        // Get the current date and time
        Date now = new Date();

        // Define the format for the date and time
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss");

        // Format the current date and time
        String formattedDate = sdf.format(now);

        // Create the filename using the formatted date and time

        return "/video_" + formattedDate + ".mp4";
    }

    private void setUpMediaCodec() throws IOException {
        Log.d(TAG, "setUpMediaCodec: setting up media codec");
        mediaCodec = MediaCodec.createEncoderByType("video/avc");
        MediaFormat format = MediaFormat.createVideoFormat("video/avc", videoSize.getWidth(), videoSize.getHeight());
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 10000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        String fileName = getCurrentDateTimeFileName();
        mediaMuxer = new MediaMuxer(getExternalFilesDir(null).getAbsolutePath() + fileName, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        // Set the orientation hint based on the device's rotation
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int orientationHint;
        switch (rotation) {
            case Surface.ROTATION_0:
                orientationHint = 90; // Adjust as per your need, usually 90 or 270
                break;
            case Surface.ROTATION_90:
                orientationHint = 0;
                break;
            case Surface.ROTATION_180:
                orientationHint = 270; // Adjust as per your need, usually 90 or 270
                break;
            case Surface.ROTATION_270:
                orientationHint = 180;
                break;
            default:
                orientationHint = 90; // Default orientation hint
        }
        mediaMuxer.setOrientationHint(orientationHint);


        trackIndex = -1;
        muxerStarted = false;

        mediaCodec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                // No input buffer is needed for this use case
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.d(TAG, "onOutputBufferAvailable: Output buffer available");
                ByteBuffer encodedData = mediaCodec.getOutputBuffer(index);
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + index + " was null");
                }

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    info.size = 0;
                }

                if (info.size != 0) {
                    if (!muxerStarted) {
                        MediaFormat newFormat = mediaCodec.getOutputFormat();
                        trackIndex = mediaMuxer.addTrack(newFormat);
                        mediaMuxer.start();
                        muxerStarted = true;
                    }

                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    mediaMuxer.writeSampleData(trackIndex, encodedData, info);
                }

                mediaCodec.releaseOutputBuffer(index, false);

                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.d(TAG, "onOutputBufferAvailable: End of stream");
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "onError: MediaCodec error", e);
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                if (muxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mediaCodec.getOutputFormat();
                trackIndex = mediaMuxer.addTrack(newFormat);
                mediaMuxer.start();
                muxerStarted = true;
                Log.d(TAG, "onOutputFormatChanged: Output format changed, muxer started");
            }
        });
    }

    private void startPreview() {
        Log.d(TAG, "startPreview: starting camera preview");
        if (cameraDevice == null || !textureView.isAvailable()) {
            Log.e(TAG, "startPreview: CameraDevice is null or TextureView is not available");
            return;
        }
        try {
            closePreviewSession();
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
            Surface surface = new Surface(texture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            Log.d(TAG, "startPreview: creating capture session for preview");
            cameraDevice.createCaptureSession(Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigured: Capture session configured for preview");
                            if (cameraDevice == null) {
                                Log.e(TAG, "onConfigured: CameraDevice is null when onConfigured called for preview");
                                return;
                            }
                            cameraCaptureSession = session;
                            updatePreview();
                        }

                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.e(TAG, "onConfigureFailed: Camera configuration failed for preview");
                            Toast.makeText(MainActivity.this, "Camera configuration failed for preview", Toast.LENGTH_SHORT).show();
                        }
                    }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error starting preview", e);
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        if (cameraDevice == null) {
            return;
        }
        try {
            cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error updating preview", e);
            e.printStackTrace();
        }
    }

    private void closePreviewSession() {
        if (cameraCaptureSession != null) {
            cameraCaptureSession.close();
            cameraCaptureSession = null;
        }
    }

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private void logSupportedSizes(CameraCharacteristics characteristics) {
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);
            for (Size size : outputSizes) {
                Log.d(TAG, "Supported size: " + size.toString());
            }
        }
    }

    private void openCamera(int width, int height) {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        try {
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            logSupportedSizes(characteristics);  // Log supported sizes
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                videoSize = new Size(1920, 1080); // Use 1920x1080 as the video size
            }
            configureTransform(width, height);
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
                return;
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error opening camera", e);
            e.printStackTrace();
        }
    }



    private void configureTransform(int viewWidth, int viewHeight) {
        if (textureView == null || videoSize == null) {
            return;
        }
        Matrix matrix = new Matrix();
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int viewRotation = 90;
        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
            viewRotation = 90;
        } else if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            viewRotation = 0;
        }
        textureView.setTransform(matrix);
    }

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
            cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
            MainActivity.this.finish();
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 101) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permissions granted
            } else {
                // Permissions denied
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        closePreviewSession();
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (mediaCodec != null) {
            mediaCodec.release();
            mediaCodec = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
    }
}










//package com.example.testingsurfacetexture;
//
//
//import android.Manifest;
//import android.content.pm.PackageManager;
//import android.graphics.Matrix;
//import android.graphics.SurfaceTexture;
//import android.hardware.camera2.CameraAccessException;
//import android.hardware.camera2.CameraCaptureSession;
//import android.hardware.camera2.CameraCharacteristics;
//import android.hardware.camera2.CameraDevice;
//import android.hardware.camera2.CameraManager;
//import android.hardware.camera2.CaptureRequest;
//import android.hardware.camera2.params.StreamConfigurationMap;
//import android.media.MediaRecorder;
//import android.os.Bundle;
//import android.util.Size;
//import android.view.Surface;
//import android.view.TextureView;
//import android.view.View;
//import android.widget.Button;
//import android.widget.Toast;
//
//import androidx.annotation.NonNull;
//import androidx.appcompat.app.AppCompatActivity;
//import androidx.core.app.ActivityCompat;
//import androidx.core.content.ContextCompat;
//
//import java.io.IOException;
//import java.util.Arrays;
//
//public class MainActivity extends AppCompatActivity {
//
//    private TextureView textureView;
//    private Button recordButton;
//
//    private CameraDevice cameraDevice;
//    private CaptureRequest.Builder previewRequestBuilder;
//    private CameraCaptureSession cameraCaptureSession;
//    private MediaRecorder mediaRecorder;
//
//    private boolean isRecording = false;
//    private Size videoSize;
//
//    private String cameraId;
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_main);
//
//        textureView = findViewById(R.id.textureView);
//        recordButton = findViewById(R.id.recordButton);
//
//        recordButton.setOnClickListener(new View.OnClickListener() {
//            @Override
//            public void onClick(View v) {
//                if (isRecording) {
//                    stopRecordingVideo();
//                } else {
//                    startRecordingVideo();
//                }
//            }
//        });
//
//        textureView.setSurfaceTextureListener(surfaceTextureListener);
//
//        checkPermissions();
//    }
//
//    private void checkPermissions() {
//        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};
//        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
//                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
//                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
//            ActivityCompat.requestPermissions(this, permissions, 101);
//        }
//    }
//
//    private void startRecordingVideo() {
//        if (cameraDevice == null || !textureView.isAvailable() || videoSize == null) {
//            return;
//        }
//        try {
//            closePreviewSession();
//            setUpMediaRecorder();
//
//            SurfaceTexture texture = textureView.getSurfaceTexture();
//            assert texture != null;
//            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
//            Surface previewSurface = new Surface(texture);
//            Surface recordSurface = mediaRecorder.getSurface();
//            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
//            previewRequestBuilder.addTarget(previewSurface);
//            previewRequestBuilder.addTarget(recordSurface);
//
//            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface),
//                    new CameraCaptureSession.StateCallback() {
//                        @Override
//                        public void onConfigured(@NonNull CameraCaptureSession session) {
//                            cameraCaptureSession = session;
//                            updatePreview();
//                            runOnUiThread(new Runnable() {
//                                @Override
//                                public void run() {
//                                    isRecording = true;
//                                    mediaRecorder.start();
//                                    recordButton.setText("Stop Recording");
//                                }
//                            });
//                        }
//
//                        @Override
//                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
//                            Toast.makeText(MainActivity.this, "Failed", Toast.LENGTH_SHORT).show();
//                        }
//                    }, null);
//        } catch (CameraAccessException | IOException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void stopRecordingVideo() {
//        mediaRecorder.stop();
//        mediaRecorder.reset();
//
//        isRecording = false;
//        recordButton.setText("Start Recording");
//
//        startPreview();
//    }
//
//    private void setUpMediaRecorder() throws IOException {
//        mediaRecorder = new MediaRecorder();
//        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
//        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
//        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
//        mediaRecorder.setOutputFile(getExternalFilesDir(null).getAbsolutePath() + "/video.mp4");
//        mediaRecorder.setVideoEncodingBitRate(10000000);
//        mediaRecorder.setVideoFrameRate(30);
//        mediaRecorder.setVideoSize(videoSize.getWidth(), videoSize.getHeight());
//        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
//
//        // Set the correct orientation hint
//        int rotation = getWindowManager().getDefaultDisplay().getRotation();
//        switch (rotation) {
//            case Surface.ROTATION_0:
//                mediaRecorder.setOrientationHint(90);
//                break;
//            case Surface.ROTATION_90:
//                mediaRecorder.setOrientationHint(0);
//                break;
//            case Surface.ROTATION_180:
//                mediaRecorder.setOrientationHint(270);
//                break;
//            case Surface.ROTATION_270:
//                mediaRecorder.setOrientationHint(180);
//                break;
//        }
//
//        mediaRecorder.prepare();
//    }
//
//
//    private void startPreview() {
//        if (cameraDevice == null || !textureView.isAvailable()) {
//            return;
//        }
//        try {
//            closePreviewSession();
//            SurfaceTexture texture = textureView.getSurfaceTexture();
//            assert texture != null;
//            texture.setDefaultBufferSize(videoSize.getWidth(), videoSize.getHeight());
//            Surface surface = new Surface(texture);
//            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
//            previewRequestBuilder.addTarget(surface);
//
//            cameraDevice.createCaptureSession(Arrays.asList(surface),
//                    new CameraCaptureSession.StateCallback() {
//                        @Override
//                        public void onConfigured(@NonNull CameraCaptureSession session) {
//                            cameraCaptureSession = session;
//                            updatePreview();
//                        }
//
//                        @Override
//                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
//                            Toast.makeText(MainActivity.this, "Failed", Toast.LENGTH_SHORT).show();
//                        }
//                    }, null);
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void updatePreview() {
//        if (cameraDevice == null) {
//            return;
//        }
//        try {
//            cameraCaptureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null);
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void closePreviewSession() {
//        if (cameraCaptureSession != null) {
//            cameraCaptureSession.close();
//            cameraCaptureSession = null;
//        }
//    }
//
//    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
//        @Override
//        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
//            openCamera(width, height);
//        }
//
//        @Override
//        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
//            configureTransform(width, height);
//        }
//
//        @Override
//        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
//            return true;
//        }
//
//        @Override
//        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
//        }
//    };
//
//    private void openCamera(int width, int height) {
//        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
//        try {
//            cameraId = manager.getCameraIdList()[0];
//            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
//            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//            if (map != null) {
//                videoSize = map.getOutputSizes(MediaRecorder.class)[0];
//            }
//            configureTransform(width, height);
//            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
//                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 101);
//                return;
//            }
//            manager.openCamera(cameraId, stateCallback, null);
//        } catch (CameraAccessException e) {
//            e.printStackTrace();
//        }
//    }
//
//    private void configureTransform(int viewWidth, int viewHeight) {
//        if (textureView == null || videoSize == null) {
//            return;
//        }
//        Matrix matrix = new Matrix();
//        int rotation = getWindowManager().getDefaultDisplay().getRotation();
//        int viewRotation = 90;
//        if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) {
//            viewRotation = 90;
//        } else if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
//            viewRotation = 0;
//        }
//        textureView.setTransform(matrix);
//    }
//
//    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
//        @Override
//        public void onOpened(@NonNull CameraDevice camera) {
//            cameraDevice = camera;
//            startPreview();
//        }
//
//        @Override
//        public void onDisconnected(@NonNull CameraDevice camera) {
//            cameraDevice.close();
//            cameraDevice = null;
//        }
//
//        @Override
//        public void onError(@NonNull CameraDevice camera, int error) {
//            cameraDevice.close();
//            cameraDevice = null;
//            MainActivity.this.finish();
//        }
//    };
//
//    @Override
//    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//        if (requestCode == 101) {
//            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                // Permissions granted
//            } else {
//                // Permissions denied
//            }
//        }
//    }
//
//    @Override
//    protected void onPause() {
//        super.onPause();
//        closePreviewSession();
//        if (cameraDevice != null) {
//            cameraDevice.close();
//            cameraDevice = null;
//        }
//        if (mediaRecorder != null) {
//            mediaRecorder.release();
//            mediaRecorder = null;
//        }
//    }
//}
//
//
//
////package com.example.testingsurfacetexture;
////
////import android.Manifest;
////import android.content.pm.PackageManager;
////import android.graphics.SurfaceTexture;
////import android.hardware.Camera;
////import android.media.MediaRecorder;
////import android.os.Bundle;
////import android.util.Log;
////import android.view.SurfaceHolder;
////import android.view.SurfaceView;
////import android.widget.Button;
////
////import androidx.annotation.NonNull;
////import androidx.appcompat.app.AppCompatActivity;
////import androidx.core.app.ActivityCompat;
////import androidx.core.content.ContextCompat;
////
////import java.io.IOException;
////
////public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
////
////    private SurfaceView surfaceView;
////    private SurfaceHolder surfaceHolder;
////    private Camera camera;
////    private MediaRecorder mediaRecorder;
////    private Button recordButton;
////    private boolean isRecording = false;
////
////    @Override
////    protected void onCreate(Bundle savedInstanceState) {
////        super.onCreate(savedInstanceState);
////        setContentView(R.layout.activity_main);
////
////        surfaceView = findViewById(R.id.surfaceView);
////        recordButton = findViewById(R.id.recordButton);
////
////        surfaceHolder = surfaceView.getHolder();
////        surfaceHolder.addCallback(this);
////
////        recordButton.setOnClickListener(v -> {
////            if (isRecording) {
////                stopRecording();
////            } else {
////                startRecording();
////            }
////        });
////
////        checkPermissions();
////    }
////
////    private void checkPermissions() {
////        String[] permissions = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE};
////        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
////                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ||
////                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
////            ActivityCompat.requestPermissions(this, permissions, 101);
////        }
////    }
////
////    private void startRecording() {
////        if (prepareMediaRecorder()) {
////            mediaRecorder.start();
////            isRecording = true;
////            recordButton.setText("Stop Recording");
////        }
////    }
////
////    private void stopRecording() {
////        mediaRecorder.stop();
////        mediaRecorder.release();
////        camera.lock();
////        isRecording = false;
////        recordButton.setText("Start Recording");
////    }
////
////    private boolean prepareMediaRecorder() {
////        camera = Camera.open();
////        camera.setDisplayOrientation(90);
////
////        SurfaceTexture surfaceTexture = new SurfaceTexture(10);
////        try {
////            camera.setPreviewTexture(surfaceTexture);
////            camera.startPreview();
////        } catch (IOException e) {
////            e.printStackTrace();
////            return false;
////        }
////
////        mediaRecorder = new MediaRecorder();
////        camera.unlock();
////        mediaRecorder.setCamera(camera);
////
////        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
////        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);
////        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
////        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
////        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
////
////        // Set video size (e.g., 1920x1080 for Full HD)
////        mediaRecorder.setVideoSize(1920, 1080);
////
////        // Set video frame rate (e.g., 30fps)
////        mediaRecorder.setVideoFrameRate(30);
////
////        // Set video encoding bitrate (higher value means better quality)
////        mediaRecorder.setVideoEncodingBitRate(5 * 1024 * 1024); // 5 Mbps
////
////
////        mediaRecorder.setOutputFile(getExternalFilesDir(null).getAbsolutePath() + "/video.mp4");
////        Log.i("NRYN", getExternalFilesDir(null).getAbsolutePath() + "/video.mp4");
////
////        mediaRecorder.setPreviewDisplay(surfaceHolder.getSurface());
////        mediaRecorder.setOrientationHint(90);
////
////
////        try {
////            mediaRecorder.prepare();
////        } catch (IOException e) {
////            e.printStackTrace();
////            return false;
////        }
////        return true;
////    }
////
////    @Override
////    public void surfaceCreated(@NonNull SurfaceHolder holder) {
////        try {
////            camera = Camera.open();
////            camera.setPreviewDisplay(holder);
////            camera.setDisplayOrientation(90); // Set the correct display orientation
////            camera.startPreview();
////        } catch (IOException e) {
////            e.printStackTrace();
////        }
////    }
////
////    @Override
////    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
////        // Restart preview if surface changes
////        if (surfaceHolder.getSurface() == null) {
////            return;
////        }
////        try {
////            camera.stopPreview();
////        } catch (Exception e) {
////            // Ignore: Tried to stop a non-existent preview
////        }
////        try {
////            camera.setPreviewDisplay(surfaceHolder);
////            camera.setDisplayOrientation(90); // Ensure the orientation is correct when the surface changes
////            camera.startPreview();
////        } catch (IOException e) {
////            e.printStackTrace();
////        }
////    }
////
////    @Override
////    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
////        if (camera != null) {
////            camera.stopPreview();
////            camera.release();
////            camera = null;
////        }
////    }
////
////    @Override
////    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
////        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
////        if (requestCode == 101) {
////            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
////                // Permissions granted
////            } else {
////                // Permissions denied
////            }
////        }
////    }
////}
