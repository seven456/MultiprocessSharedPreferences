package com.android.zgj.utils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.OverlappingFileLockException;
import java.util.concurrent.locks.ReentrantLock;
import android.util.Log;
import com.android.zgj.BuildConfig;

/**
 * 描述：文件锁工具类；
 * @author zhangguojun
 * @version 1.0
 * @since JDK1.6
 */
public class FileLockUtil {
	private static final boolean DEBUG = BuildConfig.DEBUG;
	private final String mTag;
	private final File mParent;
	private final File mLock;
	private final ReentrantLock mReentrantLock;
	private FileLock mFileLock;
	private FileOutputStream mFos;

	/**
	 * @param lock 需要对其进行锁定的文件对象，这里采用的是创建一个文件名称相同后缀是“.lock”的文件，方便一次锁定时间内，可以操作多个文件；<br>
	 * 如：要锁定“/sdcard/111.txt”，会创建“/sdcard/111.txt.lock”，并对“.lock”进行锁定和解除锁定；
	 */
	public FileLockUtil(String tag, File lock) {
		mTag = tag;
		if (DEBUG) {
			Log.d(mTag, android.os.Process.myPid() + " new FileLockUtil()");
		}
		mParent = lock.getParentFile();
		mLock = new File(mParent, lock.getName() + ".lock");
		mReentrantLock = new ReentrantLock();
		checkCreateFile();
	}

	private void checkCreateFile() {
		try {
			if (!mParent.exists()) {
				mParent.mkdirs();
			}
			if (!mLock.exists()) {
				mLock.createNewFile();
			}
		} catch (Exception unused) {
		}
	}

	/**
	 * 尝试获得文件锁，该操作是阻塞的，直到成功获得锁才结束方法执行；<br>
	 * 由于文件锁在Linux平台是进程级别，同一进程内的多线程可以多次获得；<br>
	 * Android（Linux）平台FileLock只能控制进程间的文件锁，在同一个进程内的多线程之间是无效的，无法互斥；<br>
	 * 这里采用ReentrantLock解决同一进程内多线程之间文件锁无效问题；
	 * @param mTag
	 */
	public void tryLock(String tag) {
		if (DEBUG) {
			Log.d(mTag, android.os.Process.myPid() + " " + android.os.Process.myTid() + " " + tag + " tryLock.wait");
		}
		mReentrantLock.lock();
		if (DEBUG) {
			Log.d(mTag, android.os.Process.myPid() + " " + android.os.Process.myTid() + " " + tag + " tryLock.wait.end");
		}
		lock(tag);
	}

	private void lock(String tag) {
		if (mLock.exists()) { // 如果锁文件创建失败，文件锁逻辑直接失效；
			while (true) {
				try {
					if (DEBUG) {
						Log.d(mTag, android.os.Process.myPid() + " " + android.os.Process.myTid() + " " + tag + " lock.wait");
					}
					mFos = new FileOutputStream(mLock);
					mFileLock = mFos.getChannel().lock(); // 进程间是阻塞的，线程间linux平台没有互斥效果；
				} catch (OverlappingFileLockException unused) { // Windows： 不同jvm获取同一文件锁时，先拿到的获得锁，后获取的抛出文件重叠锁异常OverlappingFileLockException；
					closeFile();
					if (DEBUG) {
						Log.d(mTag, "OverlappingFileLockException " + android.os.Process.myPid() + " " + tag);
					}
				} catch (ClosedChannelException unused) {
					if (DEBUG) {
						Log.d(mTag, "ClosedChannelException " + android.os.Process.myPid() + " " + tag);
					}
					break;
				} catch (FileLockInterruptionException unused) {
					closeFile();
					if (DEBUG) {
						Log.d(mTag, "FileLockInterruptionException " + android.os.Process.myPid() + " " + tag);
					}
					break;
				} catch (NonWritableChannelException unused) {
					closeFile();
					if (DEBUG) {
						Log.d(mTag, "NonWritableChannelException " + android.os.Process.myPid() + " " + tag);
					}
					break;
				} catch (IOException unused) { // Linux：不同jvm获取同一文件锁时，先拿到的获得锁，后获取的抛出异常；
					closeFile();
					if (DEBUG) {
						Log.d(mTag, "IOException " + android.os.Process.myPid() + " " + tag);
					}
				}
				if (locked()) {
					if (DEBUG) {
						Log.d(mTag, android.os.Process.myPid() + " " + android.os.Process.myTid() + " " + tag + " lock.end " + mFileLock);
					}
					break;
				}
			}
		}
	}

	private boolean locked() {
		return mFileLock != null && mFileLock.isValid();
	}

	/**
	 * 释放文件锁
	 * @param fileLock
	 */
	public void unlock(String tag) {
		unlock(tag, false);
	}

	/**
	 * 释放文件锁
	 * @param fileLock
	 * @param isDelLockFile
	 */
	public void unlock(String tag, boolean isDelLockFile) {
		try {
			if (mFileLock != null) {
				mFileLock.release();
				mFileLock.channel().close();
				mFileLock = null;
			}
			closeFile();
			if (isDelLockFile) {
				if (DEBUG) {
					Log.d(mTag, android.os.Process.myPid() + " " + android.os.Process.myTid() + " " + tag + " unlock.exists = " + mLock.exists()
							+ ", delete = " + mLock.delete());
				}
			}
		} catch (IOException unused) {
			unused.printStackTrace();
		} finally {
			if (DEBUG) {
				Log.d(mTag, android.os.Process.myPid() + " " + android.os.Process.myTid() + " " + tag + " unlock");
			}
			mReentrantLock.unlock();
		}
	}

	private void closeFile() {
		if (mFos != null) {
			try {
				mFos.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}