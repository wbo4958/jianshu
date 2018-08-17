> 本文基于 AOSP android-9.0.0_r2

Android中的广播机制是通过底层binder来传递Intent的跨进程通信方式。它是`1-N`的机制，即一个广播端可以与N个Receiver对应。

发送端为信息源, 它将一些信息封装到Intent中，然后广播出去。
接收端为信息消费端，它向系统注册广播Receiver(BroadcastReceiver与IntentFilter)，当有对应(IntentFilter)广播发生时，Receiver就会收到广播并开始处理相应的信息。

Android的广播机制涉及如下几个方面

# 一. Receiver
## 1.1 静态注册

通过在AndroidManifest.xml里声明注册的广播称为静态Receiver.
 ```
        <receiver
            android:name=".MyReceiver"
            android:enabled="true"
            android:exported="true">
            <intent-filter>
                <action android:name="me.bobby.test2.MyReceiver"/>
            </intent-filter>
        </receiver>
 ```
如上所示，向系统注册一个MyReceiver的广播Receiver，对应的IntentFilter中的action为`me.bobby.test2.MyReceiver`. 
那么静态注册的Receiver是怎样注册到系统里的呢？
当安装App或系统启动时，PMS会对Apk中的AndroidManifest.xml进行解析。具体的代码在PackageParser.java中的parseBaseApplication函数中
```java
if (tagName.equals("receiver")) {
    Activity a = parseActivity(owner, res, parser, flags, outError, cachedArgs, true, false);
    hasReceiverOrder |= (a.order != 0);
    owner.receivers.add(a);
}
```
![静态Receiver](https://upload-images.jianshu.io/upload_images/5688445-f7329e2e84cf61dc.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 1.2 动态注册
``` java
ContextImpl.registerReceiver(BroadcastReceiver receiver, IntentFilter filter)
```
通过Activity里直接调用registerReceiver去注册一个动态的广播Receiver。
![动态注册广播Receiver](https://upload-images.jianshu.io/upload_images/5688445-296239b731f3c248.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 1.3 小结
从静态广播Receiver与动态广播Receiver可以看出，
静态广播Receiver，并没有**刻意去注册**，只是由PMS在解析Apk时，将申明在AndroidManifest.xml里的广播解析出来，并保存在PMS里仅此而已。
动态广播Receiver，会在Activity的Context中直接调用接口往AMS中注册，可以看出来BroadcastReceiver是直接注册到LoadedApk中的mReceivers中的.

mReceivers的定义
`private final ArrayMap<Context, ArrayMap<BroadcastReceiver, ReceiverDispatcher>> mReceivers`
mReceivers是一个ArrayMap, key为Context, 也就是一个Activity/Service/Provider这样的实例, 它的value又是另一个ArrayMap, 这里的意思是一个Context其实是允许注册多个BroadcastReceiver.

# 二. 发送端

## 2.1 发送广播，找到match的Receiver，并加入到广播队列中
发送广播流程, 主要是在AMS的broadcastIntentLocked中

- **1. 排除stopped的package**
```
        intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
```
- **2. 检查是否是protected的广播**
```
        final boolean isProtectedBroadcast;
        isProtectedBroadcast = AppGlobals.getPackageManager().isProtectedBroadcast(action);
        if (!isCallerSystem) {
            if (isProtectedBroadcast) {
                throw new SecurityException(msg);
            } 
```
从PMS中查找该action是否是protected的广播，如果是**非系统App发出的protected广播**，直接抛出异常。
那什么是protected的广播呢？ 
protected 广播是由系统App在AndroidManifest.xml中定义的，比如 framework-res.apk里面定义了大多数protected broadcast. 
而一般的apk可以申明protected broadcast么？答案是非系统apk可以定义protected广播，但是不会有任何作用，尽管PMS能正常解析, 但是在applyPolicy中发现它非系统apk, 会重置为null.

- **3. sticky的广播**
见后面第三大节。

- **4. 是否针对动态注册的Receiver**
```
        List receivers = null;
        List<BroadcastFilter> registeredReceivers = null;
        // Need to resolve the intent to interested receivers...
        if ((intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY)== 0) {
            receivers = collectReceiverComponents(intent, resolvedType, callingUid, users);
        }
```
如果Intent设置了FLAG_RECEIVER_REGISTERED_ONLY标志，表明该intent只会去找动态注册的Receiver。 否则会查找静态注册和动态注册的两种Receiver。
其中 receivers list中存放的是静态注册的Receiver, collectReceiverComponents通过PMS的queryIntentReceivers去查询静态Receiver，说到底就是从1.1小节所构成的类图中去查找。这个就不细说了。

而registeredReceivers里存放的是动态注册的Receiver，也就是接下来所说的。

- **5. 查找动态注册的Receiver**
```
        if (intent.getComponent() == null) {
            if (userId == UserHandle.USER_ALL && callingUid == SHELL_UID) {
                // Query one target user at a time, excluding shell-restricted users
                for (int i = 0; i < users.length; i++) {
                    ...
                    List<BroadcastFilter> registeredReceiversForUser =
                            mReceiverResolver.queryIntent(intent, resolvedType, false /*defaultOnly*/, users[i]);
                    if (registeredReceivers == null) {
                        registeredReceivers = registeredReceiversForUser;
                    } else if (registeredReceiversForUser != null) {
                        registeredReceivers.addAll(registeredReceiversForUser);
                    }
                }
            } else {
                registeredReceivers = mReceiverResolver.queryIntent(intent,
                        resolvedType, false /*defaultOnly*/, userId);
            }
        }
```
intent.getComponent()如果不为空时，则表明intent意图很明显，就是找某个特定的component, 所以如果它不为空时，则会依次遍历去查找。其实说到底，就是从1.2小节所构成的类图中查询。查询出来的广播receiver放在registeredReceivers中。

- **6. enqueue无序的动态注册的广播**
```
        final boolean replacePending =
                (intent.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;
       int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
        if (!ordered && NR > 0) {
            ...
            final BroadcastQueue queue = broadcastQueueForIntent(intent);
            BroadcastRecord r = new BroadcastRecord(...);
            final boolean replaced = replacePending && (queue.replaceParallelBroadcastLocked(r) != null);
            if (!replaced) {
                queue.enqueueParallelBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
            }
            registeredReceivers = null;
            NR = 0;
        }
```
上面这代码针对的是动态注册的Receiver。
如果设置FLAG_RECEIVER_REPLACE_PENDING，且之前已经有相同的广播，则只需要替换即可，不需要重新入广播队列。 否则就将该广播加入到mParallelBroadcasts中去, 这个是无序广播。

- **7合并动态和静态Receiver**
```
        // Merge into one list.
        int ir = 0;
        if (receivers != null) {
            // this decision.
            String skipPackages[] = null;
             ...
            int NT = receivers != null ? receivers.size() : 0;
            int it = 0;
            ResolveInfo curt = null;
            BroadcastFilter curr = null;
            while (it < NT && ir < NR) {
                if (curt == null) {
                    curt = (ResolveInfo)receivers.get(it);
                }
                if (curr == null) {
                    curr = registeredReceivers.get(ir);
                }
                if (curr.getPriority() >= curt.priority) {
                    // Insert this broadcast record into the final list.
                    receivers.add(it, curr);
                    ir++;
                    curr = null;
                    it++;
                    NT++;
                } else {
                    // Skip to the next ResolveInfo in the final list.
                    it++;
                    curt = null;
                }
            }
        }
        while (ir < NR) {
            if (receivers == null) {
                receivers = new ArrayList();
            }
            receivers.add(registeredReceivers.get(ir));
            ir++;
        }
```
上面的代码根据Receiver的优先级，合并成一个 receivers.
- **8 将最后的广播依次加入到有序广播队列中**
```
       if ((receivers != null && receivers.size() > 0)
                || resultTo != null) {
            BroadcastQueue queue = broadcastQueueForIntent(intent);
            BroadcastRecord r = new BroadcastRecord( ... );
            final BroadcastRecord oldRecord =
                    replacePending ? queue.replaceOrderedBroadcastLocked(r) : null;
            if (oldRecord != null) {
                // 替换了，fire CANCELED
                if (oldRecord.resultTo != null) {
                    final BroadcastQueue oldQueue = broadcastQueueForIntent(oldRecord.intent);
                    try {
                        oldQueue.performReceiveLocked(oldRecord.callerApp, oldRecord.resultTo,
                                oldRecord.intent, Activity.RESULT_CANCELED, null, null,  false, false, oldRecord.userId);
                    } catch (RemoteException e) {
                    }
                }
            } else {
                //加入到有序广播队列中
                queue.enqueueOrderedBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
            }
        } else {
            ...
        }
```


- **9 小结**

![broadcastQueue](https://upload-images.jianshu.io/upload_images/5688445-fb4a83d8694d9265.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
如图所示，
**如果是无序广播。**
那么动态注册的广播receiver全部会加入到 mParallelBroadcasts中，而静态注册的广播receiver会按优先级保存到mOrderedBroadcasts中。
**而如果是有序广播**，
那么静态注册的receiver和动态注册的receiver会根据优先级都放入到mOrderedBroadcasts中。

## 2.2 处理队列
如上面broadcastQueue所示.
广播队列分为两种，
- mFgBroadcastQueue队列
从全名来看，这是一个foreground的队列，它的timeout时间是10s, 不允许delay broadcast
- mBgBroadcastQueue队列
这是一个background的队列，timeout时间是60s,允许delay broadcast

而每个广播队列中又包含有序广播列表和并发广播列表。

广播发送在system的binder线程中，但是真正处理广播的是在ServiceThread线程中。

广播有两种，那如何选择正确的广播队列的呢?
```
    BroadcastQueue broadcastQueueForIntent(Intent intent) {
        final boolean isFg = (intent.getFlags() & Intent.FLAG_RECEIVER_FOREGROUND) != 0;
        return (isFg) ? mFgBroadcastQueue : mBgBroadcastQueue;
    }
```
如果Intent里设置了FLAG_RECEIVER_FOREGROUND，则使用mFgBroadcastQueue, 否则使用mBgBroadcastQueue， 当设置FLAG_RECEIVER_FOREGROUND，则接收端的允许有foreground优先级。

然后根据是无序广播
```
                queue.enqueueParallelBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
```

有序广播
```
                queue.enqueueOrderedBroadcastLocked(r);
                queue.scheduleBroadcastsLocked();
```
进行调度。

binder线程一般会调用 scheduleBroadcastsLocked 向servicethread线程发送BROADCAST_INTENT_MSG， 然后开始进程处理广播的流程。

```
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BROADCAST_INTENT_MSG: {
                    processNextBroadcast(true);
                } break;
                case BROADCAST_TIMEOUT_MSG: {
                    synchronized (mService) {
                        broadcastTimeoutLocked(true);
                    }
                } break;
            }
        }
```
下面来看下processNextBroadcast函数

- **1. mParallelBroadcasts里保存的是无序的广播，此时会将无序的广播依次deliver给广播receiver**
```
        // First, deliver any non-serialized broadcasts right away.
        while (mParallelBroadcasts.size() > 0) {
            r = mParallelBroadcasts.remove(0);
            r.dispatchTime = SystemClock.uptimeMillis(); //记录当前dispatch时间
            r.dispatchClockTime = System.currentTimeMillis();

            final int N = r.receivers.size();
            for (int i=0; i<N; i++) {
                Object target = r.receivers.get(i);
                deliverToRegisteredReceiverLocked(r, (BroadcastFilter)target, false, i);
            }
            addBroadcastToHistoryLocked(r);
        }
```

- **2. 针对 mPendingBroadcast**
检查是否有当前还有Broadcast没有处理完，如果正在处理广播的Receiver还没有处理完，则等着它处理完。
```
        if (mPendingBroadcast != null) {
            boolean isDead;
            if (mPendingBroadcast.curApp.pid > 0) {
                synchronized (mService.mPidsSelfLocked) {
                    ProcessRecord proc = mService.mPidsSelfLocked.get(mPendingBroadcast.curApp.pid);
                    isDead = proc == null || proc.crashing;
                }
            } else {
                final ProcessRecord proc = mService.mProcessNames.get(
                        mPendingBroadcast.curApp.processName, mPendingBroadcast.curApp.uid);
                isDead = proc == null || !proc.pendingStart;
            }
            if (!isDead) {
                // It's still alive, so keep waiting
                return;
            } else {
                mPendingBroadcast.state = BroadcastRecord.IDLE;
                mPendingBroadcast.nextReceiver = mPendingBroadcastRecvIndex;
                mPendingBroadcast = null;
            }
        }
```

- **3. 遍历有序广播, 处理第一个广播**
```
 do {
            if (mOrderedBroadcasts.size() == 0) {
                mService.scheduleAppGcsLocked();
                if (looped) {
                    mService.updateOomAdjLocked();
                }
                //已经没有广播了，直接return
                return;
            }
            r = mOrderedBroadcasts.get(0);
            boolean forceReceive = false;

            //当前广播上挂着多少个广播receiver, 下面检查broadcast是否挂住了。
            int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
            if (mService.mProcessesReady && r.dispatchTime > 0) {
                long now = SystemClock.uptimeMillis();
                if ((numReceivers > 0) &&
                        (now > r.dispatchTime + (2*mTimeoutPeriod*numReceivers))) {
                    //如果broadcast消耗时间大于了2*mTimeoutPeriod*numReceivers,则强制finish.
                    //可以看出超时时间和当前挂在广播上的receiver个数有关。
                    broadcastTimeoutLocked(false); // forcibly finish this broadcast
                    forceReceive = true;
                    r.state = BroadcastRecord.IDLE;
                }
            }

           //如果当前广播状态不是IDLE的话，说明当前正有广播还在处理，直接返回。
            if (r.state != BroadcastRecord.IDLE) {
                return;
            }

            //当前广播已经没有等待接收的receiver了，通过广播发送端，
            // 当前广播被Receiver中止了，后面的Receiver不会再接收到该广播了。
            if (r.receivers == null || r.nextReceiver >= numReceivers
                    || r.resultAbort || forceReceive) {
                // No more receivers for this broadcast!  Send the final
                // result if requested...
                if (r.resultTo != null) {
                    try {
                        performReceiveLocked(r.callerApp, r.resultTo,
                            new Intent(r.intent), r.resultCode,
                            r.resultData, r.resultExtras, false, false, r.userId);
                        r.resultTo = null;
                    } catch (RemoteException e) {
                    }
                }
                ...
            }
        } while (r == null);
```
> 上面代码有一处非常重要， 即r.resultAbort， 如果当前正在处理广播的Receiver调用了abortBroadcast了，则该有序广播就不会再继续发了，直接中断，进入下一个广播处理。

- **4. 处理广播**
经过第3步后，找到了即将要处理的广播
```
        // Get the next receiver...
        int recIdx = r.nextReceiver++;  //当前要处理的广播receiver 索引

        r.receiverTime = SystemClock.uptimeMillis();
        if (recIdx == 0) { //如果处理的是该广播的第一个receiver, 则记录它的dispatch时间。
            r.dispatchTime = r.receiverTime;
            r.dispatchClockTime = System.currentTimeMillis();
        }
```
r.nextReceiver是指下一次要处理的广播receiver, 如上面broadcastQueue所示，每一个广播都会挂着一个list的receiver, 这时，nextReceiver就用来指明下一次处理哪一个。

```
        final BroadcastOptions brOptions = r.options;
        final Object nextReceiver = r.receivers.get(recIdx);

        if (nextReceiver instanceof BroadcastFilter) {
            BroadcastFilter filter = (BroadcastFilter)nextReceiver;
            deliverToRegisteredReceiverLocked(r, filter, r.ordered, recIdx);
            ...
            return;
        }
```
由broadcastQueue图所示，有序广播队列会挂着静态注册和动态注册的广播receiver. 而动态广播receiver, 对应的是BroadcastFilter, 所以这里如果是动态receiver, 则直接deliver给动态receiver. 而没有直接deliver给静态receiver, 说明后面会对静态的receiver有更多的限制

- **5. 静态广播receiver**
```
        // Hard case: need to instantiate the receiver, possibly
        // starting its application process to host it.
        // 由注释可知，下面针对的是静态广播receiver, 有可能会启动静态广播的进程
    ResolveInfo info = (ResolveInfo)nextReceiver;
    ComponentName component = new ComponentName( info.activityInfo.applicationInfo.packageName, info.activityInfo.name);
```
获得静态广播receiver组件信息
然后根据receiver的信息来决定是否将broadcast deliver给该receiver

- **6. receiver权限检查**

代码很长，就不帖了。比如Receiver中的Permission是否granted, 是否exported等等，具体直接看代码吧。

**而下面这个检查比较关键，因为它涉及到在android高版本中虽然注册了静态广播receiver, 但是有广播发生时，Receiver确收不到广播的情况**

```
        if (!skip) {
            final int allowed = mService.getAppStartModeLocked(
                    info.activityInfo.applicationInfo.uid, info.activityInfo.packageName,
                    info.activityInfo.applicationInfo.targetSdkVersion, -1, true, false, false);
            if (allowed != ActivityManager.APP_START_MODE_NORMAL) {
                if (allowed == ActivityManager.APP_START_MODE_DISABLED) {
                    skip = true;
                } else if (((r.intent.getFlags()&Intent.FLAG_RECEIVER_EXCLUDE_BACKGROUND) != 0)
                        || (r.intent.getComponent() == null
                            && r.intent.getPackage() == null
                            && ((r.intent.getFlags()
                                    & Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND) == 0)
                            && !isSignaturePerm(r.requiredPermissions))) {
                    mService.addBackgroundCheckViolationLocked(r.intent.getAction(),
                            component.getPackageName());
                    skip = true;
                }
            }
        }
```
通过getAppStartModeLocked去获得APP START模式，
getAppStartModeLocked会调用如下代码，其中 alwaysRestrict = true
```
                final int startMode = (alwaysRestrict)
                        ? appRestrictedInBackgroundLocked(uid, packageName, packageTargetSdk)
                        : appServicesRestrictedInBackgroundLocked(uid, packageName,
```
在appRestrictedInBackgroundLocked函数中
```
    int appRestrictedInBackgroundLocked(int uid, String packageName, int packageTargetSdk) {
        // Apps that target O+ are always subject to background check
        if (packageTargetSdk >= Build.VERSION_CODES.O) {
            return ActivityManager.APP_START_MODE_DELAYED_RIGID;
        }
    ...
```
> 如果当前Android版本是Oreo及以后，那返回是非APP_START_MODE_DELAYED_RIGID, 
所以会进入else if, 从else if的条件可以看出。

设置FLAG_RECEIVER_EXCLUDE_BACKGROUND, 表明要排除静态receiver.
如果intent没有指定特定的component, package, 以及FLAG_RECEIVER_INCLUDE_BACKGROUND, 以及signature权限相关，也会排除静态receiver.

这也就是在Android O后面，静态广播不会再收到一些系统广播的原因了。

```
        if (skip) {
            r.delivery[recIdx] = BroadcastRecord.DELIVERY_SKIPPED;
            r.receiver = null;
            r.curFilter = null;
            r.state = BroadcastRecord.IDLE;
            r.manifestSkipCount++;
            scheduleBroadcastsLocked();
            return;
        }
```
如果该广播对于这些静态的receiver最后是skip的话，直接return掉。

---

相反，如果Receiver可以接收的话，此时就会修改广播状态为APP_RECEIVE
```
        r.manifestCount++;
        r.delivery[recIdx] = BroadcastRecord.DELIVERY_DELIVERED;
        r.state = BroadcastRecord.APP_RECEIVE; //修改该广播的状态为APP_RECEIVE.
        r.curComponent = component;
        r.curReceiver = info.activityInfo;
```

如果receiver所在的进程已经启动了，调用processCurBroadcastLocked着手处理该广播receiver
```
        // Is this receiver's application already running?
        if (app != null && app.thread != null && !app.killed) {
            try {
                app.addPackage(info.activityInfo.packageName,
                        info.activityInfo.applicationInfo.versionCode, mService.mProcessStats);
                processCurBroadcastLocked(r, app, skipOomAdj);
                return;
            } 
            ...
        }
```

如果receiver所在的进程还没有启动，则先开启该进程, 
```
        if ((r.curApp=mService.startProcessLocked(...)) == null) {
            finishReceiverLocked(r, r.resultCode, r.resultData, r.resultExtras, r.resultAbort, false);
            scheduleBroadcastsLocked();
            r.state = BroadcastRecord.IDLE;
            return;
        }
```

### 2.2.1 AMS通知Receiver广播到来
从上面2.2小节可知，deliverToRegisteredReceiverLocked将广播deliver给动态注册的广播。它可以是在有序和无序广播中被调用。

第一个阶段依然是权限检查，可以参考 2.2中第4步，基本一样.
```
        if (ordered) { //针对有序广播
            r.receiver = filter.receiverList.receiver.asBinder(); //找到receiver
            r.curFilter = filter;
            filter.receiverList.curBroadcast = r;
            r.state = BroadcastRecord.CALL_IN_RECEIVE;
            if (filter.receiverList.app != null) {
                r.curApp = filter.receiverList.app;
                filter.receiverList.app.curReceivers.add(r);
                mService.updateOomAdjLocked(r.curApp, true);
            }
        }
        try {
            if (filter.receiverList.app != null && filter.receiverList.app.inFullBackup) {
                if (ordered) {
                    skipReceiverLocked(r);
                }
            } else {
                performReceiveLocked(filter.receiverList.app, filter.receiverList.receiver,
                        new Intent(r.intent), r.resultCode, r.resultData,
                        r.resultExtras, r.ordered, r.initialSticky, r.userId);
            }
            if (ordered) {
                r.state = BroadcastRecord.CALL_DONE_RECEIVE;
            }
        } catch (RemoteException e) {
            if (ordered) {
                r.receiver = null;
                r.curFilter = null;
                filter.receiverList.curBroadcast = null;
                if (filter.receiverList.app != null) {
                    filter.receiverList.app.curReceivers.remove(r);
                }
            }
        }
```
![动态注册广播Receiver](https://upload-images.jianshu.io/upload_images/5688445-296239b731f3c248.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
可以参考上面动态注册广播的图，deliverToRegisteredReceiverLocked会找到IIntenReceiver.Proxy,然后调用scheduleRegisteredReceiver去通知对应Receiver广播发生了。

![broadcast流程图](https://upload-images.jianshu.io/upload_images/5688445-5b69ca21878b4aba.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

### 2.2.2 有序广播queue收到Receiver完成广播处理

有序广播的一个特点是，如果高优先级的Receiver处理了广播，它有权abort该广播，那后果是低优先级的Receiver就不会再接收到该广播。

Receiver调用abortBroadcast
```
        public final void abortBroadcast() {
            mAbortBroadcast = true;
        }
```
并且Receiver会调用finishReceiver通知BroadcastQueue完成广播处理
```
        public void sendFinished(IActivityManager am) {
            synchronized (this) {
                try {
                    if (mOrderedHint) {
                        am.finishReceiver(mToken, mResultCode, mResultData, mResultExtras,
                                mAbortBroadcast, mFlags);
                    } else {
                        am.finishReceiver(mToken, 0, null, null, false, mFlags);
                    }
                } catch (RemoteException ex) {
                }
            }
        }
```
mOrderedHint表明该广播是有序广播，此时会向broadcastqueue传递相应的处理结果，以及mAbortBroadcast

AMS在收到finishReceiver后
```
public void finishReceiver(IBinder who, int resultCode, String resultData,
            Bundle resultExtras, boolean resultAbort, int flags) {
        final long origId = Binder.clearCallingIdentity();
        try {
            boolean doNext = false;
            BroadcastRecord r;

            synchronized(this) {
                BroadcastQueue queue = (flags & Intent.FLAG_RECEIVER_FOREGROUND) != 0
                        ? mFgBroadcastQueue : mBgBroadcastQueue;
                r = queue.getMatchingOrderedReceiver(who);
                if (r != null) {
                    doNext = r.queue.finishReceiverLocked(r, resultCode,
                        resultData, resultExtras, resultAbort, true);
                }
                if (doNext) {
                    r.queue.processNextBroadcastLocked(/*fromMsg=*/ false, /*skipOomAdj=*/ true);
                }
                // updateOomAdjLocked() will be done here
                trimApplicationsLocked();
            }
    }
```
在finishReceiverLocked，会将r.resultAbort = resultAbort, 此时可以参见2.2小节第3步，如果广播abort了，直接跳到下一个广播cycle.

![广播处理cycle](https://upload-images.jianshu.io/upload_images/5688445-9dfb96081cfbec2b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 三、sticky的广播
sticky的广播为粘性广播，什么意思呢？
我们知道如果是一般的有序或是无序广播，如果是广播处理cycle中，所有的receiver已经处理完该广播，则广播队列会将该条广播给删除掉，所以后面再注册的Receiver是不会收到这条已经被删除的广播的。

而sticky广播不一样，如果广播发送端指定一条广播是sticky的广播，则系统会一直记录该sticky广播，即使当时已经处理完sticky广播的Receiver, sticky广播也不会消失。当新注册一个广播receiver, AMS会立即向该receiver广播该sticky广播。

## 3.1 sticky广播发送端
在broadcastIntentLocked函数中
```
            if (checkPermission(android.Manifest.permission.BROADCAST_STICKY, callingPid, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException(msg);
            }
            if (requiredPermissions != null && requiredPermissions.length > 0) {
                return ActivityManager.BROADCAST_STICKY_CANT_HAVE_PERMISSION;
            }
            if (intent.getComponent() != null) {
                throw new SecurityException("Sticky broadcasts can't target a specific component");
            }
            if (userId != UserHandle.USER_ALL) {
                ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(UserHandle.USER_ALL);
                if (stickies != null) {
                    ArrayList<Intent> list = stickies.get(intent.getAction());
                    if (list != null) {
                        int N = list.size();
                        int i;
                        for (i=0; i<N; i++) {
                            if (intent.filterEquals(list.get(i))) {
                                throw new IllegalArgumentException( "Sticky broadcast " + intent + " for user "
                                        + userId + " conflicts with existing global broadcast");
                            }
                        }
                    }
                }
            }
```
上面的代码表示 sticky的广播
- 获得 `android.permission.BROADCAST_STICKY`
- sticky广播不能 enforce 权限
- sticky广播不能作用在某一个特殊的component上
- sticky广播如果不是针对所有用户的话，会检查它是否和当前sticky的广播发生冲突

如果sticky广播通过了上面的检查，那就将sticky的广播保存到 mStickyBroadcasts中
```
            ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(userId);
            if (stickies == null) {
                stickies = new ArrayMap<>();
                mStickyBroadcasts.put(userId, stickies);
            }
            ArrayList<Intent> list = stickies.get(intent.getAction());
            if (list == null) {
                list = new ArrayList<>();
                stickies.put(intent.getAction(), list);
            }
            final int stickiesCount = list.size();
            int i;
            for (i = 0; i < stickiesCount; i++) {
                if (intent.filterEquals(list.get(i))) {
                    // This sticky already exists, replace it.
                    list.set(i, new Intent(intent));
                    break;
                }
            }
            if (i >= stickiesCount) {
                list.add(new Intent(intent));
            }
```

## 3.2 sticky广播Receiver
```
    public Intent registerReceiver(IApplicationThread caller, String callerPackage,
            IIntentReceiver receiver, IntentFilter filter, String permission, int userId,
            int flags) {
            ...
            while (actions.hasNext()) {
                String action = actions.next();
                for (int id : userIds) {
                    ArrayMap<String, ArrayList<Intent>> stickies = mStickyBroadcasts.get(id);
                    if (stickies != null) {
                        ArrayList<Intent> intents = stickies.get(action);
                        if (intents != null) {
                            if (stickyIntents == null) {
                                stickyIntents = new ArrayList<Intent>();
                            }
                            stickyIntents.addAll(intents);
                        }
                    }
                }
            }
        }

        ArrayList<Intent> allSticky = null;
        if (stickyIntents != null) {
            final ContentResolver resolver = mContext.getContentResolver();
            // Look for any matching sticky broadcasts...
            for (int i = 0, N = stickyIntents.size(); i < N; i++) {
                Intent intent = stickyIntents.get(i);
                if (filter.match(resolver, intent, true, TAG) >= 0) {
                    if (allSticky == null) {
                        allSticky = new ArrayList<Intent>();
                    }
                    allSticky.add(intent);
                }
            }
        }
```
看看刚注册的Receiver是否可以接收sticky的广播

```
            if (allSticky != null) {
                ArrayList receivers = new ArrayList();
                receivers.add(bf);

                final int stickyCount = allSticky.size();
                for (int i = 0; i < stickyCount; i++) {
                    Intent intent = allSticky.get(i);
                    BroadcastQueue queue = broadcastQueueForIntent(intent);
                    BroadcastRecord r = new BroadcastRecord(queue, intent, null,
                            null, -1, -1, false, null, null, OP_NONE, null, receivers,
                            null, 0, null, null, false, true, true, -1);
                    queue.enqueueParallelBroadcastLocked(r);
                    queue.scheduleBroadcastsLocked();
                }
            }

            return sticky;
        }
    }
```
将sticky的广播加入到广播队列中，然后进入第二节的广播处理cycle. Receiver就会收到该sticky的广播了。

# 四、广播 ANR
从有序广播的流程可以看出，有序广播是一个一个的将广播deliver给广播Receiver. 如果Receiver处理广播的时间过长、或因为代码错误导致死循环了， 那后面的Receiver将不会再得到调用了。这是系统不愿看到的， 所以应该会有个超时的机制。
从第2.2小节可以看出，两种广播队列在初始化时，它们都有一个timeout时间，也就是超时时间，如果一个广播Receiver超过了timeout时间还没有处理完，此时广播队列就会触发超时机制触发ANR

在 processNextBroadcastLocked 找到要处理的有序广播后，也就意味着要deliver了，在deliver之前，会设置超时时间，
```
        r.receiverTime = SystemClock.uptimeMillis();
        ...
        if (! mPendingBroadcastTimeoutMessage) {
            long timeoutTime = r.receiverTime + mTimeoutPeriod;
            setBroadcastTimeoutLocked(timeoutTime);
        }
```
```
    final void setBroadcastTimeoutLocked(long timeoutTime) {
        if (! mPendingBroadcastTimeoutMessage) {
            Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG, this);
            mHandler.sendMessageAtTime(msg, timeoutTime);
            mPendingBroadcastTimeoutMessage = true;
        }
    }
```
使用的机制是handler在mTimeoutPeriod后发送一条BROADCAST_TIMEOUT_MSG信息，如果在超时之前完成了，则会cancelBroadcastTimeoutLocked cancel掉该信息。
 
如果超时后会调用
```

    final void broadcastTimeoutLocked(boolean fromMsg) {
        ...
        //调度下一个receiver
        // Move on to the next receiver.
        finishReceiverLocked(r, r.resultCode, r.resultData,
                r.resultExtras, r.resultAbort, false);
        scheduleBroadcastsLocked();

        //触发ANR
        if (!debugging && anrMessage != null) {
            mHandler.post(new AppNotResponding(app, anrMessage));
        }
    }
```
# 五、小结
Android的广播Receiver注册方式包括
- 静态注册
- 动态注册

Android的广播发送分为
- 无序广播 - sendBroadcast

- 有序广播 - sendOrderedBroadcast
有序广播会根据Receiver的优先级依次调度Receiver, 如果高优先级的Receiver完成广播的处理后，调用了abortBroadcast, 则低优先级的Receiver将不会再收到该Broadcast了。

- sticky的广播 - sendStickyBroadcast
需要获得BROADCAST_STICKY权限，且sticky广播不能指定一个特定的component上。
只有新注册的动态广播receiver才能接收到sticky的广播，静态receiver不能收到sticky的广播。见2.2第6步分析。

---

AMS提供了两种广播队列，一种是background，一种是foreground的队列。根据广播中intent中的flag，即是否设置FLAG_RECEIVER_FOREGROUND来决定是将broadcast放入哪个队列中。
如果是foreground队列中的广播，这会提升广播receiver的优先级到foreground, 至于是怎么提升的，请参考[Android Low memory killer](https://www.jianshu.com/p/b5a8a1d09712)
```
computeOomAdjLocked 函数中
else if (isReceivingBroadcastLocked(app, mTmpBroadcastQueue)) {
            adj = ProcessList.FOREGROUND_APP_ADJ;
            schedGroup = (mTmpBroadcastQueue.contains(mFgBroadcastQueue))
                    ? ProcessList.SCHED_GROUP_DEFAULT : ProcessList.SCHED_GROUP_BACKGROUND;
            app.adjType = "broadcast";
            procState = ActivityManager.PROCESS_STATE_RECEIVER;
        }
```
isReceivingBroadcastLocked计算当前app的正在running的receivers是在哪些广播队列中(也就两种，fg和bg)调度的，并放在mTmpBroadcastQueue中。 如果其中有**从mFgBroadcastQueue**调度的话，它的schedGroup会提升至SCHED_GROUP_DEFAULT , 否则SCHED_GROUP_BACKGROUND.

---
**广播ANR**，广播队列中针对**有序广播**会做超时处理，如果一个Receiver在timeout时间内还没有处理完，此时会触发ANR.

foreground的超时是10s
background的超时是60s.

还有一种本地广播.
它其实就是一种进程内通信的方式，它不会与System进程通信，这样就保证它更快，更安全。
