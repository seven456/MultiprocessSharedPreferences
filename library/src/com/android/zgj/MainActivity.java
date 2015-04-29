package com.android.zgj;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.EditText;

import com.android.zgj.utils.MultiprocessSharedPreferences;
import com.android.zgj.R;

public class MainActivity extends Activity {
	private static final String TAG = "MainActivity";
	private EditText mEditText;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		mEditText = (EditText) findViewById(R.id.edittext_msg);
		
		startService(new Intent(this, CoreService.class)); // Across processes

		final SharedPreferences sharedPreferences = MultiprocessSharedPreferences.getSharedPreferences(this, CoreService.SP_NAME, Context.MODE_PRIVATE);
		mEditText.addTextChangedListener(new TextWatcher() {
			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				boolean commit = sharedPreferences.edit().putString(CoreService.SP_KEY, mEditText.getText().toString()).commit();
				Log.d(TAG, "commit = " + commit);
			}
		});

	}
}
