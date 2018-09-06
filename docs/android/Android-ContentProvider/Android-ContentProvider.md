> 本文基于 AOSP android-9.0.0_r2

本文依然从Provider的解析，Provider的安装，以及Provider的调用流程来分析Provider相关代码。

# 一、Provider的解析
Provider也是Android的四大组件之一，如果开发者要使用它，需继承ContentProvider， 然后自己实现里面的query/update/delete/insert ...等等相关方法

Provider由PMS中的PackageParser的parseProvider 解析

![图1 Provider的解析](https://upload-images.jianshu.io/upload_images/5688445-eab11e36d64942f4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


# 二、Provider的安装
之前在[将system_server进程配置成Android Application进程](https://www.jianshu.com/p/4378ebb1847f)笔记中已经提到过了安装系统进程的Provider, 而一般的Application的安装流程也基本一样。

## 2.1 Provider的安装流程

![图2 Provider的安装流程](https://upload-images.jianshu.io/upload_images/5688445-632d29d17fb920cc.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

下面来看下generateApplicationProvidersLocked

```
    private final List<ProviderInfo> generateApplicationProvidersLocked(ProcessRecord app) {
        List<ProviderInfo> providers = null;
        try {
            providers = AppGlobals.getPackageManager()
                    .queryContentProviders(app.processName, app.uid,
                            STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS
                                    | MATCH_DEBUG_TRIAGED_MISSING, /*metadastaKey=*/ null)
                    .getList();
        } catch (RemoteException ex) {
        }
        int userId = app.userId;
        if (providers != null) {
            int N = providers.size();
            app.pubProviders.ensureCapacity(N + app.pubProviders.size());
            for (int i=0; i<N; i++) {
                ProviderInfo cpi = (ProviderInfo)providers.get(i);
                boolean singleton = isSingleton(cpi.processName, cpi.applicationInfo,cpi.name, cpi.flags);
                if (singleton && UserHandle.getUserId(app.uid) != UserHandle.USER_SYSTEM) {
                    providers.remove(i);
                    N--;
                    i--;
                    continue;
                }

                ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
                ContentProviderRecord cpr = mProviderMap.getProviderByClass(comp, userId);
                if (cpr == null) {
                    cpr = new ContentProviderRecord(this, cpi, app.info, comp, singleton);
                    mProviderMap.putProviderByClass(comp, cpr);
                }
                app.pubProviders.put(cpi.name, cpr);
                if (!cpi.multiprocess || !"android".equals(cpi.packageName)) {
                    app.addPackage(cpi.applicationInfo.packageName, cpi.applicationInfo.versionCode,mProcessStats);
                }
                notifyPackageUse(cpi.applicationInfo.packageName,PackageManager.NOTIFY_PACKAGE_USE_CONTENT_PROVIDER);
            }
        }
        return providers;
    }
```
![图3 ContentProviderRecord](https://upload-images.jianshu.io/upload_images/5688445-9236fad11d9ecd18.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

generateApplicationProvidersLocked的主要的目的就是生成图3的数据结构，将ProviderInfo从PMS查询出来，生成 ContentProviderRecord 然后保存在 AMS 的ProviderMap中

## 2.2 Provider的安装
如图2所示，ActivityManagerService 将App的信息，包括app中Provider的信息通过bindApplication函数bind到App所在的进程时发生的，这个时间点基本算的上是应用程序的最早的阶段，它都比Application的onCreate还在前面。更不用说其它三大组件都早。

installContentProvider到底发生了什么呢？
```
    private void installContentProviders(Context context, List<ProviderInfo> providers) {
        final ArrayList<ContentProviderHolder> results = new ArrayList<>();
        for (ProviderInfo cpi : providers) {
            ContentProviderHolder cph = installProvider(context, null, cpi,false , true , true);
            if (cph != null) {
                cph.noReleaseNeeded = true;
                results.add(cph);
            }
        }

        try {
            ActivityManager.getService().publishContentProviders(getApplicationThread(), results);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }
```
从代码上看, installContentProviders遍历所有的Provider, 然后分别安装，最后将这些安装好的Provider publish到AMS中。

installProvider分为两部分，第一部分是实例化ContentProvider, 另外一部分是将ContentProvider加入到各种链表当中去。

- **实例化ContentProvider**
```
    private ContentProviderHolder installProvider(Context context,
            ContentProviderHolder holder, ProviderInfo info,
            boolean noisy, boolean noReleaseNeeded, boolean stable) {
        ContentProvider localProvider = null;
        IContentProvider provider;
        if (holder == null || holder.provider == null) {
            ...
            try {
                ...
                localProvider = packageInfo.getAppFactory().instantiateProvider(cl, info.name);
                provider = localProvider.getIContentProvider();
                localProvider.attachInfo(c, info);
            } 
        } else {
            provider = holder.provider;
        }
```
instantiateProvider函数通过反射机制生成Provider的实例， 然后通过 attachInfo 将ProviderInfo保存到ContentProvider实例当中， 最后调用 ContentProvider的onCreate
```
    private void attachInfo(Context context, ProviderInfo info, boolean testing) {
        mNoPerms = testing;
        if (mContext == null) {
            mContext = context;
            if (context != null) {
                mTransport.mAppOpsManager = (AppOpsManager) context.getSystemService(
                        Context.APP_OPS_SERVICE);
            }
            mMyUid = Process.myUid();
            if (info != null) {
                setReadPermission(info.readPermission);
                setWritePermission(info.writePermission);
                setPathPermissions(info.pathPermissions);
                mExported = info.exported;
                mSingleUser = (info.flags & ProviderInfo.FLAG_SINGLE_USER) != 0;
                setAuthorities(info.authority);
            }
            ContentProvider.this.onCreate();
        }
    }
```

- **将ContentProvider加入到各种数据结构当中**
```
        ContentProviderHolder retHolder;
        synchronized (mProviderMap) {
            IBinder jBinder = provider.asBinder();
            if (localProvider != null) {
                ComponentName cname = new ComponentName(info.packageName, info.name);
                ProviderClientRecord pr = mLocalProvidersByName.get(cname);
                if (pr != null) {
                    provider = pr.mProvider;
                } else {
                    holder = new ContentProviderHolder(info);
                    holder.provider = provider;
                    holder.noReleaseNeeded = true;
                    pr = installProviderAuthoritiesLocked(provider, localProvider, holder);
                    mLocalProviders.put(jBinder, pr);
                    mLocalProvidersByName.put(cname, pr);
                }
                retHolder = pr.mHolder;
            } else {
              ...
            }
        }
        return retHolder;
    }
```
![图4 Provider的数据结构](https://upload-images.jianshu.io/upload_images/5688445-0171a78dab2fb503.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 2.3 向System publish Provider
Application 将ContentProvider  安装好后，最后通过publishContentProviders往AMS中publish,
```
    public final void publishContentProviders(IApplicationThread caller, List<ContentProviderHolder> providers) {
        synchronized (this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            final long origId = Binder.clearCallingIdentity();
            final int N = providers.size();
            for (int i = 0; i < N; i++) {
                ContentProviderHolder src = providers.get(i);
                ...
               ContentProviderRecord dst = r.pubProviders.get(src.info.name);
                if (dst != null) {
                    ComponentName comp = new ComponentName(dst.info.packageName, dst.info.name);
                    mProviderMap.putProviderByClass(comp, dst);
                    String names[] = dst.info.authority.split(";");
                    for (int j = 0; j < names.length; j++) {
                        mProviderMap.putProviderByName(names[j], dst);
                    }
                    ...
                    synchronized (dst) {
                        dst.provider = src.provider;
                        dst.proc = r;
                        dst.notifyAll();
                    }
                    updateOomAdjLocked(r, true);
                }
            }
        }
    }
```

![图5 publish provider](https://upload-images.jianshu.io/upload_images/5688445-408d018c6423b12a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


至此, Application已经将ContentProvider安装好，且已经向AMS publish了Application所提供的 ContentProvider了， 下面介绍下其它APP访问别的APP里的 Provider的流程。

# 三、Provider的访问
如图4所示，ContentProvider里的Transport间接实现了IContentProvider, IContentPrvoider定义了如下的接口 query/insert/update/delete/getType/call 等Transport最终会将这些接口调用route到自己实现的MyProvider里。

这里以call为例来看下Provider是怎么样跨进程通信的。

Client App通过以下代码去访问图4所示的Provider.
```
            Uri uri = Uri.parse("content://bar");
            getContentResolver().call(uri, "from_test2", "arg", null);
```

先来看下getContentResolver
```
    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    mContentResolver = new ApplicationContentResolver(this, mainThread);
```
来看下ContentResolver的call的函数
```
    public final @Nullable Bundle call(@NonNull Uri uri, @NonNull String method,
            @Nullable String arg, @Nullable Bundle extras) {
        IContentProvider provider = acquireProvider(uri);
        try {
            final Bundle res = provider.call(mPackageName, method, arg, extras);
            Bundle.setDefusable(res, true);
            return res;
        } catch (RemoteException e) {
            return null;
        } finally {
            releaseProvider(provider);
        }
    }
```
call函数会先通过acquireProvider去获得Provider的Proxy代理，然后通过Provider的代理去请求MyProvider的call函数。 

![图6 call请求流程](https://upload-images.jianshu.io/upload_images/5688445-d3accbceb78304b2.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

如图6中 4所示 acquireProvider会先从本地已经安装好的Provider来查请求的Provider是否已经安装，如果已经安装，直接返回，这样就省去向AMS查询获得

相反，如果本地并没有安装，此时就要通过binder向AMS查询获得。

## 3.1 AMS返回ContentProvider proxy
```
    private ContentProviderHolder getContentProviderImpl(IApplicationThread caller,
            String name, IBinder token, boolean stable, int userId) {
        ...
        synchronized(this) {
            cpr = mProviderMap.getProviderByName(name, userId);
            ...
            //Phase1 Provider是否已经安装好，且可运行了，直接返回
            boolean providerRunning = cpr != null && cpr.proc != null && !cpr.proc.killed;
            if (providerRunning) {
                cpi = cpr.info;
                if (r != null && cpr.canRunHere(r)) {
                    holder.provider = null;
                    return holder;
                }
            }

            if (!providerRunning) {
                ...
                ComponentName comp = new ComponentName(cpi.packageName, cpi.name);
                cpr = mProviderMap.getProviderByClass(comp, userId);
                final boolean firstClass = cpr == null;

                //Phase2  如果Provider还没有运行，说明对应的进程还没有启动，此时将Provider所在的进程
                //先启动起来
                if (i >= N) {
                    try {
                        // Use existing process if already started
                        ProcessRecord proc = getProcessRecordLocked(
                                cpi.processName, cpr.appInfo.uid, false);
                        if (proc != null && proc.thread != null && !proc.killed) {
                            ...
                        } else {
                            proc = startProcessLocked(cpi.processName,
                                    cpr.appInfo, false, 0, "content provider",
                                    new ComponentName(cpi.applicationInfo.packageName,
                                            cpi.name), false, false, false);
                        }
                        cpr.launchingApp = proc;
                        mLaunchingProviders.add(cpr);
                    } finally {
                        Binder.restoreCallingIdentity(origId);
                    }
                }

                // Make sure the provider is published (the same provider class
                // may be published under multiple names).
                if (firstClass) {
                    mProviderMap.putProviderByClass(comp, cpr);
                }
                
                mProviderMap.putProviderByName(name, cpr);
                conn = incProviderCountLocked(r, cpr, token, stable);
                if (conn != null) {
                    conn.waiting = true;
                }
            }
        }

        //如果Provider并没有被publish, 此时要等着Provider publish
        synchronized (cpr) {
            while (cpr.provider == null) {
                if (cpr.launchingApp == null) {
                    return null;
                }
                try {
                    if (conn != null) {
                        conn.waiting = true;
                    }
                    cpr.wait();
                } catch (InterruptedException ex) {
                } finally {
                    if (conn != null) {
                        conn.waiting = false;
                    }
                }
            }
        }
        return cpr != null ? cpr.neowHolder(conn) : null;
    }
```
getContentProviderImpl做完减法后的代码如上，其实归根结底，如果Provider已经publish, 则直接返回，如果还没有publish, 那就先启动进程，等着provider的publish.  最后将ContentProviderHolder返回给Client App.

![图7 Client App获得Provider代理](https://upload-images.jianshu.io/upload_images/5688445-9b5364e0117574ef.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

注意: Client App安装的Provider, 有一个引用计数 ProviderRefCount, 当引用计数为0时，会释放掉已经安装的Provider, 这个引用计数的初始化值取决于installProvider. 这个不细说了。

# 四 Provider ANR

## 4.1 AMS设置的超时
回到 attachApplicationLocked， 当AMS将Provider信息bind给Application时，会设置CONTENT_PROVIDER_PUBLISH_TIMEOUT(10s)的超时。

```
   private final boolean attachApplicationLocked(IApplicationThread thread,
            int pid, int callingUid, long startSeq) {
        ...
        if (providers != null && checkAppInLaunchingProvidersLocked(app)) {
            Message msg = mHandler.obtainMessage(CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG);
            msg.obj = app;
            mHandler.sendMessageDelayed(msg, CONTENT_PROVIDER_PUBLISH_TIMEOUT);
        }
       ...
       thread.bindApplication(...)
```

当Application在Timeout之前publish成功， 会将Timeout message remove掉。
```
    public final void publishContentProviders(IApplicationThread caller,
            List<ContentProviderHolder> providers) {
              final int N = providers.size();
            for (int i = 0; i < N; i++) {
                    ...
                    if (wasInLaunchingProviders) {
                        mHandler.removeMessages(CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG, r);
                    }
                    ...
        }
    }
```

如果在Timeout之前还没有publish, 会发生什么呢？
```
            case CONTENT_PROVIDER_PUBLISH_TIMEOUT_MSG: {
                ProcessRecord app = (ProcessRecord)msg.obj;
                synchronized (ActivityManagerService.this) {
                    processContentProviderPublishTimedOutLocked(app);
                }
            }
```
```
    private final void processContentProviderPublishTimedOutLocked(ProcessRecord app) {
        cleanupAppInLaunchingProvidersLocked(app, true); // 清除信息
        removeProcessLocked(app, false, true, "timeout publishing content providers"); //kill app，可能会重启
    }
```
如果超时了，AMS会kill掉已经启动的App, 然后根据需要决定是否重启App.

## 4.2 ContentProviderClient
通过下面的代码
```
getContentResolver().acquireContentProviderClient("bar").call();
```
可以使用到ContentProviderClient, ContentProviderClient是Provider的Wrapper,  ContentProviderClient有一个**hide**的方法来enable ANR
```
    /** {@hide} */
    public void setDetectNotResponding(long timeoutMillis) {
        synchronized (ContentProviderClient.class) {
            mAnrTimeout = timeoutMillis;

            if (timeoutMillis > 0) {
                if (mAnrRunnable == null) {
                    mAnrRunnable = new NotRespondingRunnable();
                }
                if (sAnrHandler == null) {
                    sAnrHandler = new Handler(Looper.getMainLooper(), null, true /* async */);
                }
            } else {
                mAnrRunnable = null;
            }
        }
    }
```
当调用该函数设置的Timeout时间 **>0** 时， ANR 机制就启动了

```
    public @Nullable Bundle call(@NonNull String method, @Nullable String arg,
            @Nullable Bundle extras) throws RemoteException {
        beforeRemote();
        try {
            return mContentProvider.call(mPackageName, method, arg, extras);
        } catch (DeadObjectException e) {
            if (!mStable) {
                mContentResolver.unstableProviderDied(mContentProvider);
            }
            throw e;
        } finally {
            afterRemote();
        }
    }
```
ContentProviderClient在具体的IContentProvider定义的函数之前与之后加入了 beforeRemote/afterRemote
```
    private void beforeRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.postDelayed(mAnrRunnable, mAnrTimeout);
        }
    }

    private void afterRemote() {
        if (mAnrRunnable != null) {
            sAnrHandler.removeCallbacks(mAnrRunnable);
        }
    }
```
这两个函数是Enable ANR超时，如果在设定的TIMEOUT时间内IContentProvider中的方法还没有返回，此时便触发ANR.

```
    private class NotRespondingRunnable implements Runnable {
        @Override
        public void run() {
            mContentResolver.appNotRespondingViaProvider(mContentProvider);
        }
    }
```
最终会触发AMS调用appNotRespondingViaProvider
```
    public void appNotRespondingViaProvider(IBinder connection) {
        final ContentProviderConnection conn = (ContentProviderConnection) connection;
        final ProcessRecord host = conn.provider.proc;
        if (host == null) {
            return;
        }

        mHandler.post(new Runnable() {
            @Override
            public void run() {
                mAppErrors.appNotResponding(host, null, null, false, "ContentProvider not responding");
            }
        });
    }
```
这里特别需要注意的是这个ANR针对的 Provider的提供方，并不是对于 Client 这端。

# 五 小结
- Provider的authority在整个Android系统中是唯一的，且支持多个authority, 每个authority由 **";"** 隔开，
- Provider提供者在Application onCreate之前已经完全初始化，
- Provider使用者通过安装Provider的代理在本地，然后通过本地代理获得 Provider的相关服务。
- Provider在publish阶段有个10ms的timeout, 如果timeout时间内没有完成，AMS会kill掉该App
- ContentProviderClient加入了ANR机制，可以使用AMS对于Provider发出ANR







