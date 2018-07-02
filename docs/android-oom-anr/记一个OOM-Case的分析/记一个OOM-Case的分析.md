> 这篇文章记录一个OOM的case, 在LeanbackLauncher里不停的切换语言会导致OOM

转载请标明来处:  http://www.jianshu.com/p/1c324e766689

LeanbackLauncher切换语言会触发 updateLocale
它的具体调用链
```
updateLocale
  updateLocales()
    updatePersistentConfiguration
      updateConfigurationLocked
        ensureActivityConfigurationLocked
          relaunchActivityLocked()
```

relaunchActivityLocked会destroy LeanbackLauncher.MainActivity, 因此可以猜测Activity没有被GC掉。

# MAT分析hprof文件
通过MAT分析dump出来的hprof文件，发现 `NvAccStClient` 有多个实例，如下所示

```
Class Name                                                                                        | Ref. Objects | Shallow Heap | Ref. Shallow Heap | Retained Heap
--------------------------------------------------------------------------------------------------------------------------------------------------------------------
                                                                                                  |              |              |                   |              
class com.nvidia.shieldtech.NvHookHelper @ 0x7290d020 System Class                                |           13 |           24 |             4,368 |           728
'- mContext com.google.android.leanbacklauncher.LauncherApplication @ 0x12c3ce40                  |           13 |           32 |             4,368 |            80
   '- mLoadedApk android.app.LoadedApk @ 0x12c16900                                               |           13 |          112 |             4,368 |           880
      '- mServices android.util.ArrayMap @ 0x12c3c220                                             |           13 |           32 |             4,368 |           392
         '- mArray java.lang.Object[8] @ 0x12cfb2e0                                               |           13 |           48 |             4,368 |           328
            '- [1] android.util.ArrayMap @ 0x12cd3be0                                             |           13 |           32 |             4,368 |           280
               '- mArray java.lang.Object[36] @ 0x12e0fba0                                        |           13 |          160 |             4,368 |           160
                  |- [20] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12d4f2c0          |            1 |           16 |               336 |            16
                  |- [24] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12e210a0          |            1 |           16 |               336 |            16
                  |- [2] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12e595c0           |            1 |           16 |               336 |            16
                  |- [6] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12e5e8a0           |            1 |           16 |               336 |            16
                  |- [12] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12c50710          |            1 |           16 |               336 |            16
                  |- [16] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12fb44c0          |            1 |           16 |               336 |            16
                  |- [8] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12ce7290           |            1 |           16 |               336 |            16
                  |- [22] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12fe5940          |            1 |           16 |               336 |            16
                  |- [14] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12f32ef0          |            1 |           16 |               336 |            16
                  |- [10] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12c342c0          |            1 |           16 |               336 |            16
                  |- [4] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12e51ea0           |            1 |           16 |               336 |            16
                  |- [0] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x130b8370           |            1 |           16 |               336 |            16
                  |- [18] com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient$1 @ 0x12fe5550          |            1 |           16 |               336 |            16
                  |  '- this$1 com.nvidia.NvAccSt.NvAccStCapture$NvAccStClient @ 0x131027e0       |            1 |           32 |               336 |            88
                  |     '- this$0 com.nvidia.NvAccSt.NvAccStCapture @ 0x131043f8                  |            1 |           40 |               336 |           160
                  |        '- mContext com.android.internal.policy.DecorContext @ 0x12d676d0      |            1 |           48 |               336 |            48
                  |           '- mPhoneWindow com.android.internal.policy.PhoneWindow @ 0x1300fbb0|            1 |          360 |               336 |        15,192
                  '- Total: 13 entries                                                            |              |              |                   |          
```

可以看出应该是对NvAccStClient的引用导致 LeanbackLauncher.MainActivity 没有被GC.

# 原因分析
通过 openGrok 快速查看 NvAccStClient 的调用关系发现NvAccStClient 是定义在NvAccStCapture里的私有变量。

**NvAccStCapture.java**

