# MultiprocessSharedPreferences
Android 支持多进程同步读写的SharedPreferences (Android support Multi process SharedPreferences)

## 说明
	支持多进程同步读写的SharedPreferences，基于源码android.app.SharedPreferencesImpl（Android 5.L_preview）修改；
	系统源码android.app.SharedPreferencesImpl（Android 5.L_preview）虽然支持了 {@link Context#MODE_MULTI_PROCESS}，但依然有如下问题： 
	1、当多个进程同时写同一个文件时，有可能会导致文件损坏或者数据丢失； 
	2、当多个线程修改同一个文件时，必须使用getSharedPreferences方法才能检查并加载一次数据； 
	3、OnSharedPreferenceChangeListener不支持多进程； 
	解决方案： 
	1、在多进程同时写文件时使用本地文件独占锁FileLock（阻塞的），先加载文件中的数据和内存中的合并后再写入文件，解决文件损坏或者数据丢失问题；
	2、在多个进程读文件时，当文件正在被修改，也使用文件独占锁（阻塞的），可以得到最新的数值；
	3、由于文件锁在Linux平台是进程级别，同一进程内的多线程可以多次获得，使用ReentrantLock解决Android（Linux）平台FileLock只能控制进程间的文件锁，在同一个进程内的多线程之间无效和无法互斥的问题；
	4、使用FileObserver解决多进程监听事件onSharedPreferenceChangeListener；
	新增优化：
	1、新增：完全兼容本地已经存储的SharedPreferences文件的读写；
	2、新增：每个SharedPreferences都使用FileObserver监听文件被修改情况，刷新每个进程中SharedPreferences对象中的数据，不再需要getSharedPreferences才能获得最新数据了；
	3、新增：使用FileObserver监听文件变化后，与内存中的数据比对，OnSharedPreferenceChangeListener已经可以支持多进程监听；
	4、修改：采用copy的方式备份主xml文件而不采用SharedPreferencesImpl原来的file.renameTo，是因为file.renameTo会导致FileObserver失效；
	5、新增：Edit对象的clear()方法删除内存中的数据后执行onSharedPreferenceChangeListener.onSharedPreferenceChanged(sharedPreferences, key)，系统原来的API不执行通知；
	不足：
	1、由于使用了进程级别文件锁和文件备份，会多丧失一点效率；
	
	使用举例：
	因为该类实现了系统标准的SharedPreferences接口，只要将“context.getSharedPreferences(name, mode)”改成“MultiprocessSharedPreferences.getSharedPreferences(context, name, mode)”即可；
	另外mode的{@link Context#MODE_MULTI_PROCESS}状态已经不需要再使用了，因为默认已经支持；

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
