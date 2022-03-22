package com.googlecode.tesseraction.demo;

import android.Manifest;
import android.animation.Keyframe;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.LayerDrawable;
import android.hardware.camera2.CameraManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.InvertedLuminanceSource;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.googlecode.tesseract.android.TessBaseAPI;
import com.googlecode.tesseraction.CMN;
import com.googlecode.tesseraction.CropView;
import com.googlecode.tesseraction.FormatUtils;
import com.googlecode.tesseraction.Options;
import com.googlecode.tesseraction.R;
import com.googlecode.tesseraction.databinding.ActivityQrBinding;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**HighPerformanceScanning Activity (demo)。<br>
 *&nbsp;&nbsp;{@link #onConfigurationChanged compatibleWithWindowSizeChanges}、<br>
 *&nbsp;&nbsp;{@link PDFCameraManager#ResetCameraSettings compatibleWithLandscapeMode}，<br>
 *&nbsp;&nbsp;{@link PDFCameraManager#decodeLuminanceSource scanTheISBNCodeHorizontallyAndVertically}<br>
 *&nbsp;&nbsp;{@link Options#getContinuousFocus supportThreeFocusModes}<br>
 *&nbsp;&nbsp;{@link #onPause 支持熄屏/退入后台时暂停} ，{@link #onResume Continue to scan the code immediately upon recovery} 。<br>
 *&nbsp;&nbsp;{@link PDFCameraManager#decorateCameraSettings The configuration of exposure value, flash, etc. has been considered.}<br> */
public /*final*/ class PDFActivity extends Activity implements View.OnClickListener {
	private final static String[] permissions = new String[]{Manifest.permission.CAMERA};
	
	Options opt;
	
	private PDFCameraManager cameraManager;
	
	public PDFActivityHandler handler;
	
	private final MultiFormatReader multiFormatReader = new MultiFormatReader();
	
	private final Map<DecodeHintType,Object> hints = new EnumMap<>(DecodeHintType.class);
	
	public CropView qr_frame;
	
	private FrameLayout video_surface_frame;
	
	public View surfaceHolder;
	
	public TextureView surfaceView;
	
	public View root;
	
	private DisplayMetrics dm;
	
	public boolean isPortrait=true;
	
	private DecodeThread handlerThread;
	
	ActivityQrBinding UIData;
	
	public boolean suspensed;
	public boolean isTorchLighting;
	private boolean systemIntialized;
	private boolean requestedResetHints;
	
	
	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		
		dm.setTo(getResources().getDisplayMetrics());
		
		calcScreenOrientation();
		
		if(cameraManager!=null && cameraManager.isOpen()) {
			cameraManager.ResetCameraSettings();
		}
		
		CMN.Log("onConfigurationChanged", dm.widthPixels+"x"+dm.heightPixels, isPortrait);
	}
	
	@Override
	public void onCreate(Bundle icicle) {
		super.onCreate(icicle);
		
		opt = new Options();
		
		int type = opt.getLaunchCameraType();
		
		suspensed = type==0||type==2&&!opt.getRememberedLaunchCamera();
		
		if(suspensed||check_camera(true))
		{
			further_loading();
		}
		
		setStatusBarColor(getWindow());
	}
	
	private void setStatusBarColor(Window window){
		window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
				| WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
		window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
				| View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
			window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
		if(Build.VERSION.SDK_INT>=21) {
			window.setStatusBarColor(Color.TRANSPARENT);
			//window.setNavigationBarColor(Color.TRANSPARENT);
		}
	}
	
	public void further_loading() {
		Window win = getWindow();
		
		win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		
		dm = getResources().getDisplayMetrics();
		
		//setContentView(R.layout.activity_qr);
		UIData = DataBindingUtil.setContentView(this, R.layout.activity_qr);
		
		setOnClickListenersOneDepth(UIData.toolbarContent, this, 1);
		setOnClickListenersOneDepth((ViewGroup)UIData.toast, this, 1);
		
		if(opt.getLaunchCameraType()==2&&opt.getRememberedLaunchCamera()) {
			ui_camera_btn_vis(2);
		}
		
		UIData.tv1.setOnClickListener(this);
		
		qr_frame = UIData.qrFrame;
		
		root = (View) qr_frame.getParent();
		
		//qr_frame.a = this;
		
		cameraManager = new PDFCameraManager(this, dm);
		
		//qr_frame.setFramingRect(cameraManager.getFramingRect(false));
		
		cameraManager.setFramingRect(qr_frame.getFrameRect());
		
		calcScreenOrientation();
		
		//setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
		
		boolean val = opt.getTorchLight();
		if(val&&!opt.getRemberTorchLight()) {
			opt.toggleTorchLight();
		}
		
		if(suspensed) {
			suspenseCameraUI();
		} else {
			open_camera();
		}
		//root.postDelayed(this::open_camera, 350);
		
		updateOrientation();
		
		systemIntialized=true;
	}
	
	private void updateOrientation() {
//		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
//		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
//		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
//		setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
	}
	
	private void suspenseCameraUI() {
		qr_frame.suspense();
		updateTorchLight(false);
		syncQRFrameSettings(true);
		UIData.tv1.setVisibility(View.VISIBLE);
//		((ViewGroup.MarginLayoutParams)UIData.tv1.getLayoutParams())
//				.setMargins(0, (int) (qr_frame.scanLineTop-dm.density*45), 0, 0);
	}
	
	private void syncQRFrameSettings(boolean inval) {
		qr_frame.drawLaser = suspensed || opt.getQRFrameDrawLaser();
		qr_frame.drawLocations = opt.getQRFrameDrawLocations();
		if(inval) {
			if(suspensed) {
				qr_frame.invalidate();
			} else {
				qr_frame.resume();
			}
		}
	}
	
	private void updateTorchLight(boolean torchLight) {
		LayerDrawable ld = (LayerDrawable) UIData.torch.getDrawable();
		ld.getDrawable(0).setAlpha(torchLight?255:64);
		ld.getDrawable(1).setAlpha(torchLight?255:128);
	}
	
	private void calcScreenOrientation() {
		isPortrait = getScreenOrientation()==1;
	}
	
	private int getScreenRotation() {
		WindowManager windowService = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
		return windowService.getDefaultDisplay().getRotation();
	}
	
	private int getScreenOrientation() {
		int PORT=1,LAND=2;
		int screenRotation = getScreenRotation();
		if (screenRotation == Surface.ROTATION_90 || screenRotation == Surface.ROTATION_270) {
			return LAND;
		}
		return PORT;
	}
	
	EnumSet<BarcodeFormat> decodeFormats=EnumSet.noneOf(BarcodeFormat.class);
	
	private boolean check_camera(boolean init) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && PDFActivity.this.checkPermission(Manifest.permission.CAMERA, Process.myPid(), Process.myUid()) != PackageManager.PERMISSION_GRANTED) {
			PDFActivity.this.requestPermissions(permissions, init?1:2);
			return false;
		}
		return true;
	}
	
	public static Bitmap toGrayscale(Bitmap bmpOriginal) {
		int width, height;
		height = bmpOriginal.getHeight();
		width = bmpOriginal.getWidth();
		
		Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565);
		Canvas c = new Canvas(bmpGrayscale);
		Paint paint = new Paint();
		ColorMatrix cm = new ColorMatrix();
		cm.setSaturation(0);
		ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
		paint.setColorFilter(f);
		c.drawBitmap(bmpOriginal, 0, 0, paint);
		return bmpGrayscale;
	}
	
	private static final int MAX_QRCODE_SIXE = 1500;
	private static final int MAX_QRCODE_SIXE_SQ = MAX_QRCODE_SIXE*MAX_QRCODE_SIXE;
	
	private Bitmap scaleBitmap(Bitmap origin, float ratio) {
		int width = origin.getWidth();
		int height = origin.getHeight();
		Matrix matrix = new Matrix();
		matrix.preScale(ratio, ratio);
		Bitmap newBM = Bitmap.createBitmap(origin, 0, 0, width, height, matrix, false);
		if (newBM.equals(origin)) {
			return newBM;
		}
		origin.recycle();
		return newBM;
	}
	
	public static final int DEFAULT_REQ_WIDTH = 450;
	public static final int DEFAULT_REQ_HEIGHT = 800;
	
	private static Bitmap decodeBitmap(Context context, Uri input, int reqWidth, int reqHeight) throws IOException {
		//from zxing-lite
		BitmapFactory.Options newOpts = new BitmapFactory.Options();
		newOpts.inJustDecodeBounds = true;
		ContentResolver res = context.getContentResolver();
		InputStream is;
		BitmapFactory.decodeStream(is=res.openInputStream(input), null, newOpts);
		is.close();
		float width = newOpts.outWidth;
		float height = newOpts.outHeight;
		int wSize = 1;
		if (width > reqWidth) {
			wSize = (int) (width / reqWidth);
		}
		int hSize = 1;
		if (height > reqHeight) {
			hSize = (int) (height / reqHeight);
		}
		int size = Math.max(wSize,hSize);
		if (size <= 0)
			size = 1;
		newOpts.inSampleSize = size;
		newOpts.inJustDecodeBounds = false;
		Bitmap ret = BitmapFactory.decodeStream(is=res.openInputStream(input), null, newOpts);
		is.close();
		return ret;
	}
	
	private void parse_code(Uri data) {
		if(data!=null) {
			if(parse_code_runnable!=null) {
				parse_code_runnable.interrupt();
			}
			parse_code_runnable = new ParseCodeRunnable(this, data);
			parse_code_runnable.start();
		}
	}
	
	ParseCodeRunnable parse_code_runnable;
	
	static class ParseCodeRunnable implements Runnable {
		private final MultiFormatReader multiFormatReader;
		private final WeakReference<PDFActivity> activity;
		private volatile boolean stopped;
		private volatile boolean finished;
		private final Thread t = new Thread(this);
		final Uri data;
		Result res=null;
		
		ParseCodeRunnable(PDFActivity a, Uri data) {
			this.data = data;
			multiFormatReader=a.multiFormatReader;
			activity = new WeakReference<>(a);
		}
		
		public void interrupt() {
			t.interrupt();
			stopped=true;
			activity.clear();
		}
		
		public void start() {
			t.start();
		}
		@AnyThread
		@Override
		public void run() {
			PDFActivity a = activity.get();
			if(finished) {
				if(res!=null) {
					a.onDecodeSuccess(res);
				} else {
					a.showT("PARSING_FAILED");
				}
			} else {
				try {
					//InputStream input = getContentResolver().openInputStream(data.getData());
					//Bitmap bitmap = BitmapFactory.decodeStream(input);
					Bitmap bitmap = decodeBitmap(a, data, DEFAULT_REQ_WIDTH, DEFAULT_REQ_HEIGHT);
					a=null;
					int width=bitmap.getWidth();
					int height=bitmap.getHeight();
					if(stopped) return;
					//bitmap = toGrayscale(bitmap);
//				if(width*height>MAX_QRCODE_SIXE_SQ) {
//					CMN.rt();
//					float theta=1080f;
//					float ratio = Math.min(theta/width, theta/height);
//					bitmap = scaleBitmap(bitmap, ratio);
//					width=bitmap.getWidth();
//					height=bitmap.getHeight();
//					CMN.pt(width, height, ratio, "scale::");
//				}
					
					int[] dataByts = new int[width*height];//cameraManager.acquireTmpData(w * h);
					bitmap.getPixels(dataByts, 0, width, 0, 0, width, height);
					RGBLuminanceSource source = new RGBLuminanceSource(width, height, dataByts);
					try {
						BinaryBitmap binaryBitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
						res = multiFormatReader.decodeWithState(binaryBitmap);
					} catch (NotFoundException e) {
						//e.printStackTrace();
					}
					if(stopped) return;
					if(res==null) { // 反色
						try {
							BinaryBitmap binaryBitmap = new BinaryBitmap(new GlobalHistogramBinarizer(new InvertedLuminanceSource(source)));
							res = multiFormatReader.decodeWithState(binaryBitmap);
						} catch (NotFoundException e) {
							//e.printStackTrace();
						}
					}
					if(stopped) return;
					if(res==null) { // 旋转
						//from zxing-demo
						int[] rotatedData = new int[dataByts.length];
						for (int y = 0; y < height; y++) {
							for (int x = 0; x < width; x++)
								rotatedData[x * height + height - y - 1] = dataByts[x + y * width];
						}
						try {
							source = new RGBLuminanceSource(height, width, rotatedData);
							BinaryBitmap binaryBitmap = new BinaryBitmap(new GlobalHistogramBinarizer(source));
							res = multiFormatReader.decodeWithState(binaryBitmap);
						} catch (NotFoundException e) {
							//e.printStackTrace();
						}
					}
					//CMN.Log(bitmap);
				} catch (Exception e) {
					CMN.Log(e);
				}
				finished();
			}
		}
		
		private void finished() {
			PDFActivity a = activity.get();
			if(stopped||a==null) {
				return;
			}
			finished=true;
			a.root.post(this);
		}
	}
	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		//super.onActivityResult(requestCode, resultCode, data);
		if(requestCode==3) { //ACTION_GET_CONTENT
			if(resultCode==Activity.RESULT_OK && data!=null) {
				parse_code(data.getData());
			}
		}
	}
	
	
	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		//super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		boolean hasPermission=false;
		for (int i=0; i<grantResults.length; i++) {
			if(grantResults[i]==0) {
				hasPermission=true;
				break;
			}
		}
		if(requestCode==1) {
			if(hasPermission) {
				further_loading();
			} else {
				finish();
			}
		} else if(requestCode==2) {
			if(hasPermission) {
				open_camera();
			}
		}
	}
	
	private void close_camera() {
		pauseCamera();
		cameraManager.close();
		suspensed=true;
		suspenseCameraUI();
	}
	
	private void openCamera() {
		if(check_camera(false)) {
			open_camera();
		}
	}
	
	private void open_camera() {
		UIData.tv1.setVisibility(View.GONE);
		suspensed=false;
		qr_frame.resume();
		isTorchLighting=false;
		syncQRFrameSettings(true);
		updateTorchLight(opt.getTorchLight());
		
		if(handlerThread==null) {
			handlerThread = new DecodeThread(this);
			handlerThread.start();
		}
		
		if(surfaceView==null) {
			//handler = new PDFActivityHandler(this, cameraManager);
			// The prefs can't change while the thread is running, so pick them up once here.
			
			setHints();
			
			video_surface_frame = findViewById(R.id.video_surface_frame);
			
			surfaceHolder = findViewById(R.id.surface_holder);
			
			surfaceView = findViewById(R.id.preview_view);
			
			qr_frame.setViewDelegation(surfaceHolder);
			
			if (surfaceView.isAvailable()) {
				init_camera();
			} else {
				surfaceView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
					@Override
					public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
						init_camera();
					}
					
					@Override
					public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
					}
					
					@Override
					public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
						CMN.Log("onSurfaceTextureDestroyed");
						return false;
					}
					
					@Override
					public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
					}
				});
			}
		}
		else {
			resumeCamera();
		}
	}
	
	private void setHints() {
		decodeFormats.clear();
		
		if (opt.getDecodeUPCBar()) {
			decodeFormats.addAll(FormatUtils.PRODUCT_FORMATS);
		}
		if (opt.getDecode1DBar()) {
			decodeFormats.addAll(FormatUtils.INDUSTRIAL_FORMATS);
		}
		if (opt.getDecode2DBar()) {
			decodeFormats.addAll(FormatUtils.QR_CODE_FORMATS);
		}
		if (opt.getDecode2DMatrixBar()) {
			decodeFormats.addAll(FormatUtils.DATA_MATRIX_FORMATS);
		}
		if (opt.getDecodeAZTECBar()) {
			decodeFormats.addAll(FormatUtils.AZTEC_FORMATS);
		}
		if (opt.getDecodePDF417Bar()) {
			decodeFormats.addAll(FormatUtils.PDF417_FORMATS);
		}
		hints.put(DecodeHintType.POSSIBLE_FORMATS, decodeFormats);
		
		String characterSet=null;
		if (characterSet != null) {
			hints.put(DecodeHintType.CHARACTER_SET, characterSet);
		}
		hints.put(DecodeHintType.NEED_RESULT_POINT_CALLBACK,  qr_frame.getPointsCollector());
		
		multiFormatReader.setHints(hints);
	}
	
	private void resumeCamera() {
		if(!suspensed && surfaceView!=null && surfaceView.isAvailable()) {
			if(cameraManager!=null && cameraManager.isOpen()) {
				try {
					cameraManager.camera.setPreviewTexture(surfaceView.getSurfaceTexture());
				} catch (Exception e) {
					CMN.Log("re-opening::");
					cameraManager.close();
				}
			}
			init_camera();
			qr_frame.resume();
		}
	}
	
	private void pauseCamera() {
		if(surfaceView!=null) {
			cameraManager.pause();
			handler.pause();
			qr_frame.pause();
		}
	}
	
	@Override
	protected void onResume() {
		super.onResume();
		if(systemIntialized) {
			resumeCamera();
		}
	}
	
	@Override
	protected void onPause() {
		pauseCamera();
		super.onPause();
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		if(surfaceView!=null) {
			handler.stop();
			try {
				cameraManager.release();
			} catch (Exception e) {
				CMN.Log(e);
			}
		}
	}
	
	public void onDecodeSuccess(Result rawResult) {
		String text = rawResult.getText();
		Intent intent = new Intent();
		intent.putExtra(Intent.EXTRA_TEXT, text);
		//CMN.Log("sendText", CMN.id(text), text);
		setResult(RESULT_OK, intent);
		if(opt.getOneShotAndReturn()) {
			finish();
		} else {
			PostResultDisplay = text;
			root.removeCallbacks(PostDisplayResult);
			root.post(PostDisplayResult);
		}
	}
	
	String PostResultDisplay;
	public static ObjectAnimator tada(View view) {
		return tada(view, 1f);  }
	
	public static ObjectAnimator tada(View view, float shakeFactor) {
		PropertyValuesHolder pvhScaleX = PropertyValuesHolder.ofKeyframe(View.SCALE_X,
				Keyframe.ofFloat(0f, 1f),
				Keyframe.ofFloat(.1f, .9f),
				Keyframe.ofFloat(.2f, .9f),
				Keyframe.ofFloat(.3f, 1.1f),
				Keyframe.ofFloat(.4f, 1.1f),
				Keyframe.ofFloat(.5f, 1.1f),
				Keyframe.ofFloat(.6f, 1.1f),
				Keyframe.ofFloat(1f, 1f)
		);
		PropertyValuesHolder pvhScaleY = PropertyValuesHolder.ofKeyframe(View.SCALE_Y,
				Keyframe.ofFloat(0f, 1f),
				Keyframe.ofFloat(.1f, .9f),
				Keyframe.ofFloat(.2f, .9f),
				Keyframe.ofFloat(.3f, 1.1f),
				Keyframe.ofFloat(.4f, 1.1f),
				Keyframe.ofFloat(.5f, 1.1f),
				Keyframe.ofFloat(.6f, 1.1f),
				Keyframe.ofFloat(1f, 1f)
		);
		PropertyValuesHolder pvhRotate = PropertyValuesHolder.ofKeyframe(View.ROTATION,
				Keyframe.ofFloat(0f, 0f),
				Keyframe.ofFloat(.1f, -3f * shakeFactor),
				Keyframe.ofFloat(.2f, -3f * shakeFactor),
				Keyframe.ofFloat(.3f, 3f * shakeFactor),
				Keyframe.ofFloat(.4f, -3f * shakeFactor),
				Keyframe.ofFloat(.5f, 3f * shakeFactor),
				Keyframe.ofFloat(.6f, -3f * shakeFactor),
				Keyframe.ofFloat(1f, 0)
		);
		return ObjectAnimator.ofPropertyValuesHolder(view, pvhScaleX, pvhScaleY, pvhRotate).
				setDuration(900);  }
	
	private ObjectAnimator tada;
	Runnable PostDisplayResult = () -> {
		showT(PostResultDisplay);
		UIData.toastTv.setText(PostResultDisplay);
		if(tada==null) {
			tada=tada(UIData.toast);
		}
		//tada.pause();
		//tada.setCurrentPlayTime(0);
		if(!tada.isRunning()) {
			tada.setCurrentPlayTime(0);
			tada.start();
		}
	};
	
	private void init_camera() {
		if(!cameraManager.isOpen()) {
			try {
				cameraManager.open();
			} catch (Exception e) {
				CMN.Log(e);
				//showError();
			}
		}
		if(cameraManager.isOpen()) {
			//cameraManager.startPreview(null);
			cameraManager.startPreview(surfaceView.getSurfaceTexture());
			cameraManager.requestPreviewFrame(handler.ready());
			cameraManager.resumeSensor();
			//qr_frame.setBitmap(null);
		}
	}
	
	@Override
	public void onClick(View v) {
		switch (v.getId()) {
			case R.id.tv1:{
				openCamera();
				if(opt.getLaunchCameraType()==2) {
					ui_camera_btn_vis(2);
					opt.setRememberedLaunchCamera(true);
				}
			} break;
			case R.id.folder:{
				Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
				intent.setType("image/*");
				try {
					startActivityForResult(intent, 3);
				} catch (Exception e) {
					showT("failedToOpen");
				}
			} break;
			case R.id.camera:{
				close_camera();
				ui_camera_btn_vis(0);
				opt.setRememberedLaunchCamera(false);
			} break;
			case R.id.ivBack:{
				finish();
			} break;
			case R.id.torch: {
				if(suspensed) {
					isTorchLighting=!isTorchLighting;
					updateTorchLight(isTorchLighting);
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
						CameraManager camManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
						try {
							camManager.setTorchMode(camManager.getCameraIdList()[0], isTorchLighting);
						} catch (Exception e) {
							//CMN.Log(e);
						}
					} else {
						if(!cameraManager.isOpen()) {
							try {
								cameraManager.open();
							} catch (IOException e) {
								//CMN.Log(e);
							}
						}
						if(cameraManager.isOpen()) {
							cameraManager.decorateCameraSettings();
						}
					}
				} else {
					updateTorchLight(opt.toggleTorchLight());
					cameraManager.decorateCameraSettings();
				}
			} break;
//			case R.id.tools:{
//				pauseCamera();
//
//				StandardConfigDialog holder = new StandardConfigDialog(getResources(), opt);
//
//				StandardConfigDialog.buildStandardConfigDialog(this, holder, null, R.string.qr_settings);
//
//				holder.init_qr_configs(this);
//
//				holder.dlg.setOnDismissListener(dialog -> {
//					resumeCamera();
//					if(requestedResetHints&&FFStamp!=opt.FirstFlag()) {
//						setHints();
//						FFStamp=opt.FirstFlag();
//						requestedResetHints=false;
//					}
//				});
//
//				holder.dlg.show();
//			} break;
		}
	}
	
	private void showT(String text) {
		Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
	}
	