```
private NvAccStClient mNvAccStClient = new NvAccStClient();
				  
public NvAccStCapture(Context context) {
    mContext = context;
    mNvAccStClient.connect();
}
```
**NvAccStClient.java**
```
boolean connect() {
    boolean ret = false;
    Intent intent = new Intent(
                "com.nvidia.NvAccSt.START_SERVICE");
    intent.setClassName("com.nvidia.NvAccSt",
                        "com.nvidia.NvAccSt.NvAccStService");
    try {
        ret = mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE, UserHandle.CURRENT_OR_SELF);
    } catch (SecurityException e) {
        e.printStackTrace();
    }
    return ret;
}
```
而 NvAccStCapture 是在ViewRootImpl里初始化的，每一个Activity都对应一个ViewRootImpl, 因此如果NvAccSt被引用了就会导致ViewRootImpl不会被GC，从而导致Activity不能被GC,

```
public ViewRootImpl(Context context, Display display) {
    mContext = context;
    if (NvAccStCapture.isEnabled(mContext)) {
        mNvAccStCapture = new NvAccStCapture(mContext);
    } else {
        mNvAccStCapture = null;
    }
}		
```

那么问题来了，NvAccSt是怎么被引用的呢？

回到 NvAccStClient里的 connect函数
```
boolean connect() {
    boolean ret = false;
    Intent intent = new Intent(
                "com.nvidia.NvAccSt.START_SERVICE");
    intent.setClassName("com.nvidia.NvAccSt",
                        "com.nvidia.NvAccSt.NvAccStService");
    try {
        ret = mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE, UserHandle.CURRENT_OR_SELF);
    } catch (SecurityException e) {
        e.printStackTrace();
    }
    return ret;
}
```
如果 `com.nvidia.NvAccSt` 这个apk不存在，那么bindServiceAsUser就会 fail,
接着看下  bindServiceAsUser
```
    /** @hide */
    @Override
    public boolean bindServiceAsUser(Intent service, ServiceConnection conn, int flags,
            UserHandle user) {
        return bindServiceCommon(service, conn, flags, mMainThread.getHandler(), user);
    }


    private boolean bindServiceCommon(Intent service, ServiceConnection conn, int flags, Handler
            handler, UserHandle user) {
        IServiceConnection sd;
        if (conn == null) {
            throw new IllegalArgumentException("connection is null");
        }
        if (mPackageInfo != null) {
            sd = mPackageInfo.getServiceDispatcher(conn, getOuterContext(), handler, flags);
        } else {
            throw new RuntimeException("Not supported in system context");
        }
        //因为apk都不存在，那么就会返回fail
        validateServiceIntent(service);
        try {
            IBinder token = getActivityToken();
            if (token == null && (flags&BIND_AUTO_CREATE) == 0 && mPackageInfo != null
                    && mPackageInfo.getApplicationInfo().targetSdkVersion
                    < android.os.Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                flags |= BIND_WAIVE_PRIORITY;
            }
            service.prepareToLeaveProcess(this);
            int res = ActivityManagerNative.getDefault().bindService(
                mMainThread.getApplicationThread(), getActivityToken(), service,
                service.resolveTypeIfNeeded(getContentResolver()),
                sd, flags, getOpPackageName(), user.getIdentifier());
            if (res < 0) {
                throw new SecurityException(
                        "Not allowed to bind to service " + service);
            }
            return res != 0;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
```
接着看getServiceDispatcher
```
    public final IServiceConnection getServiceDispatcher(ServiceConnection c,
            Context context, Handler handler, int flags) {
        synchronized (mServices) {
            LoadedApk.ServiceDispatcher sd = null;
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> map = mServices.get(context);
            if (map != null) {
                sd = map.get(c);
            }
            if (sd == null) {
                sd = new ServiceDispatcher(c, context, handler, flags);
                if (map == null) {
                    map = new ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher>();
                    mServices.put(context, map);
                }
                map.put(c, sd);
            } else {
                sd.validate(context, handler);
            }
            return sd.getIServiceConnection();
        }
    }
```
发现 getServiceDispatcher 不管要绑定的service是否存在，直接生成一个ServiceDispatcher， 然后保存到mServices里.

**特别注意, mContext是global Application context, 它与Activity是不一样的，一个APK只有一个 Application Context**

