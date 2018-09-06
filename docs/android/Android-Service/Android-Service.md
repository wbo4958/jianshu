> 本文基于 AOSP android-9.0.0_r2

Android Service是Android四大组件之一，它主要用来执行一些不与用户交互的long-run的操作. 注意Service如非特意指定，它仅是运行于该进程的一部分代码而已，另外Service并不是运行在单独线程中，而是主线程中。所以尽量要避免一些ANR的操作。

# 一、Service的声明
Service是Android中的四大组件，使用它一定要在AndroidManifest.xml中声明，在AndroidManifest.xml中声明是为了让PackageManagerService能解析出该Service, 并建立对应的数据结构。如下图所示, 

![图1 service在pkms中的数据类型](https://upload-images.jianshu.io/upload_images/5688445-8eaf911f66940c3b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

如图中所示，Service也可以定义IntentFilter.

Service分为如下三类
- foreground service
fg Service执行一些对于用户来说是可感知的操作，如audio应用使用fg service来播放歌曲。
- background service
bg service执行的操作对用户而言是不可感知的。
- bound service
bound service主要是提供c/s接口，允许组件与service进行通信，或者是跨进程的通信。

其实说到底，由于启动方式的不同导致了三种service,

startService                      -> background service.
startForegroundService   -> foreground service 
bindService                      -> bound service

# 二、foreground和background service
对于fg和bg service,它们的启动方式不同，分别是startForegroundService和startService  
```
    @Override
    public ComponentName startService(Intent service) {
        return startServiceCommon(service, false, mUser);
    }

    @Override
    public ComponentName startForegroundService(Intent service) {
        return startServiceCommon(service, true, mUser);
    }

    private ComponentName startServiceCommon(Intent service, boolean requireForeground,
            UserHandle user) {
    }
```
从启动方式可以看出，它们的仅仅在于 requireForeground，即一个boolean形的标志位决定是bg还是fg service.

## 2.1 bg/fg启动流程

![图2 flow_startService](https://upload-images.jianshu.io/upload_images/5688445-3c6b712c2dade15d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

其中 retriveServiceLocked 函数，主要去建立如下的关系图
![图3retriveServiceLocked](https://upload-images.jianshu.io/upload_images/5688445-e18a47e5e21619ee.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
从图中可以看出，可以通过IntentFilter与ComponentName 两种方式去指定一个service.

第一次启动 service的生命周期 onCreate(scheduleCreateService) -> onStartCommand(AMS 调用scheduleServiceArgs)

多个地方(如Activity)可以多次调用startService, 如果之前已经打开，直接进入onStartCommand就行了
注意: **需要手动调用 stopService去停止Service**

---

**而对于IntentService.**
IntentService继承于Service, 它的实现相当于在Service的基础上增加了一个HandlerThread, 以及自定义的Handler, IntentService将所有的业务 route 到**HandlerThread线程**中去处理(onHandleIntent), 当onHandleIntent处理完后，就会调用**stopSelf**来停止到这个Service,
所以**每次启动**一个IntentService, 都是经过这样的生命周期
`onCreate -> onStartCommand -> onStart -> onHandleIntent -> onDestroy`,
 其中`onStart/onStartCommand`都将Intent route到了onHandleIntent中去处理


## 2.2 bg service的限制
Android O开始对background的service做了限制, 具体可以参考https://developer.android.com/about/versions/oreo/background#services, 
```
         final boolean bgLaunch = !mAm.isUidActiveLocked(r.appInfo.uid);

        boolean forcedStandby = false;
        if (bgLaunch && appRestrictedAnyInBackground(r.appInfo.uid, r.packageName)) {
            forcedStandby = true;
        }
```
bgLaunch是检查startService是否是background启动,什么意思呢？如果要启动的Service的UID并没有running的进程的话，它就属于background launch
比如， MyService在testA.apk里，**testA.apk并没有启动**
此时通过
```
adb shell am start-service -n xxxx/xxx.MyService
```
就属于background的launch,
或者在testB.apk里通过startService去启动MyService也属于background的启动。

而appRestrictedAnyInBackground检查是否可以在background状态时运行一些任务，这里一般都不允许，所以forcedStandby一般都为false.

forcedStandy是一个boolean型的变量，如果它为true的话，会强制进入启动模式的检查。 



```
        // If this is a direct-to-foreground start, make sure it is allowed as per the app op.
        boolean forceSilentAbort = false;
        if (fgRequired) {
            final int mode = mAm.mAppOpsService.checkOperation(
                    AppOpsManager.OP_START_FOREGROUND, r.appInfo.uid, r.packageName);
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED:
                case AppOpsManager.MODE_DEFAULT:
                    // All okay.
                    break;
                case AppOpsManager.MODE_IGNORED:
                    fgRequired = false;
                    forceSilentAbort = true;
                    break;
                default:
                    return new ComponentName("!!", "foreground not allowed as per app op");
            }
        }
```
上面的代码在foreground的检查，如果指明要将service运行于foreground, 那检查是否允许。

```
        if (forcedStandby || (!r.startRequested && !fgRequired)) {
            final int allowed = mAm.getAppStartModeLocked(r.appInfo.uid, r.packageName,
                    r.appInfo.targetSdkVersion, callingPid, false, false, forcedStandby);
            if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                if (allowed == ActivityManager.APP_START_MODE_DELAYED || forceSilentAbort) {
                    return null;
                }
                if (forcedStandby) {
                    if (fgRequired) {
                              return null;
                    }
                }
                UidRecord uidRec = mAm.mActiveUids.get(r.appInfo.uid);
                return new ComponentName("?", "app is in background uid " + uidRec);
            }
        }
```
由前面可知，foredStandby一般为false,  而 r.startRequested在没有启动service时，它也为false. 此时取决于是否是foreground service的请求，如果是fg请求的话。则不会进入启动模式的检查，反之，就会进入检查。 getAppStartModeLocked 在之前Broadcast里有讲，现在在看下对于Service是一种什么情况。

```
    int getAppStartModeLocked(int uid, String packageName, int packageTargetSdk,
            int callingPid, boolean alwaysRestrict, boolean disabledOnly, boolean forcedStandby) {
        UidRecord uidRec = mActiveUids.get(uid);
        if (uidRec == null || alwaysRestrict || forcedStandby || uidRec.idle) {
                final int startMode = (alwaysRestrict)
                        ? appRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk)
                        : appServicesRestrictedInBackgroundLocked(uid, packageName,
                                packageTargetSdk);
             ...
      }
```
从前面可知uidRec为空， alwaysRestrict为false, 进而会进入services restricted的检查 
```
  int appServicesRestrictedInBackgroundLocked(int uid, String packageName, int packageTargetSdk) {
        // Persistent app?
        if (mPackageManagerInt.isPackagePersistent(packageName)) {
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // Non-persistent but background whitelisted?
        if (uidOnBackgroundWhitelist(uid)) {
            return ActivityManager.APP_START_MODE_NORMAL;
        }

        // Is this app on the battery whitelist?
        if (isOnDeviceIdleWhitelistLocked(uid, /*allowExceptIdleToo=*/ false)) {
            return ActivityManager.APP_START_MODE_NORMAL;
        }
        return appRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk);
    }
```
如果service是在persistent的apk里，或者在 mBackgroundAppIdWhitelist 里，这个是background白名单(aosp中仅有com.android.defcontainer这个app才在background whitelist中)，或者在baterry白名单中的话，则直接allow, 否则就进入appRestrictedInBackgroundLocked， 而在appRestrictedInBackgroundLocked中，如果是在Android O及以事的版本中，会返回APP_START_MODE_DELAYED_RIGID。也就是不允许。
这就是Android O及以后版本对于background的广播,service的相关的限制。

# 三、bound service
## 3.1 bindService流程
![bindService类图](https://upload-images.jianshu.io/upload_images/5688445-860fd80418ba3f9a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

---

![flow_bindService](https://upload-images.jianshu.io/upload_images/5688445-4cda7d6ef122884f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

生命周期`onCreate->onBind -> onUnbind -> onDestroy`
bindService与当前的Context绑定在一起，如果 Context 销毁了，则Service将会被销毁，执行 onUnbind -> onDestroy

**如果BroadcastReceiver是声明在 AndroidManifest.xml 中的 <receiver>,  则bindService不能在Broadcast里调用, why?**

因为从 <receiver> 里启动(**ActivityThread中的handleReceiver**)的BroadcastReceiver的实例是一个局部变量，理论上onReceive后就没用了，就会被GC掉。
所以如果在这种情况下去bindService，则使用传入的Context后Service将会导致Receiver不能被回收掉，导致内存漏洞。
注: 亲自将ReceiverRestrictedContext里的bindService去掉，然后在BroadcastReceiver里bind service，没有任何异常，除了BroadcastReciver没有被回收。

另外BroadcastReceiver有10s(fg)/60s(bg)的超时。

**注意**，此时传入BroadcastReceiver的Context是ReceiverRestrictedContext, 不是普通的Context,
在ReceiverRestrictedContext中bindService会报异常。
```
    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        throw new ReceiverCallNotAllowedException(
                "BroadcastReceiver components are not allowed to bind to services");
    }
```
而如果是通过在代码中注册 (registerReceiver) , 这时广播不会走 ActivityThread 中的handleReceiver,  所以此时 bindService是可行的，而是走的 LoadedApk.ReceiverDispatcher. 此时在代码中bindService是可以的，因为此时没有ReceiverRestrictedContext去做check了。

## 3.2 unbindService
unbindService并不会触发Server调用 `onServerDisconnected`,  相反，当Server端被杀掉或者crash后就会调用 `onServerDisconnected` 函数通知Client关于Server挂掉了。

如果有多个Client绑定到Server端，并且不是最后一个Client调用unbindService的话，则不会触发Server的onUnbind和onDestroy, 如果是最后一个Client调用unbindService, 则会调用 onUnbind -> onDestroy该Service.

# 四、Service ANR
AMS在通知App去调用Service的生命周期函数时，都会先执行一次bumpServiceExecutingLocked.
![bumpServiceExecutingLocked](https://upload-images.jianshu.io/upload_images/5688445-09c68a9fd526c8e8.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

而该函数最重要的一个功能就是设置timeout时间, 即scheduleServiceTimeoutLocked
```
    void scheduleServiceTimeoutLocked(ProcessRecord proc) {
        if (proc.executingServices.size() == 0 || proc.thread == null) {
            return;
        }
        Message msg = mAm.mHandler.obtainMessage(
                ActivityManagerService.SERVICE_TIMEOUT_MSG);
        msg.obj = proc;
        mAm.mHandler.sendMessageDelayed(msg,
                proc.execServicesFg ? SERVICE_TIMEOUT : SERVICE_BACKGROUND_TIMEOUT);
    }
```
如果service运行在foreground，它的timeout时间是SERVICE_TIMEOUT即20s, 如果它运行在background, 它的timeout时间为SERVICE_BACKGROUND_TIMEOUT为200s.

那如果Service在timeout时间内处理完了对应的操作，ActivityThread会调用serviceDoneExecuting通知AMS, service已经完成处理运动了。
serviceDoneExecuting 会在onCreate/onBind/onStartCommand/onUnbind/onDestroy执行后被调用。

```
serviceDoneExecuting->serviceDoneExecutingLocked->serviceDoneExecutingLocked
mAm.mHandler.removeMessages(ActivityManagerService.SERVICE_TIMEOUT_MSG, r.app);
```

如果service在timeout时间内没有返回，此时调用serviceTimeout
···

    void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;
        ...
        if (anrMessage != null) {
            mAm.mAppErrors.appNotResponding(proc, null, null, false, anrMessage);
        }
    }
···
最终触发 ANR

# 五、小节
Android在8.0后加入了对Service的诸多限制。

Debug技巧
Service.apk,   StartService.apk
- 1. Service.apk不启动
```
Service.apk里调用startService会启动成功。
```
```
  adb shell am start-service -n xxxx/xxx.MyService
Error: app is in background uid
```
```
在StartService.apk中调用startService启动Service.apk
此时直接throw exception, Erro: app is in backround uid ...
```
- 2. Service.apk不启动
```
  adb shell am start-forceground-service -n xxxx/xxx.MyService
启动成功
```
```
StartService.apk中调用 startForegroundService 启动成功
```
- 3. Service.apk已经启动
此时再次启动Service.apk里的Service都会成功。


