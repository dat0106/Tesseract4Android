package com.googlecode.tesseraction;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.widget.Toast;

import com.googlecode.tesseract.android.TessBaseAPI;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

public class TesseractPluginTest {
	// https://gist.github.com/alvareztech/6627673
	
	// From assets
	public static Bitmap getBitmapFromAsset(Context context, String strName) {
		AssetManager assetManager = context.getAssets();
		InputStream istr;
		Bitmap bitmap = null;
		try {
			istr = assetManager.open(strName);
			bitmap = BitmapFactory.decodeStream(istr);
		} catch (IOException e) {
			return null;
		}
		return bitmap;
	}
	
	// From raw
	public static Bitmap getBitmapFromAsset(Context context, int id) {
		InputStream input = context.getResources().openRawResource(id);
		Bitmap bitmap = null;
		try {
			bitmap = BitmapFactory.decodeStream(input);
		} catch (Exception e) {
			return null;
		}
		return bitmap;
	}
	
	public static void Test(Context context) {
		CMN.rt();
		TessBaseAPI tess = new TessBaseAPI();

		Utils.contentResolver = context.getContentResolver();
		
		tess.init(null, "eng");
		
		tess.setImage(getBitmapFromAsset(context, R.raw.text));
		
		//tess.setImage(new Data[], , , ,);
		
		String text = tess.getHOCRText(0);
		CMN.pt("test_done", text);
		
		try {
			Toast.makeText(context, text, 1).show();
		} catch (Exception e) {
			e.printStackTrace();
		}
		
		tess.recycle();
	}
}
