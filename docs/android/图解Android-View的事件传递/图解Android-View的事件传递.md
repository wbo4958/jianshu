转载请标注: [http://www.jianshu.com/p/bea1bb4aac95](http://www.jianshu.com/p/bea1bb4aac95)
# 一、UI overview
在说 View的事件传递过程之前先看下UI overview。

![图1 UI overview](http://upload-images.jianshu.io/upload_images/5688445-8aa87c263810b073.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

这里的 `screen_simple.xml`

```
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fitsSystemWindows="true"
    android:orientation="vertical">
    <ViewStub android:id="@+id/action_mode_bar_stub"
              android:inflatedId="@+id/action_mode_bar"
              android:layout="@layout/action_mode_bar"
              android:layout_width="match_parent"
              android:layout_height="wrap_content"
              android:theme="?attr/actionBarTheme" />
    <FrameLayout
         android:id="@android:id/content"
         android:layout_width="match_parent"
         android:layout_height="match_parent"
         android:foregroundInsidePadding="false"
         android:foregroundGravity="fill_horizontal|top"
         android:foreground="?android:attr/windowContentOverlay" />
</LinearLayout>
```

Activity并不负责视图控制，它只是控制生命周期和事件处理，真正控制视图的是Window。一个Activity包含一个Window，Window才是真正代表一个窗口，Window 中持有一个 DecorView，而这个DecorView才是 view 的根布局。

打个不恰当比喻吧，Window类相当于一幅画(抽象概念，什么画我们未知) ，PhoneWindow为一副齐白石先生的山水画(具体概念，我们知道了是谁的、什么性质的画)，DecorView则为该山水画的具体内容(有山、有水、有树，各种界面)。DecorView呈现在PhoneWindow上。

上面两段文字摘自网络

DecorView继承FrameLayout, 即它本是一个ViewGroup. PhoneWindow通过 installDecor()来安装DecorView.  如图所示,


![图2 window and decorview](http://upload-images.jianshu.io/upload_images/5688445-41307f525c500e29.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

`installDecor()` 主要做了以下几件事情

- 生成DecorView的实例 (这样PhoneWindow就可以有View根的引用了)
- Android为DecorView做了一些通用的Layout(比如带标题、ActionBar等等)
 如：
FEATURE_SWIPE_TO_DISMISS ->  screen_swipe_dismiss.xml
FEATURE_ACTION_MODE_OVERLAY -> screen_simple_overlay_action_mode.xml
DEFAULE -> screen_simple.xml 
  这里的Layout会根据Window的Feature去做具体的选择，也就是开发者自己选择。但它们都有一个共同的特点，就是所有的Layout里都包含有android:id/content这个子ViewGroup, 而这个content就是给Activity里 setContentView所使用的。


- PhoneWindow引用到App需要定制的mContentParent.
  mContentParent指向通用Layout里的 android:id/content, 而这个content 即是Activity里setContentView里的Parent View.

下面来看下setContentView关键代码, 可见它将开发者自定义的layout inflate到了mContentParent里了
```
public void setContentView(@LayoutRes int layoutResID) {
    getWindow().setContentView(layoutResID);
    initWindowDecorActionBar();
}

public void setContentView(int layoutResID) {
    // Note: FEATURE_CONTENT_TRANSITIONS may be set in the process of installing the window
    // decor, when theme attributes and the like are crystalized. Do not check the feature
    // before this happens.
    if (mContentParent == null) {
        installDecor();
    } else if (!hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
        mContentParent.removeAllViews();
    }

    if (hasFeature(FEATURE_CONTENT_TRANSITIONS)) {
        final Scene newScene = Scene.getSceneForLayout(mContentParent, layoutResID,
                getContext());
        transitionTo(newScene);
    } else {
	//将Activity里的layout inflate到 mContentParent里
        mLayoutInflater.inflate(layoutResID, mContentParent);
    }
    mContentParent.requestApplyInsets();
    final Callback cb = getCallback();
    if (cb != null && !isDestroyed()) {
        cb.onContentChanged();
    }
    mContentParentExplicitlySet = true;
}
```

# 二、Activity/ViewRootImpl以及WMS之间的关系
可以参考下这篇文章，http://www.jianshu.com/p/c223b993b1ec

![图3 Activity ViewRootImpl Wms之间的关系](http://upload-images.jianshu.io/upload_images/5688445-e68ca880f3900bc4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

上图发生的顺序如下:
1. 初始化Activity
ActivityThread在实例化一个Activity后，会将AMS 传递过来的信息attach到Activity里，同时生成一个PhoneWindow对象.
```
final void attach(Context context, ActivityThread aThread,
        Instrumentation instr, IBinder token, int ident,
        Application application, Intent intent, ActivityInfo info,
        CharSequence title, Activity parent, String id,
        NonConfigurationInstances lastNonConfigurationInstances,
        Configuration config, String referrer, IVoiceInteractor voiceInteractor,
        Window window) {
    attachBaseContext(context);

    mWindow = new PhoneWindow(this, window);
    mWindow.setWindowControllerCallback(this);
    mWindow.setCallback(this);
…
}
```
2. 接着为PhoneWindow安装DecorView (一般是在 Activity.onCreate()调用setContentView来最终调用到installDecor)
3. WindowManager 将ViewRootImpl.W传给 WMS管理, (具体是 addView )
a) 生成 ViewRootImpl  
b) 将DecorView作为 根view
c) 生成 WindowInputEventReceiver 用于接收触摸按键等事件(具体的注册动作是在WMS里进行的)
d) 通过Session.addToDisplay 将W 传给WMS

