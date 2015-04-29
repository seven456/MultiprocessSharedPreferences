/*
 * 创建日期：2013年11月22日 下午6:34:31
 */
package com.android.zgj.utils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import org.xmlpull.v1.XmlPullParserException;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * 支持多进程同步读写的SharedPreferences，基于源码android.app.SharedPreferencesImpl（Android 5.L_preview）修改；<br>
 * 系统源码android.app.SharedPreferencesImpl（Android 5.L_preview）虽然支持了 {@link Context#MODE_MULTI_PROCESS}，但依然有如下问题： <br>
 * 1、当多个进程同时写同一个文件时，有可能会导致文件损坏或者数据丢失； <br>
 * 2、当多个线程修改同一个文件时，必须使用getSharedPreferences方法才能检查并加载一次数据； <br>
 * 3、OnSharedPreferenceChangeListener不支持多进程； <br>
 * 解决方案： <br>
 * 1、在多进程同时写文件时使用本地文件独占锁FileLock（阻塞的），先加载文件中的数据和内存中的合并后再写入文件，解决文件损坏或者数据丢失问题；<br>
 * 2、在多个进程读文件时，当文件正在被修改，也使用文件独占锁（阻塞的），可以得到最新的数值；<br>
 * 3、由于文件锁在Linux平台是进程级别，同一进程内的多线程可以多次获得，使用ReentrantLock解决Android（Linux）平台FileLock只能控制进程间的文件锁，在同一个进程内的多线程之间无效和无法互斥的问题；<br>
 * 4、使用FileObserver解决多进程监听事件onSharedPreferenceChangeListener；<br>
 * 新增优化：
 * 1、新增：完全兼容本地已经存储的SharedPreferences文件的读写；<br>
 * 2、新增：每个SharedPreferences都使用FileObserver监听文件被修改情况，刷新每个进程中SharedPreferences对象中的数据，不再需要getSharedPreferences才能获得最新数据了；<br>
 * 3、新增：使用FileObserver监听文件变化后，与内存中的数据比对，OnSharedPreferenceChangeListener已经可以支持多进程监听；<br>
 * 4、修改：采用copy的方式备份主xml文件而不采用SharedPreferencesImpl原来的file.renameTo，是因为file.renameTo会导致FileObserver失效；
 * 5、新增：Edit对象的clear()方法删除内存中的数据后执行onSharedPreferenceChangeListener.onSharedPreferenceChanged(sharedPreferences, key)，系统原来的API不执行通知；<br>
 * 不足：<br>
 * 1、由于使用了进程级别文件锁和文件备份，会多丧失一点效率；<br>
 * <br>
 * 使用举例：<br>
 * 因为该类实现了系统标准的SharedPreferences接口，只要将“context.getSharedPreferences(name, mode)”改成“MultiprocessSharedPreferences.getSharedPreferences(context, name, mode)”即可；<br>
 * 另外mode的{@link Context#MODE_MULTI_PROCESS}状态已经不需要再使用了，因为默认已经支持；<br>
 * 
 * @author zhangguojun
 * @version 1.0
 * @since JDK1.6
 */
public final class MultiprocessSharedPreferences implements SharedPreferences {
	private static final String TAG = "MultiprocessSharedPreferences";
	private static final String TAG_TEMP = "MultiprocessSharedPreferences.temp";
	private static final boolean DEBUG = true;
	private static final WeakHashMap<String, MultiprocessSharedPreferences> sSharedPrefs = new WeakHashMap<String, MultiprocessSharedPreferences>();

	private static final int BUFFER_SIZE = 16 * 1024;
	private final File mFile;
	private final File mBackupFile;
	private final int mMode;
	private FileLockUtil mFileLockUtil;
	private FileObserver mFileObserver;

	private final Map<String, Object> mMap; // guarded by 'this'
	private boolean mLoaded = false; // guarded by 'this'
	private long mStatTimestamp; // guarded by 'this'
	private long mStatSize; // guarded by 'this'

	private static final Object mContent = new Object();
	private final WeakHashMap<OnSharedPreferenceChangeListener, Object> mListeners = new WeakHashMap<OnSharedPreferenceChangeListener, Object>();

	private static class ReflectionUtil {

		public static File contextGetSharedPrefsFile(Context context, String name) {
			try {
				return (File) context.getClass().getMethod("getSharedPrefsFile", String.class).invoke(context, name);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e);
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}

		public static int fileUtilsGetAttribute(String name) {
			try {
				Class<?> obj = Class.forName("android.os.FileUtils");
				return (Integer) obj.getField(name).get(obj);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (NoSuchFieldException e) {
				throw new RuntimeException(e);
			}
		}

		public static int fileUtilsSetPermissions(String file, int mode, int uid, int gid) {
			try {
				Class<?> obj = Class.forName("android.os.FileUtils");
				return (Integer) obj.getMethod("setPermissions", String.class, int.class, int.class, int.class).invoke(obj, file, mode, uid, gid);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e);
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}

		public static void queuedWorkAdd(Runnable finisher) {
			try {
				Class<?> obj = Class.forName("android.app.QueuedWork");
				obj.getMethod("add", Runnable.class).invoke(obj, finisher);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e);
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}

		public static void queuedWorkRemove(Runnable finisher) {
			try {
				Class<?> obj = Class.forName("android.app.QueuedWork");
				obj.getMethod("remove", Runnable.class).invoke(obj, finisher);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e);
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}

		public static ExecutorService queuedWorkSingleThreadExecutor() {
			try {
				Class<?> obj = Class.forName("android.app.QueuedWork");
				return (ExecutorService) obj.getMethod("singleThreadExecutor").invoke(obj);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				throw new RuntimeException(e);
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}

		public static void xmlUtilsWriteMapXml(@SuppressWarnings("rawtypes") Map val, OutputStream out) throws XmlPullParserException, IOException {
			try {
				Class<?> obj = Class.forName("com.android.internal.util.XmlUtils");
				obj.getMethod("writeMapXml", Map.class, OutputStream.class).invoke(obj, val, out);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				if (e.getCause() instanceof XmlPullParserException) {
					throw (XmlPullParserException) e.getTargetException();
				} else if (e.getCause() instanceof IOException) {
					throw (IOException) e.getTargetException();
				} else {
					throw new RuntimeException(e);
				}
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}

		@SuppressWarnings("rawtypes")
		public static HashMap xmlUtilsReadMapXml(InputStream in) throws XmlPullParserException, IOException {
			try {
				Class<?> obj = Class.forName("com.android.internal.util.XmlUtils");
				return (HashMap) obj.getMethod("readMapXml", InputStream.class).invoke(obj, in);
			} catch (ClassNotFoundException e) {
				throw new RuntimeException(e);
			} catch (IllegalArgumentException e) {
				throw new RuntimeException(e);
			} catch (IllegalAccessException e) {
				throw new RuntimeException(e);
			} catch (InvocationTargetException e) {
				if (e.getCause() instanceof XmlPullParserException) {
					throw (XmlPullParserException) e.getTargetException();
				} else if (e.getCause() instanceof IOException) {
					throw (IOException) e.getTargetException();
				} else {
					throw new RuntimeException(e);
				}
			} catch (NoSuchMethodException e) {
				throw new RuntimeException(e);
			}
		}
	}

	/**
	 * mode不需要使用{@link Context#MODE_MULTI_PROCESS}就可以支持多进程了；
	 * 
	 * @param mode
	 * 
	 * @see Context#MODE_PRIVATE
	 * @see Context#MODE_WORLD_READABLE
	 * @see Context#MODE_WORLD_WRITEABLE
	 */
	private MultiprocessSharedPreferences(Context context, String name, int mode) {
		mFile = ReflectionUtil.contextGetSharedPrefsFile(context, name);
		if (DEBUG) {
			Log.d(TAG_TEMP, android.os.Process.myPid() + " MultiprocessSharedPreferences.file = " + mFile);
		}
		mBackupFile = makeBackupFile(mFile);
		mMode = mode;
		mLoaded = false;
		mMap = new HashMap<String, Object>();
		checkCreateFile();
		createFileLock(context);
		createFileObserver();
		startLoadFromDisk(false);
	}

	private void checkCreateFile() {
		try {
			File parent = mFile.getParentFile();
			if (!parent.exists()) {
				if (parent.mkdirs()) {
					int S_IRWXU = ReflectionUtil.fileUtilsGetAttribute("S_IRWXU");
					int S_IRWXG = ReflectionUtil.fileUtilsGetAttribute("S_IRWXG");
					int S_IXOTH = ReflectionUtil.fileUtilsGetAttribute("S_IXOTH");
					ReflectionUtil.fileUtilsSetPermissions(parent.getPath(), S_IRWXU | S_IRWXG | S_IXOTH, -1, -1);
				} else {
					Log.e(TAG, "Couldn't create directory for SharedPreferences file " + mFile);
				}
			}
			if (!mFile.exists()) {
				mFile.createNewFile();
			}
		} catch (IOException e) {
			Log.e(TAG, "Couldn't create SharedPreferences file " + mFile, e);
		}
	}

	private void createFileLock(Context context) {
		File fileLock = mFile;
		// 解决外网某些设备在应用卸载后有残留，无法重新安装问题；
		// 联系出现问题的用户后发现，使用系统自带的应用管理和第三方软件卸载都会导致卸载残留；
		// 卸载后发现文件锁/data/data/<packageName>/xxx/xxx.lock文件在卸载应用时没有被删除，导致无法重装应用；
		// Android4.1.1_r1及以上版本开始，在安装程序时，当有卸载残留（有/data/data/<packageName>目录存在）时新增错误提示“INSTALL_FAILED_UID_CHANGED”；
		// Android4.1.1_r1及以上版，将文件锁文件存储到/sdcard/Android/data/<packageName>私有目录（该目录也会随程序的卸载而自动删除），不再存储到/data/data/<packageName>下面的私有目录；
		if (Build.VERSION.SDK_INT >= 16) { // Android4.1.1_r1
			// 从Android3.1开始支持PTP（图像传输协议）或MTP（媒体传输协议）功能，让连接的Camera和其他设备可以直接保持连接，所以无需再动态监听Sdcard的挂载状态；
			if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
				fileLock = new File(Environment.getExternalStorageDirectory(), "Android" + File.separator + "data" + File.separator + context.getPackageName()
						+ File.separator + "files" + File.separator + mFile.getName());
			}
		}
		mFileLockUtil = new FileLockUtil(TAG, fileLock);
	}

	private void createFileObserver() {
		mFileObserver = new FileObserver(mFile.getPath(), FileObserver.MODIFY) { // FileObserver只对已经存在的文件有效，所以在注册事件之前必须创建好文件；
			@Override
			public void onEvent(final int event, final String path) {
				if (DEBUG) {
					Log.d(TAG_TEMP, android.os.Process.myPid() + " createFileObserver.mFileObserver.onEvent.event = " + event);
				}
				startLoadFromDisk(true);
			}
		};
		mFileObserver.startWatching();
	}

	private void destroyFileObserver() {
		mFileObserver.stopWatching();
	}

	/**
	 * 获取支持多进程的SharedPreferences；
	 * 
	 * @param context
	 * @param name
	 * @return
	 * 
	 * @see Context#MODE_PRIVATE
	 * @see Context#MODE_WORLD_READABLE
	 * @see Context#MODE_WORLD_WRITEABLE
	 */
	public static SharedPreferences getSharedPreferences(Context context, String name, int mode) {
		MultiprocessSharedPreferences sp;
		synchronized (sSharedPrefs) {
			sp = sSharedPrefs.get(name);
			if (DEBUG) {
				Log.d(TAG_TEMP, android.os.Process.myPid() + " getSharedPreferences.loadFromDiskLocked.name = " + name + ", sp = " + sp);
			}
			if (sp == null) {
				sp = new MultiprocessSharedPreferences(context, name, mode);
				sSharedPrefs.put(name, sp);
			}
		}
		return sp;
	}

	/**
	 * 在应用销毁时调用（可选的，内部文件监控的观察者都是weak引用）；
	 */
	public static void onApplicationDestroy() {
		Iterator<Entry<String, MultiprocessSharedPreferences>> it = sSharedPrefs.entrySet().iterator();
		while (it.hasNext()) {
			Entry<String, MultiprocessSharedPreferences> entry = it.next();
			MultiprocessSharedPreferences sp = entry.getValue();
			if (sp != null) {
				sp.destroyFileObserver();
			}
			it.remove();
		}
	}

	private void startLoadFromDisk(final boolean isNeedNotify) {
		synchronized (this) {
			mLoaded = false;
		}
		new Thread(TAG + " startLoadFromDisk") {
			@Override
			public void run() {
				synchronized (MultiprocessSharedPreferences.this) {
					loadFromDiskLocked(isNeedNotify);
				}
			}
		}.start();
	}

	private void loadFromDiskLocked(boolean isNeedNotify) {
		if (DEBUG) {
			Log.d(TAG_TEMP, android.os.Process.myPid() + " loadFromDiskLocked.start");
		}
		mFileLockUtil.tryLock("loadFromDiskLocked");
		try {
			if (!mLoaded) {
				if (mBackupFile.exists()) {
					checkCreateFile();
					destroyFileObserver();
					createFileObserver();
					if (copyFile(mBackupFile, mFile)) {
						mBackupFile.delete();
					}
				}
				// Debugging
				if (mFile.exists() && !mFile.canRead()) {
					Log.w(TAG, "Attempt to read preferences file " + mFile + " without permission");
				}
				Map<String, Object> map = null;
				boolean hasFileChanged = hasFileChanged();
				if (DEBUG) {
					Log.d(TAG_TEMP, android.os.Process.myPid() + " loadFromDiskLocked.hasFileChanged = " + hasFileChanged);
				}
				if (hasFileChanged) {
					map = readToMap(mFile);
					if (map != null) {
						Set<String> keysModified = updateMap(mMap, map, isNeedNotify);
						mStatTimestamp = mFile.lastModified();
						mStatSize = mFile.length();
						notifyListeners(keysModified);
						if (DEBUG) {
							Log.d(TAG_TEMP, android.os.Process.myPid() + " loadFromDiskLocked.mMap = " + mMap);
						}
					}
				}
			}
		} finally {
			mLoaded = true;
			notifyAll();
			mFileLockUtil.unlock("loadFromDiskLocked");
			if (DEBUG) {
				Log.d(TAG_TEMP, android.os.Process.myPid() + " loadFromDiskLocked.end");
			}
		}
	}

	private Set<String> updateMap(Map<String, Object> oleMap, Map<String, Object> newMap, boolean getKeysModified) {
		Set<String> keysModified = null;
		if (getKeysModified) {
			keysModified = new HashSet<String>();
		}
		Iterator<Entry<String, Object>> it = oleMap.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<String, Object> entry = it.next();
			String key = entry.getKey();
			Object value = entry.getValue();
			if (newMap.containsKey(key)) {
				if (value != null && !value.equals(newMap.get(key)) || value == null && newMap.get(key) != null) {
					entry.setValue(newMap.get(key)); // 更新；
					if (getKeysModified) {
						keysModified.add(key);
					}
				}
				newMap.remove(key);
			} else {
				it.remove(); // 删除；
				if (getKeysModified) {
					keysModified.add(key);
				}
			}
		}
		Iterator<Entry<String, Object>> itAdd = newMap.entrySet().iterator();
		while (itAdd.hasNext()) {
			Map.Entry<String, Object> entry = itAdd.next();
			String key = entry.getKey();
			Object value = entry.getValue();
			oleMap.put(key, value); // 新增；
			if (getKeysModified) {
				keysModified.add(key);
			}
			itAdd.remove();
		}
		return keysModified;
	}

	private static File makeBackupFile(File prefsFile) {
		return new File(prefsFile.getPath() + ".bak");
	}

	private boolean hasFileChanged() {
		return mStatTimestamp != mFile.lastModified() || mStatSize != mFile.length();
	}

	@Override
	public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		synchronized (this) {
			mListeners.put(listener, mContent);
		}
	}

	@Override
	public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
		synchronized (this) {
			mListeners.remove(listener);
		}
	}

	private void awaitLoadedLocked() {
		while (!mLoaded) {
			try {
				wait();
			} catch (InterruptedException unused) {
			}
		}
	}

	@Override
	public Map<String, ?> getAll() {
		synchronized (this) {
			awaitLoadedLocked();
			// noinspection unchecked
			return new HashMap<String, Object>(mMap);
		}
	}

	@Override
	public String getString(String key, String defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			String v = (String) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	// @Override // Android 3.0
	@Override
	public Set<String> getStringSet(String key, Set<String> defValues) {
		synchronized (this) {
			awaitLoadedLocked();
			@SuppressWarnings("unchecked")
			Set<String> v = (Set<String>) mMap.get(key);
			return v != null ? v : defValues;
		}
	}

	@Override
	public int getInt(String key, int defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Integer v = (Integer) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	@Override
	public long getLong(String key, long defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Long v = (Long) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	@Override
	public float getFloat(String key, float defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Float v = (Float) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	@Override
	public boolean getBoolean(String key, boolean defValue) {
		synchronized (this) {
			awaitLoadedLocked();
			Boolean v = (Boolean) mMap.get(key);
			return v != null ? v : defValue;
		}
	}

	@Override
	public boolean contains(String key) {
		synchronized (this) {
			awaitLoadedLocked();
			return mMap.containsKey(key);
		}
	}

	@Override
	public Editor edit() {
		// TODO: remove the need to call awaitLoadedLocked() when
		// requesting an editor. will require some work on the
		// Editor, but then we should be able to do:
		//
		// context.getSharedPreferences(..).edit().putString(..).apply()
		//
		// ... all without blocking.
		synchronized (this) {
			awaitLoadedLocked();
		}
		return new EditorImpl();
	}

	// Return value from EditorImpl#commitToMemory()
	private static class MemoryCommitResult {
		public boolean changesMade; // any keys different?
		public Set<String> keysModified; // may be null
	}

	public final class EditorImpl implements Editor {
		private final Map<String, Object> mModified = new HashMap<String, Object>();
		private boolean mClear = false;

		@Override
		public Editor putString(String key, String value) {
			synchronized (this) {
				if (DEBUG) {
					Log.d(TAG_TEMP, android.os.Process.myPid() + " putString.key = " + key + ", value = " + value);
				}
				mModified.put(key, value);
				return this;
			}
		}

		// @Override // Android 3.0
		@Override
		public Editor putStringSet(String key, Set<String> values) {
			synchronized (this) {
				mModified.put(key, values);
				return this;
			}
		}

		@Override
		public Editor putInt(String key, int value) {
			synchronized (this) {
				mModified.put(key, value);
				return this;
			}
		}

		@Override
		public Editor putLong(String key, long value) {
			synchronized (this) {
				mModified.put(key, value);
				return this;
			}
		}

		@Override
		public Editor putFloat(String key, float value) {
			synchronized (this) {
				mModified.put(key, value);
				return this;
			}
		}

		@Override
		public Editor putBoolean(String key, boolean value) {
			synchronized (this) {
				mModified.put(key, value);
				return this;
			}
		}

		@Override
		public Editor remove(String key) {
			synchronized (this) {
				mModified.put(key, this);
				return this;
			}
		}

		@Override
		public Editor clear() {
			synchronized (this) {
				mClear = true;
				return this;
			}
		}

		@Override
		public void apply() {
			final CountDownLatch writtenToDiskLatch = new CountDownLatch(1);
			final Runnable awaitCommit = new Runnable() {
				@Override
				public void run() {
					try {
						writtenToDiskLatch.await();
					} catch (InterruptedException ignored) {
					}
				}
			};
			ReflectionUtil.queuedWorkAdd(awaitCommit);
			final Runnable writeToDiskRunnable = new Runnable() {
				@Override
				public void run() {
					try {
						innerCommit();
					} finally {
						writtenToDiskLatch.countDown();
						awaitCommit.run();
						ReflectionUtil.queuedWorkRemove(awaitCommit);
					}
				}
			};
			ReflectionUtil.queuedWorkSingleThreadExecutor().execute(writeToDiskRunnable);
		}

		// Returns true if any changes were made
		private MemoryCommitResult commitToMemory() {
			MemoryCommitResult mcr = new MemoryCommitResult();
			boolean hasListeners = !mListeners.isEmpty();
			if (hasListeners) {
				mcr.keysModified = new HashSet<String>();
			}
			Map<String, Object> mapFile = null;
			if (hasFileChanged()) {
				mapFile = readToMap(mFile); // 本地文件中的数据比内存中的新；
			}
			synchronized (this) {
				if (mClear) {
					if (!mMap.isEmpty()) {
						Iterator<Entry<String, Object>> it = mMap.entrySet().iterator();
						while (it.hasNext()) {
							Entry<String, Object> entry = it.next();
							String key = entry.getKey();
							if (mapFile == null || mapFile != null && !mapFile.containsKey(key)) {
								it.remove(); // 删除；
								if (hasListeners) {
									mcr.keysModified.add(key);
								}
							}
						}
						mcr.changesMade = true;
					}
				}

				if (hasListeners && mapFile != null) {
					Set<String> keysModified = updateMap(mModified, mapFile, true);
					if (keysModified != null) {
						mcr.keysModified.addAll(keysModified);
						keysModified.clear();
					}
				}
				if (DEBUG) {
					Log.d(TAG_TEMP, android.os.Process.myPid() + " " + android.os.Process.myTid() + " commitToMemory.start.mModified = " + mModified
							+ ", mMap = " + mMap);
				}
				Iterator<Entry<String, Object>> it = mModified.entrySet().iterator();
				while (it.hasNext()) {
					Entry<String, Object> entry = it.next();
					String key = entry.getKey();
					Object value = entry.getValue();
					// Android 5.L_preview : "this" is the magic value for a removal mutation. In addition,
					// setting a value to "null" for a given key is specified to be
					// equivalent to calling remove on that key.
					if (value == this || value == null) {
						if (mMap.containsKey(key)) {
							mMap.remove(key);
							if (hasListeners) {
								mcr.keysModified.add(key);
							}
							mcr.changesMade = true;
						}
					} else {
						if (!mMap.containsKey(key) || (mMap.containsKey(key) && !value.equals(mMap.get(key)))) {
							mMap.put(key, value);
							if (hasListeners) {
								mcr.keysModified.add(key);
							}
							mcr.changesMade = true;
						}
					}
					it.remove();
				}
				mModified.clear();
				if (DEBUG) {
					Log.d(TAG_TEMP, android.os.Process.myPid() + " " + android.os.Process.myTid() + " commitToMemory.end");
				}
			}
			return mcr;
		}

		@Override
		public boolean commit() {
			if (Build.VERSION.SDK_INT >= 9) { // Android 2.3.1_r1
				final CountDownLatch writtenToDiskLatch = new CountDownLatch(1);
				final Runnable awaitCommit = new Runnable() {
					@Override
					public void run() {
						try {
							writtenToDiskLatch.await();
						} catch (InterruptedException ignored) {
						}
					}
				};
				ReflectionUtil.queuedWorkAdd(awaitCommit);
				try {
					return innerCommit();
				} finally {
					writtenToDiskLatch.countDown();
					awaitCommit.run();
					ReflectionUtil.queuedWorkRemove(awaitCommit);
				}
			} else {
				return innerCommit();
			}
		}

		private boolean innerCommit() {
			synchronized (MultiprocessSharedPreferences.this) {
				if (DEBUG) {
					Log.d(TAG_TEMP, android.os.Process.myPid() + " " + android.os.Process.myTid() + " commit.tryLock.start");
				}
				mFileLockUtil.tryLock("commit");
				if (DEBUG) {
					Log.d(TAG_TEMP, android.os.Process.myPid() + " " + android.os.Process.myTid() + " commit.tryLock.end");
				}
				try {
					if (DEBUG) {
						Log.d(TAG_TEMP, android.os.Process.myPid() + " " + android.os.Process.myTid() + " commit.try.start");
					}
					boolean succeed = false;
					MemoryCommitResult mcr = commitToMemory();
					long time = SystemClock.elapsedRealtime();
					if (!mcr.changesMade) {
						succeed = true;
					} else {
						succeed = writeToFile(mMap);
					}
					if (DEBUG) {
						Log.d(TAG_TEMP, android.os.Process.myPid() + " commit.time = " + (SystemClock.elapsedRealtime() - time) + ", mcr.changesMade = "
								+ mcr.changesMade + ", succeed = " + succeed + ", mMap = " + mMap);
					}
					notifyListeners(mcr.keysModified);
					return succeed;
				} finally {
					if (DEBUG) {
						Log.d(TAG_TEMP, android.os.Process.myPid() + " " + android.os.Process.myTid() + " commit.finally.unlock");
					}
					mFileLockUtil.unlock("commit");
				}
			}
		}
	}

	private void notifyListeners(final Set<String> keysModified) {
		if (keysModified != null && !keysModified.isEmpty()) {
			Runnable runnable = new Runnable() {
				@Override
				public void run() {
					if (!mListeners.isEmpty()) {
						Iterator<String> it = keysModified.iterator();
						while (it.hasNext()) {
							String key = it.next();
							for (Entry<OnSharedPreferenceChangeListener, Object> entry : mListeners.entrySet()) {
								entry.getKey().onSharedPreferenceChanged(MultiprocessSharedPreferences.this, key);
							}
							it.remove();
						}
					}
				}
			};
			if (Looper.myLooper() == Looper.getMainLooper()) {
				runnable.run();
			} else {
				// Run this function on the main thread.
				new Handler(Looper.getMainLooper()).post(runnable);
			}
		}
	}

	private boolean writeToFile(Map<String, Object> map) {
		if (DEBUG) {
			Log.d(TAG_TEMP, android.os.Process.myPid() + " writeToFile");
		}
		if (mFile.exists()) {
			if (!mBackupFile.exists()) {
				// 这里采用copy而不采用SharedPreferencesImpl原来的file.renameTo是因为file.renameTo会导致FileObserver失效；
				if (!copyFile(mFile, mBackupFile)) { // 先备份文件，防止kill时，数据丢失；
					Log.e(TAG, "Couldn't copy file " + mFile + " to backup file " + mBackupFile);
					return false;
				} else {
					setFilePermissionsFromMode(mBackupFile.getPath(), mMode, 0);
				}
			}
		} else { // 假如其他未知情况下删除了文件，这里重新创建文件和文件监听器；
			checkCreateFile();
			createFileObserver();
		}
		BufferedOutputStream bos = null;
		// Attempt to write the file, delete the backup and return true as atomically as
		// possible.  If any exception occurs, delete the new file; next time we will restore
		// from the backup.
		try {
			FileOutputStream fos = new FileOutputStream(mFile);
			bos = new BufferedOutputStream(fos, BUFFER_SIZE);
			ReflectionUtil.xmlUtilsWriteMapXml(map, bos);
			fos.getFD().sync();
			setFilePermissionsFromMode(mFile.getPath(), mMode, 0);
			mStatTimestamp = mFile.lastModified();
			mStatSize = mFile.length();
			// Writing was successful, delete the backup file if there is one.
			mBackupFile.delete();
			return true;
		} catch (XmlPullParserException e) {
			Log.w(TAG, "writeToFile: Got exception:", e);
		} catch (FileNotFoundException e) {
			Log.w(TAG, "writeToFile: Got exception:", e);
		} catch (IOException e) {
			Log.w(TAG, "writeToFile: Got exception:", e);
		} finally {
			try {
				if (bos != null) {
					bos.close();
				}
			} catch (IOException unused) {
			}
		}
		// Clean up an unsuccessfully written file
		if (mFile.exists()) {
			try {
				FileOutputStream fos = new FileOutputStream(mFile); // 只清空文件内容，不delete，delete会导致FileObserver失效；
				fos.close();
			} catch (Exception e) {
				Log.e(TAG, "Couldn't clean up partially-written file " + mFile, e);
			}
		}
		return false;
	}

	private boolean copyFile(File srcFile, File destFile) {
		FileInputStream fis = null;
		BufferedOutputStream bos = null;
		try {
			fis = new FileInputStream(srcFile);
			bos = new BufferedOutputStream(new FileOutputStream(destFile), BUFFER_SIZE);
			byte[] buffer = new byte[BUFFER_SIZE];
			int len;
			while ((len = fis.read(buffer)) != -1) {
				bos.write(buffer, 0, len);
			}
			bos.flush();
			return true;
		} catch (IOException unused) {
		} finally {
			try {
				if (fis != null) {
					fis.close();
				}
				if (bos != null) {
					bos.close();
				}
			} catch (IOException unused) {
			}
		}
		return false;
	}

	private static void setFilePermissionsFromMode(String name, int mode, int extraPermissions) {
		int S_IRUSR = ReflectionUtil.fileUtilsGetAttribute("S_IRUSR");
		int S_IWUSR = ReflectionUtil.fileUtilsGetAttribute("S_IWUSR");
		int S_IRGRP = ReflectionUtil.fileUtilsGetAttribute("S_IRGRP");
		int S_IWGRP = ReflectionUtil.fileUtilsGetAttribute("S_IWGRP");
		int S_IROTH = ReflectionUtil.fileUtilsGetAttribute("S_IROTH");
		int S_IWOTH = ReflectionUtil.fileUtilsGetAttribute("S_IWOTH");
		int perms = S_IRUSR | S_IWUSR | S_IRGRP | S_IWGRP | extraPermissions;
		if ((mode & Context.MODE_WORLD_READABLE) != 0) {
			perms |= S_IROTH;
		}
		if ((mode & Context.MODE_WORLD_WRITEABLE) != 0) {
			perms |= S_IWOTH;
		}
		if (DEBUG) {
			Log.i(TAG, "File " + name + ": mode=0x" + Integer.toHexString(mode) + ", perms=0x" + Integer.toHexString(perms));
		}
		ReflectionUtil.fileUtilsSetPermissions(name, perms, -1, -1);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> readToMap(File file) {
		Map<String, Object> map = null;
		BufferedInputStream bis = null;
		try {
			if (file.canRead()) {
				bis = new BufferedInputStream(new FileInputStream(file), BUFFER_SIZE);
				map = ReflectionUtil.xmlUtilsReadMapXml(bis);
			}
		} catch (XmlPullParserException e) {
			Log.w(TAG, "readToMap", e);
		} catch (FileNotFoundException e) {
			Log.w(TAG, "readToMap", e);
		} catch (IOException e) {
			Log.w(TAG, "readToMap", e);
		} catch (AssertionError e) { // 解决外网崩溃：java.lang.AssertionError at android.util.Xml.newPullParser(Xml.java:97) at com.android.internal.util.XmlUtils.readMapXml(XmlUtils.java:492)
			Log.w(TAG, "readToMap", e);
		} catch (ArrayIndexOutOfBoundsException e) { // 特殊机型（LG D958）有时候报：java.lang.ArrayIndexOutOfBoundsException: src.length=8192 srcPos=1 dst.length=8192 dstPos=0 length=-1 at java.lang.System.arraycopy(Native Method) at org.kxml2.io.KXmlParser.fillBuffer(KXmlParser.java:1489)
			Log.w(TAG, "readToMap", e);
			// TODO 这个异常不知道要不要删除文件？
		} finally {
			try {
				if (bis != null) {
					bis.close();
				}
			} catch (IOException unused) {
			}
		}
		return map;
	}
}