# 1. adb shell kill -9 pid_of_app

AMS定义了AppDeathRecipient

APP 在 attachApplication -> attachApplicationLocked
AMS里会注册 App 进程的 BinderDeath通知

```
AppDeathRecipient adr = new AppDeathRecipient(app, pid, thread);
thread.asBinder().linkToDeath(adr, 0);
```

当App进程被杀了后 
binder里的 binder_fops就会调用

```
binder_release -> binder_deferred_func -> binder_deferred_release

-> BINDER_WORK_DEAD_BINDER(binder_thread_read) ->  BR_DEAD_BINDER(binder_thread_read) -> BR_DEAD_BINDER(IPCThreadState::executeCommand)
-> (BpBinder->sendObituary) -> (BpBinder->reportOneDeath) -> (JavaDeathRecipient->binderDied)
-> BinderProxy.sendDeathNotice 
-> ActivityManagerService.binderDied ->  appDiedLocked -> handleAppDiedLocked 清理相关资源 resumeFocusedStackTopActivityLocked
```
**注意，这种方式kill掉的进程不会调用 onDestroy**

![link to death](https://upload-images.jianshu.io/upload_images/5688445-7fc8c10c48355547.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

https://www.cnblogs.com/samchen2009/p/3316001.html

# 2. 通过Recents里removeTasks杀掉进程
```
AMS.removeTask -> ASS.removeTaskByIdLocked ->
TaskRecord.removeTaskActivitiesLocked -> 这里面会去finish掉当前Activity, 也就是会调用onDestroy
ASS.cleanUpRemovedTaskLocked -> ProcessRecord.kill -> 
Process.killProcessQuiet -> sendSignalQuiet -> kill -9 pid (类似于kill -9)
Process.killProcessGroup -> killProcessGroup
```
后面的过程的1一样，通过binder death去通知AMS
