# MultiprocessSharedPreferences
Android Multi process SharedPreferences （支持多进程同步读写的SharedPreferences）

## 说明
	使用ContentProvider实现多进程SharedPreferences读写;
	1、ContentProvider天生支持多进程访问；
	2、使用内部私有BroadcastReceiver实现多进程OnSharedPreferenceChangeListener监听；
	
	使用方法：AndroidManifest.xml中添加provider申明：
	<provider android:name="com.android.zgj.utils.MultiprocessSharedPreferences"
		android:authorities="com.qihoo.appstore.MultiprocessSharedPreferences"
		android:process="com.qihoo.appstore.MultiprocessSharedPreferences"
		android:exported="false" />
	<!-- authorities属性里面最好使用包名做前缀，apk在安装时authorities同名的provider需要校验签名，否则无法安装；--!/>
	
	ContentProvider方式实现要注意：
	1、当ContentProvider所在进程android.os.Process.killProcess(pid)时，会导致整个应用程序完全意外退出或者ContentProvider所在进程重启；
	重启报错信息：Acquiring provider <processName> for user 0: existing object's process dead；
	2、如果设备处在“安全模式”下，只有系统自带的ContentProvider才能被正常解析使用，因此put值时默认返回false，get值时默认返回null；
	
	其他方式实现ContentProvider的问题：
	使用FileLock和FileObserver也可以实现多进程SharedPreferences读写，但是会有兼容性问题：
	1、某些设备上卸载程序时锁文件无法删除导致卸载残留，进而导致无法重新安装该程序（报INSTALL_FAILED_UID_CHANGED错误）；
	2、某些设备上FileLock会导致僵尸进程出现进而导致耗电；
	3、僵尸进程出现后，正常进程的FileLock会一直阻塞等待僵尸进程中的FileLock释放，导致进程一直阻塞；

## License

    Copyright (C) 2014 seven456@gmail.com
    
    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at
    
       http://www.apache.org/licenses/LICENSE-2.0
    
    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
