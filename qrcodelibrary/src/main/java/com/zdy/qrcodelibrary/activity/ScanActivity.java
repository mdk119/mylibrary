/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.zdy.qrcodelibrary.activity;

import android.Manifest;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Camera.CameraInfo;
import android.hardware.Camera.PreviewCallback;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.TextureView.SurfaceTextureListener;
import android.view.View;
import android.view.WindowManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.ResultPointCallback;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.zdy.qrcodelibrary.R;
import com.zdy.qrcodelibrary.scan.CameraManager;
import com.zdy.qrcodelibrary.view.DialogBuilder;
import com.zdy.qrcodelibrary.view.ScannerView;

import java.util.EnumMap;
import java.util.Map;


public final class ScanActivity extends FragmentActivity
        implements SurfaceTextureListener, ActivityCompat.OnRequestPermissionsResultCallback {
    private static final String TAG = "ScanActivity";
    public static final String INTENT_EXTRA_RESULT = "result";


    private static final long VIBRATE_DURATION = 50L;
    private static final long AUTO_FOCUS_INTERVAL_MS = 2500L;

    private final CameraManager cameraManager = new CameraManager();

    private ScannerView scannerView;
    private TextureView previewView;

    private volatile boolean surfaceCreated = false;

    private Vibrator vibrator;//设置震动
    private HandlerThread cameraThread;
    private volatile Handler cameraHandler;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Stick to the orientation the activity was started with. We cannot declare this in the
        // AndroidManifest.xml, because it's not allowed in combination with the windowIsTranslucent=true
        // theme attribute.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        // Draw under navigation and status bars.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

        setContentView(R.layout.scan_activity);
        scannerView = findViewById(R.id.scan_activity_mask);
        previewView = findViewById(R.id.scan_activity_preview);
        previewView.setSurfaceTextureListener(this);

        cameraThread = new HandlerThread("cameraThread", Process.THREAD_PRIORITY_BACKGROUND);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, 0);
    }

    @Override
    protected void onResume() {
        super.onResume();

        maybeOpenCamera();
    }

    @Override
    protected void onPause() {
        cameraHandler.post(closeRunnable);

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        // cancel background thread
        cameraHandler.removeCallbacksAndMessages(null);
        cameraThread.quit();

        previewView.setSurfaceTextureListener(null);

        // We're removing the requested orientation because if we don't, somehow the requested orientation is
        // bleeding through to the calling activity, forcing it into a locked state until it is restarted.
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode, final String[] permissions,
                                           final int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            maybeOpenCamera();
        }
    }

    private void maybeOpenCamera() {
        Log.e(TAG, "maybeOpenCamera");
        if (surfaceCreated && ContextCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            cameraHandler.post(openRunnable);
    }

    @Override
    public void onSurfaceTextureAvailable(final SurfaceTexture surface, final int width, final int height) {
        surfaceCreated = true;
        maybeOpenCamera();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(final SurfaceTexture surface) {
        surfaceCreated = false;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(final SurfaceTexture surface, final int width, final int height) {
    }

    @Override
    public void onSurfaceTextureUpdated(final SurfaceTexture surface) {
    }

    @Override
    public void onAttachedToWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            //设置在锁屏页面上显示
            setShowWhenLocked(true);
        }
    }

    @Override
    public void onBackPressed() {
        scannerView.setVisibility(View.GONE);
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_FOCUS:
            case KeyEvent.KEYCODE_CAMERA:
                // don't launch camera app
                return true;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
                cameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        //打开闪光灯
                        cameraManager.setTorch(keyCode == KeyEvent.KEYCODE_VOLUME_UP);
                    }
                });
                return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    public void handleResult(final Result scanResult) {
        vibrator.vibrate(VIBRATE_DURATION);

        scannerView.setIsResult(true);

        final Intent result = new Intent();
        result.putExtra(INTENT_EXTRA_RESULT, scanResult.getText());
        setResult(RESULT_OK, result);
        postFinish();
    }

    private void postFinish() {
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                finish();
            }
        }, 50);
    }

    /**
     * 打开相机的具体逻辑
     */
    private final Runnable openRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                final Camera camera = cameraManager.open(previewView, displayRotation());

                final Rect framingRect = cameraManager.getFrame();
                final RectF framingRectInPreview = new RectF(cameraManager.getFramePreview());
                framingRectInPreview.offsetTo(0, 0);
                final boolean cameraFlip = cameraManager.getFacing() == CameraInfo.CAMERA_FACING_FRONT;
                final int cameraRotation = cameraManager.getOrientation();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        //设置取景框位置
                        scannerView.setFraming(framingRect, framingRectInPreview, displayRotation(), cameraRotation,
                                cameraFlip);
                    }
                });

                final String focusMode = camera.getParameters().getFocusMode();
                //自动对焦或聚焦模式
                final boolean nonContinuousAutoFocus = Camera.Parameters.FOCUS_MODE_AUTO.equals(focusMode)
                        || Camera.Parameters.FOCUS_MODE_MACRO.equals(focusMode);

                if (nonContinuousAutoFocus)
                    cameraHandler.post(new AutoFocusRunnable(camera));

                cameraHandler.post(fetchAndDecodeRunnable);
            } catch (final Exception x) {
                Log.i(TAG, "problem opening camera" + x.getMessage());
            }
        }

        private int displayRotation() {
            final int rotation = getWindowManager().getDefaultDisplay().getRotation();
            if (rotation == Surface.ROTATION_0)
                return 0;
            else if (rotation == Surface.ROTATION_90)
                return 90;
            else if (rotation == Surface.ROTATION_180)
                return 180;
            else if (rotation == Surface.ROTATION_270)
                return 270;
            else
                throw new IllegalStateException("rotation: " + rotation);
        }
    };

    private final Runnable closeRunnable = new Runnable() {
        @Override
        public void run() {
            cameraHandler.removeCallbacksAndMessages(null);
            cameraManager.close();
        }
    };

    /**
     * 设置对焦模式
     */
    private final class AutoFocusRunnable implements Runnable {
        private final Camera camera;

        public AutoFocusRunnable(final Camera camera) {
            this.camera = camera;
        }

        @Override
        public void run() {
            try {
                camera.autoFocus(autoFocusCallback);
            } catch (final Exception x) {
                Log.i(TAG, "problem with auto-focus, will not schedule again" + x.getMessage());
            }
        }

        private final Camera.AutoFocusCallback autoFocusCallback = new Camera.AutoFocusCallback() {
            @Override
            public void onAutoFocus(final boolean success, final Camera camera) {
                // schedule again
                cameraHandler.postDelayed(AutoFocusRunnable.this, AUTO_FOCUS_INTERVAL_MS);
            }
        };
    }
    /**
     * 解码的逻辑
     */
    private final Runnable fetchAndDecodeRunnable = new Runnable() {
        private final QRCodeReader reader = new QRCodeReader();
        private final Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);

        @Override
        public void run() {
            cameraManager.requestPreviewFrame(new PreviewCallback() {
                @Override
                public void onPreviewFrame(final byte[] data, final Camera camera) {
                    decode(data);
                }
            });
        }

        private void decode(final byte[] data) {
             // 构造基于平面的YUV亮度源，即包含二维码区域的数据源
            final PlanarYUVLuminanceSource source = cameraManager.buildLuminanceSource(data);
            // 构造二值图像比特流，使用HybridBinarizer算法解析数据源
            final BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

            try {
                /**
                * 设置解码格式，设置下面三个模式是和二维码相关的格式
                *
                *     mHints.put(DecodeHintType.CHARACTER_SET, "utf-8");
                  *   mHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
                  *   mHints.put(DecodeHintType.POSSIBLE_FORMATS, BarcodeFormat.QR_CODE);
                * */
                hints.put(DecodeHintType.CHARACTER_SET, "utf-8");
                hints.put(DecodeHintType.POSSIBLE_FORMATS, com.google.zxing.BarcodeFormat.QR_CODE);
                hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
                hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK, new ResultPointCallback() {
                    @Override
                    public void foundPossibleResultPoint(final ResultPoint dot) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                scannerView.addDot(dot);
                            }
                        });
                    }
                });
                // 采用QRCodeReader解析图像
                final Result scanResult = reader.decode(bitmap, hints);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        handleResult(scanResult);
                    }
                });
            } catch (final ReaderException x) {
                // retry
                cameraHandler.post(fetchAndDecodeRunnable);
            } finally {
                reader.reset();
            }
        }
    };
    /**
     * dialog的使用
     */
    public static class WarnDialogFragment extends DialogFragment {
        private static final String FRAGMENT_TAG = WarnDialogFragment.class.getName();

        public static void show(final FragmentManager fm, final int titleResId, final String message) {
            final WarnDialogFragment newFragment = new WarnDialogFragment();
            final Bundle args = new Bundle();
            args.putInt("title", titleResId);
            args.putString("message", message);
            newFragment.setArguments(args);
            newFragment.show(fm, FRAGMENT_TAG);
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final Bundle args = getArguments();
            final DialogBuilder dialog = DialogBuilder.warn(getActivity(), args.getInt("title"));
            dialog.setMessage(args.getString("message"));
            dialog.singleDismissButton(new OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialog, final int which) {
                    getActivity().finish();
                }
            });
            return dialog.create();
        }

        @Override
        public void onCancel(final DialogInterface dialog) {
            getActivity().finish();
        }
    }
}
