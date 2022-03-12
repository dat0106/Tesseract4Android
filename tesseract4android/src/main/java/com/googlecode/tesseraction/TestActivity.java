package com.googlecode.tesseraction;

import android.app.Activity;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

public class TestActivity extends Activity {
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		FrameLayout fl = new FrameLayout(this);
		setContentView(fl);
		
		TesseractPluginTest.Test(this);
	}
	
}
