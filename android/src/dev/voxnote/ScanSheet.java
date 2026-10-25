package dev.voxnote;

import android.app.Activity;
import android.hardware.Camera;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.Map;

/**
 * App 内扫码配对（全屏覆盖层，同 DetailSheet/ServerRecordSheet 的进出方式）：
 * 相机预览 + ZXing（纯 Java core，vendor 在 android/libs）解 QR。
 * 只认 QR；解码出的内容由宿主解析（配对码格式见 HomeActivity.onPairScanned）。
 *
 * 用的是老版 android.hardware.Camera：免依赖的预览方案，已废弃但在所有目标
 * API 上仍可用；解码线程单独一条 HandlerThread，帧回调节流（上一帧没解完就丢帧）。
 */
public class ScanSheet implements Overlay {

    public interface Listener {
        void onScanned(String text);

        /** 关闭（扫码成功 / 手动 ✕ / 返回键）：宿主从 Overlay 栈移除。 */
        void onClosed();
    }

    private final Activity act;
    private final Listener cb;
    private final FrameLayout wrap;
    private final SurfaceView surface;
    private final TextView hint;

    private Camera cam;
    private final MultiFormatReader reader = new MultiFormatReader();
    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean decoding;
    private boolean open;
    /** 扫到码后置位：不再处理后续帧/重复回调。 */
    private volatile boolean done;

    public ScanSheet(Activity a, Listener listener) {
        act = a;
        cb = listener;
        reader.setHints(buildHints());

        wrap = new FrameLayout(a);
        wrap.setBackgroundColor(0xE6000000);
        wrap.setVisibility(View.GONE);

        surface = new SurfaceView(a);
        surface.getHolder().addCallback(new SurfaceHolder.Callback() {
            public void surfaceCreated(SurfaceHolder h) { openCam(h); }
            public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
            public void surfaceDestroyed(SurfaceHolder h) { closeCam(); }
        });
        wrap.addView(surface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 中央取景框：只提示范围，裁剪其实按预览帧中心方框算（旋转后视觉上正对）
        View box = new View(a);
        box.setBackground(Ui.rounded(18, 0x00000000, 0xCCFFFFFF, 2));
        int bs = Ui.dp(240);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(bs, bs);
        bp.gravity = Gravity.CENTER;
        wrap.addView(box, bp);

        hint = Ui.text(a, "对准控制台上的配对二维码", 12.5f, 0xCCFFFFFF);
        hint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.topMargin = Ui.dp(240 + 60);
        wrap.addView(hint, hp);

        TextView close = Ui.text(a, "✕ 关闭", 13f, Ui.DIM);
        close.setGravity(Gravity.CENTER);
        close.setClickable(true);
        Ui.pad(close, 12, 10, 12, 10);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismiss(); }
        });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        cp.setMargins(Ui.dp(8), Ui.dp(24), 0, 0);
        wrap.addView(close, cp);
    }

    public void show() {
        ((FrameLayout) act.findViewById(android.R.id.content)).addView(wrap);
        open = true;
        wrap.setVisibility(View.VISIBLE);
    }

    public boolean isOpen() { return open; }

    public void dismiss() {
        if (!open) return;
        open = false;
        closeCam();
        stopDecodeThread();
        ((FrameLayout) act.findViewById(android.R.id.content)).removeView(wrap);
        if (cb != null) cb.onClosed();
    }

    // ------------------------------------------------------------ 相机

    private void openCam(SurfaceHolder holder) {
        if (decodeThread == null) {
            decodeThread = new HandlerThread("qr-decode");
            decodeThread.start();
            decodeHandler = new Handler(decodeThread.getLooper());
        }
        try {
            cam = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
            Camera.Parameters p = cam.getParameters();
            // 预览分辨率选接近 640 的最小档：够解码 QR，帧回调负担小
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (s.width >= 640 && (best == null || s.width < best.width)) best = s;
            }
            if (best == null && p.getSupportedPreviewSizes().size() > 0) {
                best = p.getSupportedPreviewSizes().get(0);
            }
            if (best != null) p.setPreviewSize(best.width, best.height);
            if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            }
            cam.setParameters(p);
            cam.setDisplayOrientation(90); // 竖屏
            cam.setPreviewDisplay(holder);
            cam.startPreview();
            cam.setPreviewCallback(previewCb);
        } catch (Throwable t) {
            closeCam();
            hint.setText("相机打不开：" + t.getMessage());
        }
    }

    private void closeCam() {
        try {
            if (cam != null) {
                cam.setPreviewCallback(null);
                cam.stopPreview();
                cam.release();
            }
        } catch (Throwable ignore) { }
        cam = null;
    }

    private final Camera.PreviewCallback previewCb = new Camera.PreviewCallback() {
        public void onPreviewFrame(final byte[] data, final Camera c) {
            if (done || decoding || decodeHandler == null || data == null) return;
            decoding = true;
            final Camera.Size sz = c.getParameters().getPreviewSize();
            decodeHandler.post(new Runnable() {
                public void run() {
                    try { decodeFrame(data, sz.width, sz.height); }
                    catch (Throwable ignore) { }
                    decoding = false;
                }
            });
        }
    };

    private void decodeFrame(byte[] data, int w, int h) {
        if (done) return;
        // 传感器帧是横的，取中心方框裁剪（屏上竖屏显示旋转 90°，中心仍对中心）
        int side = Math.min(w, h) * 7 / 10;
        int left = (w - side) / 2;
        int top = (h - side) / 2;
        PlanarYUVLuminanceSource src =
                new PlanarYUVLuminanceSource(data, w, h, left, top, side, side, false);
        try {
            Result r = decodeTry(src);
            if (r == null) r = decodeTry(src.rotateCounterClockwise());
            if (r != null && !done) {
                done = true;
                final String text = r.getText();
                ui.post(new Runnable() {
                    public void run() {
                        dismiss();
                        if (cb != null && text != null) cb.onScanned(text);
                    }
                });
            }
        } catch (Throwable ignore) {
        }
    }

    /** reader 在构造时 setHints 一次；decodeWithState 为连扫设计，用完必须 reset。 */
    private Result decodeTry(com.google.zxing.LuminanceSource src) {
        try {
            return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
        } catch (NotFoundException e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private static Map<DecodeHintType, Object> buildHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<DecodeHintType, Object>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.EnumSet.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return hints;
    }

    private void stopDecodeThread() {
        if (decodeThread != null) {
            decodeThread.quitSafely();
            decodeThread = null;
            decodeHandler = null;
        }
    }
}
