package com.android.zgj;

import com.android.zgj.utils.MultiprocessSharedPreferences;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class CoreService extends Service {
	private static final String TAG = "CoreService";
	public static final String SP_NAME = "test";
	public static final String SP_KEY = "aaa";
	
	@Override
	public void onCreate() {
		super.onCreate();
		
		SharedPreferences sharedPreferences = MultiprocessSharedPreferences.getSharedPreferences(this, SP_NAME, Context.MODE_PRIVATE);
		Log.d(TAG, "onCreate." + SP_KEY + " = " + sharedPreferences.getString(SP_KEY, null));
		sharedPreferences.registerOnSharedPreferenceChangeListener(new OnSharedPreferenceChangeListener() {
			@Override
			public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
				String msg = "onSharedPreferenceChanged." + SP_KEY + " = " + sharedPreferences.getString(SP_KEY, null);
				Log.d(TAG, msg);
				Toast.makeText(CoreService.this, msg, Toast.LENGTH_SHORT).show();
			}
		});
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
}