//	@Override
//	public void processOptionChanged(ClickableSpan clickableSpan, View widget, int processId, int val) {
//		CMN.Log("processOptionChanged", processId, val);
//		switch (processId) {
//			case 1:
//				syncQRFrameSettings(true);
//			break;
//			case 2:
//				ui_camera_btn_vis(val);
//			break;
//			case 3:
//				requestedResetHints=true;
//			break;
//		}
//	}
	
	private void ui_camera_btn_vis(int vis) {
		UIData.camera.setVisibility(vis==2?View.VISIBLE:View.GONE);
	}
	
	/** 此Handler位于异步线程，这样不会卡UI */
	static class PDFActivityHandler extends Handler {
		TessBaseAPI tess;
		public final WeakReference<PDFActivity> activity;
		
		private boolean running = true;
		
		private int failInc;
		
		//构造
		public PDFActivityHandler(PDFActivity activity) {
			//super(Looper.myLooper());
			activity.handler=this;
			this.activity = new WeakReference<>(activity);
		}
		
		@Override
		public void handleMessage(@NonNull Message message) {
			if (running && message.what==R.id.decode) { //data_recv
				decode((byte[]) message.obj);
			} else if(message.what==R.id.quit) {
				Looper.myLooper().quit();
			}
		}
		
		/** Decode the data within the viewfinder rectangle, and time how long it
		 * took. For efficiency, reuse the same reader object.
		 * @param data The YUV preview frame.  */
		private void decode(byte[] data) {
			PDFActivity a = activity.get();
			if(a==null) {
				stop();
				return;
			}
			int width=a.sWidth;
			int height=a.sHeight;
			long start = System.currentTimeMillis();
			Result rawResult = null;
			//CMN.Log("decode……", CMN.id(data));
			PDFCameraManager camera = a.cameraManager;
			boolean debugCamera=false;
			boolean testTesseraction=false;
			if(!debugCamera && testTesseraction) {
				// TestTheOCRPlugin！！！
				if(tess==null) {
					tess = new TessBaseAPI();
					String dataPath = new File(Environment.getExternalStorageDirectory(), "tesseract").getAbsolutePath();
					tess.init(dataPath, "eng"); //chi_sim
					//tess.setImage(data, width, height, );
					//String text = tess.getUTF8Text();
				}
				try {
					rawResult = camera.decodeOCR(this, data, width, height, a.isPortrait, false, false);
				} catch (Exception e) {
					CMN.Log(e);
				}
			}
			else if(!debugCamera) {
				try {
					rawResult = camera.decodeLuminanceSource(this, data, width, height, a.isPortrait, false, false); //不旋转
				} catch (Exception e) {// continue
					//CMN.Log(e);
				}
				Options opt=a.opt;
				if(rawResult==null && opt.getTryAgainWithInverted()) {
					try {
						rawResult = camera.decodeLuminanceSource(this, data, width, height, a.isPortrait, false, true); //反色
					} catch (Exception e) {// continue
						//CMN.Log(e);
					}
				}
				if(rawResult==null && opt.getTryAgainWithRotatedData()) { //for isbn
					failInc++;
					if(opt.getTryAgainImmediately()||failInc>=5) {
						try {
							rawResult = camera.decodeLuminanceSource(this, data, width, height, a.isPortrait, true, false); //不旋转;
						} catch (Exception e) {
							//CMN.Log(e);
						}
						failInc=0;
					}
				}
				a.multiFormatReader.reset();
			}
			
			if (rawResult != null) { // Don't log the barcode contents for security.
				a.onDecodeSuccess(rawResult);
				//CMN.Log("fatal poison", "Found_barcode_in " + (end - start) + " ms");
			}
			camera.requestPreviewFrame(this); // fast restart
		}
		
		public Result try_decode_source(LuminanceSource source) throws NotFoundException {
			BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
			return activity.get().multiFormatReader.decodeWithState(bitmap);
		}
		
		public void stop() {
			pause();
			removeMessages(R.id.decode);
			sendEmptyMessage(R.id.quit);
		}
		
		public synchronized void pause() {
			running=false;
		}
		
		public synchronized PDFActivityHandler ready() {
			running=true;
			return this;
		}
	}
	
	/**酱油线程*/
	static class DecodeThread extends Thread {
		WeakReference<PDFActivity> activity;
		
		public DecodeThread(PDFActivity qrActivity) {
			activity = new WeakReference<>(qrActivity);
		}
		
		@Override
		public void run() {
			Looper.prepare();
			new PDFActivityHandler(activity.get());
			activity=null;
			//CMN.Log("thread_run……");
			Looper.loop();
			//CMN.Log("thread_run_ended……");
		}
	}
	
	/** 处理画面拉伸，从我的视频播放器项目复制过来的。 */
	
	private final int Match_Width=0;
	private final int Match_Height=1;
	private final int Match_Auto=2;
	private final int Match_None=3;
	
	float pendingTransX = 0;
	float pendingTransY = 0;
	int pendingWidth = 0;
	int pendingHeight = 0;
	float pendingScale = 0;
	int pendingMatchType = -1;
	int preferedMatchType = -1;
	
	private int mVideoVisibleHeight = 0;
	private int mVideoVisibleWidth = 0;
	
	int mVideoHeight = 0;
	int mVideoWidth = 0;
	boolean mVideoWidthReq;
	
	public int sWidth;
	public int sHeight;
	
	// 有些变量用于处理视频播放器画面的缩放和移动
	// 			，自图片读取二维码可能用得到这些。
	// 不过还要弄扫码框的大小、位置调整，太麻烦了……
	public float scale;
	private float scaleStart;
	private float zoomInStart;
	public float maxScale=10;
	
	/** fit camera image, additional translation is applied to the parent view. */
	public PointF vTranslate = new PointF();
	public PointF vTranslateOrg = new PointF();
	
	/** 测试用，试试视频播放器的fit_center模式，使画面整个纳入界面范围。
	 * 		进入分屏模式以查看效果。*/
	private final static boolean videoMode=false;
	
	public void onNewVideoViewLayout(int width, int height) {
		CMN.Log("onNewVideoViewLayout", width, height, isPortrait);
		if(width==-1 && height==-1){
			width=dm.widthPixels;
			height=dm.heightPixels;
		}
		if (width * height == 0) return;
		//if(mVideoWidthReq || mVideoWidth != width || mVideoHeight != height){
			mVideoWidth = width;
			mVideoHeight = height;
			mVideoVisibleWidth = mVideoWidth;
			mVideoVisibleHeight = mVideoHeight;
			refreshSVLayout();
			mVideoWidthReq=false;
		//}
	}
	
	/** Refresh size of videoview and it's parent view*/
	public void refreshSVLayout() {
		//CMN.Log("----refreshSVLayout", getScreenRotation());
		if(!suspensed) {
			float scaleX=1;
			float scaleY=1;
			int screenRotation = getScreenRotation();
			cameraManager.screenRotation = screenRotation;
			if (screenRotation == Surface.ROTATION_180) {
				scaleX = scaleY = -1;
			} else if (screenRotation == Surface.ROTATION_270) {
				scaleX = scaleY = -1;
			}
			surfaceView.setScaleX(scaleX);
			surfaceView.setScaleY(scaleY);
			CMN.Log("----refreshSVLayout", screenRotation, scaleX, scaleY);
			
			FrameLayout.LayoutParams targetLayoutParams = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
			ViewGroup.LayoutParams params = video_surface_frame.getLayoutParams();
			targetLayoutParams.gravity= Gravity.START|Gravity.TOP;
			targetLayoutParams.height=-1;
			targetLayoutParams.width=-1;
			
	//		if(opt.isFullScreen() && opt.isFullscreenHideNavigationbar())
	//			getWindowManager().getDefaultDisplay().getRealMetrics(dm);
	//		else
				getWindowManager().getDefaultDisplay().getMetrics(dm);
			
			
			int w = dm.widthPixels;
			int h = dm.heightPixels;
			//int w = getWindow().getDecorView().getWidth();
			//int h = getWindow().getDecorView().getHeight();
			
	//		if(!opt.isFullScreen())
	//			h-=CMN.getStatusBarHeight(this);
	
	//		w-=DockerMarginR+DockerMarginL;
	//		h-=DockerMarginT+DockerMarginB;
			
			float newW = w;
			float newH = h;
			params.width = w;
			params.height = h;
			pendingTransX = 0;
			pendingTransY = 0;
			pendingWidth = 0;
			pendingHeight = 0;
			pendingScale = 1;
			pendingMatchType = -1;
			
			int type=Match_Auto;
			
			switch(type){
				case Match_Auto:
					pendingMatchType=3;
				case Match_Width:
					//CMN.Log("Match_Width");
					OUT: {
						targetLayoutParams.width = (int) (0.5+w*1.0*mVideoWidth/mVideoVisibleWidth);
						newH = 1.f*w*mVideoVisibleHeight/mVideoVisibleWidth;
						float bottomPad = (mVideoVisibleHeight - mVideoHeight) * newH * 1.f/mVideoVisibleHeight;
						newH-=bottomPad;
						targetLayoutParams.height = (int) newH;
						if(newH<=h) {
							if(!videoMode&&pendingMatchType==3) break OUT;
							pendingTransY = -(newH - h + bottomPad) / 2; //targetLayoutParams.gravity = Gravity.CENTER;
						} else {
							if(videoMode&&pendingMatchType==3) break OUT;
							pendingTransY = -(newH - h + bottomPad) / 2;
						}
						pendingMatchType=Match_Width;
						break;
					}
				case Match_Height:
					//CMN.Log("Match_Height");
					newH = h; pendingTransY = 0;
					//bottomPad = 1.f*(mVideoVisibleHeight - mVideoHeight)*dm.density  * newH /mVideoVisibleHeight;
					//targetLayoutParams.height = (int) (h - bottomPad);
					targetLayoutParams.height = (int) (0.5+h*1.0*mVideoHeight/mVideoVisibleHeight);
					newW = 1.f * newH * mVideoVisibleWidth / mVideoVisibleHeight;
					float rightPad = (mVideoVisibleWidth - mVideoWidth) * newW * 1.f / mVideoVisibleWidth;
					newW-=rightPad;
					targetLayoutParams.width = (int) newW;
					if(newW<w){
						pendingTransX = -(newW - w) / 2;//targetLayoutParams.gravity=Gravity.TOP|Gravity.CENTER;
					}else {
						pendingTransX = -(newW - w) / 2;
					}
					pendingMatchType=Match_Height;
					break;
				case Match_None:
					break;
			}
			
			surfaceView.setLayoutParams(targetLayoutParams);
			surfaceView.setTranslationX(pendingTransX);
			surfaceView.setTranslationY(pendingTransY);
			vTranslate.set(pendingTransX, pendingTransY);
			vTranslateOrg = new PointF(vTranslate.x, vTranslate.y);
			pendingWidth=targetLayoutParams.width;
			pendingHeight=targetLayoutParams.height;
			//sWidth=targetLayoutParams.width;
			//sHeight=targetLayoutParams.height;
			//scale=1;
			sWidth=mVideoWidth;
			sHeight=mVideoHeight;
			pendingScale=scale=targetLayoutParams.width*1.0f/mVideoWidth;
			maxScale=pendingScale*10.5f;
			
			video_surface_frame.setLayoutParams(params);
		}
	}
	
	
	public static void setOnClickListenersOneDepth(ViewGroup vg, View.OnClickListener clicker, int depth) {
		int cc = vg.getChildCount();
		View ca;
		boolean longClickable = clicker instanceof View.OnLongClickListener;
		boolean touhable = clicker instanceof View.OnTouchListener;
		if(vg.isClickable()) {
			click(vg, clicker, longClickable, touhable);
		}
		for (int i = 0; i < cc; i++) {
			ca = vg.getChildAt(i);
			//CMN.Log("setOnClickListenersOneDepth", ca, (i+1)+"/"+(cc));
			if(ca instanceof ViewGroup) {
				if(--depth>0) {
					if(ca.isClickable()) {
						click(ca, clicker, longClickable, touhable);
					} else {
						setOnClickListenersOneDepth((ViewGroup) ca, clicker, depth);
					}
				}
			} else {
				int id = ca.getId();
				if(ca.getId()!=View.NO_ID){
					if(!(ca instanceof EditText) && ca.isEnabled()) {
						click(ca, clicker, longClickable, touhable);
					}
				}
			}
		}
	}
	
	private static void click(View ca, View.OnClickListener clicker, boolean longClickable, boolean touhable) {
		ca.setOnClickListener(clicker);
		if(longClickable&&ca.isLongClickable()) {
			ca.setOnLongClickListener((View.OnLongClickListener) clicker);
		}
		if(touhable) {
			ca.setOnTouchListener((View.OnTouchListener) clicker);
		}
	}
	
}