从上面3点可知:
1.	一个App只有一个WindowManager (WindowManagerGlobal.getInstance())
2.	WindowManagerGlobal管理着App里所有的RootView(DecorView或addView里的根View), ViewRootImpl,以及对应的LayoutParams
3.	每个Activity仅有一个DecorView
4.	每个Activity仅有一个PhoneWindow
5.	ViewRootImpl的个数与addView的个数相关, 并不一定只有一个。

**ViewRootImpl.W**
该类用于WindowManagerService通知一些UI相关事件发生了，如:
dispatchAppVisiblity
windowFocusChanged
activateWindow

# 三、View的事件流程

图3 的WindowInputEventReceiver类用于接收IMS传递过来的按键、触屏等相关事件。下面以触屏事件为例来看下事件传递过程


![图4 View事件传递](http://upload-images.jianshu.io/upload_images/5688445-cec276a57529162e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

上图灰色背景一般是APP不能直接处理的，而浅绿色背景的模块则是APP需要定制的。

## 3.1 View都不处理事件

![图5 View都不处理事件](http://upload-images.jianshu.io/upload_images/5688445-79b5a909578e225c.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

上图表示所有的View都没有处理 ACTION_DOWN事件，那么就没有必要把后面的事件(ACTION_MOVE/ACTION_UP)再往下发送了。
这样后面来的ACTION_MOVE和ACTION_UP都将不会处理了。
具体的实现是在ViewGroup.dispatchTouchEvent, 通过判断mFirstTouchTarget是否为空来决定是否还继续往子view发送。 因为如果有子view  consume了ACTION_DOWN事件的话，那么 mFirstTouchTarget将不会为NULL.

## 3.2 子View处理事件

![图6 子view处理事件](http://upload-images.jianshu.io/upload_images/5688445-ee9204493f60b185.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

View里因为设置了 onClickListener(), 这样就导致 View是 clickable (或者可以直接在xml里加上android:clickable=”true”)，即可点击，那么View.onTouchEvent就会永远返回 True, 代表View consume了该事件。
注意：只要View consume了该事件，那么该事件既不会往下传(不会传给子view)，也不会往上传(后面Activity/ViewGroup 的 onTouchEvent将不会再调用)。

## 3.3 ViewGroup拦截

![图7 ViewGroup拦截事件](http://upload-images.jianshu.io/upload_images/5688445-09c47b73d96d6c25.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

onInterceptTouchEvent 是拦截的意思，它是拦截事件不往下传(不会再传给子View)，但是会将事件往上传(相关的 Activity/ViewGroup 会调用 onTouchEvent).
上图 onInterceptTouchEvent拦截了，所以不会再将ACTION_DOWN往View里传了，直接返回到 onTouchEvent.   因为ACTION_DOWN没有被任何View consume, 所以也没必要继续发送 ACTION_MOVE/ACTION_UP事件了。

## 3.4 ViewGroup consume事件

![图8 ViewGroup consume事件](http://upload-images.jianshu.io/upload_images/5688445-d2c0900e3b193884.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
上图是ViewGroup的 onTouchEvent consume了事件，那么说明是ViewGroup需要处理事件，所以将后续的 ACTION_MOVE/ACTION_UP直接发送给ViewGroup处理了，就不再需要往子View传递事件了。

# 四、总结

onTouchEvent  返回值表示是否 consume该事件，它的传递方向是往上传递，View -> ViewGroup->Activity. 但是一旦某个View或ViewGroup的onTouchEvent返回True,  就不会再往上传递了。

onInterceptTouchEvent 是ViewGroup特有的，表示是否拦截事件往下(子View)传递，Activity->ViewGroup->View, 如果ViewGroup拦截了事件，那么会直接调用ViewGroup 的onTouchEvent。