这样的引用链如下
```
Activity -> Décor view -> ViewRootImpl -> mContext -> mLoadedApk -> mServices -> mArrayMap (holding mConnection)
```
即生成每个Activity时，都会向Global Application Context加入ServiceConnection, 这样每个 Activity都有被Application Context所hold住的引用，而Application Context的生命周期最长，这样当Activity就不会被GC，多打开几次Activity，就会导到OOM了。

解决方案，当只要 bindServiceAsUser fail了也要unbind一次。


# 问题衍生
可以试下，如果在一般的Activity里去bindService, 即使bind失败了，不去unbind，也不导致OOM，为什么呢？

因为在Activity里bindService，它对应的ServiceConnection是保存在对应的Activity的mContext里的，而不是Global Application Context里，这里是有本质的区别的。

因为Activity是destroy的时候会自己unbindService
```
handleDestroyActivity
  scheduleFinalCleanup
    performFinalCleanup
      removeContextRegistrations (LoadedApk)
```     

```
    public void removeContextRegistrations(Context context,
            String who, String what) {
        final boolean reportRegistrationLeaks = StrictMode.vmRegistrationLeaksEnabled();
        synchronized (mReceivers) {
            ArrayMap<BroadcastReceiver, LoadedApk.ReceiverDispatcher> rmap =
                    mReceivers.remove(context);
            if (rmap != null) {
                for (int i = 0; i < rmap.size(); i++) {
                    LoadedApk.ReceiverDispatcher rd = rmap.valueAt(i);
                    IntentReceiverLeaked leak = new IntentReceiverLeaked(
                            what + " " + who + " has leaked IntentReceiver "
                            + rd.getIntentReceiver() + " that was " +
                            "originally registered here. Are you missing a " +
                            "call to unregisterReceiver()?");
                    leak.setStackTrace(rd.getLocation().getStackTrace());
                    Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                    if (reportRegistrationLeaks) {
                        StrictMode.onIntentReceiverLeaked(leak);
                    }
                    try {
                        ActivityManagerNative.getDefault().unregisterReceiver(
                                rd.getIIntentReceiver());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                }
            }
            mUnregisteredReceivers.remove(context);
        }

        synchronized (mServices) {
            //Slog.i(TAG, "Receiver registrations: " + mReceivers);
            ArrayMap<ServiceConnection, LoadedApk.ServiceDispatcher> smap =
                    mServices.remove(context);
            if (smap != null) {
                for (int i = 0; i < smap.size(); i++) {
                    LoadedApk.ServiceDispatcher sd = smap.valueAt(i);
                    ServiceConnectionLeaked leak = new ServiceConnectionLeaked(
                            what + " " + who + " has leaked ServiceConnection "
                            + sd.getServiceConnection() + " that was originally bound here");
                    leak.setStackTrace(sd.getLocation().getStackTrace());
                    Slog.e(ActivityThread.TAG, leak.getMessage(), leak);
                    if (reportRegistrationLeaks) {
                        StrictMode.onServiceConnectionLeaked(leak);
                    }
                    try {
                        ActivityManagerNative.getDefault().unbindService(
                                sd.getIServiceConnection());
                    } catch (RemoteException e) {
                        throw e.rethrowFromSystemServer();
                    }
                    sd.doForget();
                }
            }
            mUnboundServices.remove(context);
            //Slog.i(TAG, "Service registrations: " + mServices);
        }
    }
```
从 removeContextRegistrations 里可以看出，在destroy一个activity的最后，会unbindService, unregisterReceiver, 防止内存泄露。

# 参考
- [bindService() returns false but unbindService() needs to be called?](http://baxincc.cc/questions/703668/bindservice-returns-false-but-unbindservice-needs-to-be-called)
- [Binding to a service](https://developer.android.com/guide/components/bound-services.html#Binding)
- [[bindService() returns false but unbindService() needs to be called?](https://stackoverflow.com/questions/14255338/bindservice-returns-false-but-unbindservice-needs-to-be-called)](http://stackoverflow.com/questions/14255338/bindservice-returns-false-but-unbindservice-needs-to-be-called)
