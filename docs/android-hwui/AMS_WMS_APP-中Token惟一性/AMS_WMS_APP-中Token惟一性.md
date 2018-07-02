> AMS/WMS 以及APP中(Activity,Window)大量使用了"Token", 那么这些Token是干什么的，是怎么来的呢？

转载请标明来处:  http://www.jianshu.com/p/5e2efbaa2949

![Token的惟一性](http://upload-images.jianshu.io/upload_images/5688445-6cf0575bb52ccb45.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

上图很好的画出来AMS WMS以及APP之间Token之间的关系。

注意，书写顺序也就是Token的传递过程

# 1. ActivityRecord中的token
```
startActivityLocked -> 
    ActivityRecord r = new ActivityRecord(..); //生成ActivityRecord实例
    ActivityRecord(
        ...
        appToken = new Token(this, service);
        ...
    }
```
启动一个Activity的时候会为这个Activity生成一个ActivityRecord对象，该对象用于AMS管理跟踪，而 Token就在这里诞生了。 由图中p1所示。

---
**作用**

Token类实现了IApplicationToken.Stub，也就是作为Binder的服务端，那么它自然的接收客户端的请求，那它主要提供什么样的服务呢? 看下IApplicationToken.aidl
```
interface IApplicationToken
{
    void windowsDrawn();
    void windowsVisible();
    void windowsGone();
    boolean keyDispatchingTimedOut(String reason);
    long getKeyDispatchingTimeout();
}
```
可以看出，大部分是WindowManagerService用于通知ActivityManagerService的关于Window的消息，也有key的相关消息

# 2. WMS中的token
```
startActivityUnlocked->
    startActivityLocked->
        addConfigOverride->
            addAppToken(task.mActivities.indexOf(r), r.appToken,
                r.task.taskId, mStackId ...)
```
将ActivityRecord中的appToken加入到WMS中
```
public void addAppToken( ) {
            //生成ActivityRecord在WMS中对应的AppWindowToken,并引用到ActivityRecord中的Token,见p2
            atoken = new AppWindowToken(this, token, voiceInteraction); 
            //如果没有Task, 就创建一个task, 并加入到stack中，
            //这里的task/stack都是与AMS中task/stack就一一对应的。 见p3
            Task task = mTaskIdToTask.get(taskId); 

            if (task == null) {
                task = createTaskLocked(taskId, stackId, userId, atoken, taskBounds, config);
            }
            //将AppWindowToken加入到task中管理起来
            task.addAppToken(addPos, atoken, taskResizeMode, homeTask); 
            mTokenMap.put(token.asBinder(), atoken); //加入到mTokenMap中, 见p4
}
```
```
    AppWindowToken(WindowManagerService _service, IApplicationToken _token,
            boolean _voiceInteraction) {
        //将token的binder对象给AppWindowToken的父类WindowToken引用
        super(_service, _token.asBinder(), 
                WindowManager.LayoutParams.TYPE_APPLICATION, true);
        appWindowToken = this;
        appToken = _token;  //引用到ActivityRecord中的Token
        voiceInteraction = _voiceInteraction;
        mInputApplicationHandle = new InputApplicationHandle(this);
        mAppAnimator = new AppWindowAnimator(this);
    }
```

```
wtoken.appToken.windowsDrawn();
wtoken.appToken.windowsVisible(); 
appWindowToken.appToken.keyDispatchingTimedOut(reason);
```

---
**作用**

WindowManagerService中AppWindowToken保存着ActivityManagerService Binder对象，用来向AMS传递Window和按键的一些信息.
另外的一个用处是作为 mTokenMap的key
```
mTokenMap.put(token.asBinder(), atoken);
```
# 3. App中的token

```
attachApplicationLocked
    realStartActivityLocked
        app.thread.scheduleLaunchActivity(new Intent(r.intent), r.appToken, );

public final void scheduleLaunchActivity(Intent intent, IBinder token, ) {
            //生成App中的ActivityClientRecord
            ActivityClientRecord r = new ActivityClientRecord();
            r.token = token;  //将AMS中的token保存到 ActivityClientRecord中 见P5
}

```
AMS将ActivityRecord的appToken传递给App进程。

```
private void handleLaunchActivity(ActivityClientRecord r, ...) {
        Activity a = performLaunchActivity(r, customIntent);
}

private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
          activity = mInstrumentation.newActivity(
                    cl, component.getClassName(), r.intent); //通过反射得到Activity实例
          activity.attach(appContext, this, getInstrumentation(), r.token ...); //attach
}

final void attach(Context context, ActivityThread aThread,
            Instrumentation instr, IBinder token, ...) {
        mToken = token; //见p6
        mWindow.setWindowManager(
                (WindowManager)context.getSystemService(Context.WINDOW_SERVICE),
                mToken, ...)
}

public void setWindowManager(WindowManager wm, IBinder appToken, String appName,
            boolean hardwareAccelerated) {
        mAppToken = appToken; //见p7
}
```

---
**作用**
Activity中的token涉及到多个地方，

- **ActivityClientRecord**
  这个类是Activity在ActivityThread中一一对应的，一个APP有多个Activity, 也就是说有多个ActivityClientRecord， 那么当AMS要启动一个Activity的时候，怎么样找到APP中正确的那个Activity呢？答案就是通过Token, 
如：
```
public final ActivityClientRecord performResumeActivity(IBinder token,
            boolean clearHide, String reason) {
        ActivityClientRecord r = mActivities.get(token);
        r.activity.performResume();
}
```
先通过token找到ActivityClientRecord,然后再通过ActivityClientRecord中的activity就找到了正确的Activity了

- **Activity**
Activity中Token主要用于在请求AMS服务时用于定位到具体到AMS中正确的ActivityRecord
比如进入PIP模式，通过Token,AMS就可以知道具体是哪个Activity进入PIP,
```
    public void enterPictureInPictureMode() {
        try {
            ActivityManagerNative.getDefault().enterPictureInPictureMode(mToken);
        } catch (RemoteException e) {
        }
    }
```
又比如 startActivityForResult，希望在finish时得到一些结果，那么AMS在finish那个Activity时，会把result传递给resultTo(mToken对应的那个Activity), 
```
    public void startActivityForResult(@RequiresPermission Intent intent, int requestCode,
            @Nullable Bundle options) {
        if (mParent == null) {
            Instrumentation.ActivityResult ar =
                mInstrumentation.execStartActivity(
                    this, mMainThread.getApplicationThread(), mToken, this,
                    intent, requestCode, options);
```

- **Window**
Window中的Token主要是传给LayoutParams, 见下面分析

# 4. WindowManager.LayoutParams里的token
WindowManager.LayoutParams是App中的，但是这里单独拿出来，是因为WMS会使用到它中的Token
```
addView->setView

public void addView(View view, ViewGroup.LayoutParams params,
            Display display, Window parentWindow) {
        if (parentWindow != null) {//这里parentWindow为PhoneWindow
            parentWindow.adjustLayoutParamsForSubWindow(wparams);
        } 
}

void adjustLayoutParamsForSubWindow(WindowManager.LayoutParams wp) {
       ...
       } else {
             //这里为null, 且mContainer也为空，所以将mAppToken直接赋值给wp.token
            if (wp.token == null) {
                wp.token = mContainer == null ? mAppToken : mContainer.mAppToken;
            }
      }
}
```
由上代码可知WindowManager.LayoutParams的token为Window中的mAppToken也就是AMS中ActivityRecord中的Token 见p8

---
**作用**

WindowManager.LayoutParams中的 token传递给WMS,
另外它的大部分作用是一致性判断

# 5. WindowState中的token

```
public int addWindow(WindowManager.LayoutParams attrs) {
            //attrs.token即是图中的p8, 这里拿到的token不为null, 具体参考 **WMS中的token**
            WindowToken token = mTokenMap.get(attrs.token); 
            ...
            if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW){
                //这里atoken即是WindowToken的子类 AppWindowToken, 具体见p2
                atoken = token.appWindowToken;  
           }
            WindowState win = new WindowState(this, session, client, token,
                    attachedWindow, appOp[0], seq, attrs, viewVisibility, displayContent);
}

WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token,
           WindowState attachedWindow, int appOp, int seq, WindowManager.LayoutParams a) {
        mToken = token; //WindowState引用到WindowToken

        WindowState appWin = this;
        while (appWin.isChildWindow()) {
            //一个Activity上可能有多个窗口，这里找到父窗口
            appWin = appWin.mAttachedWindow;  
        }
        WindowToken appToken = appWin.mToken; // 
        while (appToken.appWindowToken == null) {
            WindowToken parent = mService.mTokenMap.get(appToken.token);
            if (parent == null || appToken == parent) {
                break;
            }
            appToken = parent;
        }
        mRootToken = appToken;
        //这里mAppToken就是WindowToken的子类 AppWindowToken. 见P4
        mAppToken = appToken.appWindowToken; 
```
从上面代码看出WindowState也间接有ActivityRecord中的Token的引用。

---
**作用**

WMS中的token是通过WindowManager.LayoutParams传过来的，作用之一是作为
mTokensMap中的key值用来储存对应的WindowToken
作用之二是通知AMS一些消息，如
```
mActivityManager.notifyEnterAnimationComplete(atoken.token);
wtoken.appToken.windowsVisible();
```
