转载请标注: http://www.jianshu.com/p/5e802482caa4
始终觉得要理解一个庞大的东西，需要将它分解，从部分理解开始，慢慢理解了，那么这个庞大的东西也就理解。

在看这篇文章之前，非常建议参考[AMS/WMS/APP 中Token惟一性](http://www.jianshu.com/p/5e2efbaa2949), 因为这里面会涉及到各种Token,稍不注意就不知道具体的token是指什么了。

再把这张图贴在这里， 这样看代码时，对照着看就更容易理解了。
![Token的惟一性](http://upload-images.jianshu.io/upload_images/5688445-6cf0575bb52ccb45.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

---

**注意:** 本篇以Launcher启动一个App (MainActivity)时，来学习Z order. 另外由于在启动一个App时会有一个starting Window, 略过这部分。

# 一、加入Window到WMS的窗口堆栈
```
public int addWindow(Session session, IWindow client, int seq,
        WindowManager.LayoutParams attrs, int viewVisibility, int displayId,
        Rect outContentInsets, Rect outStableInsets, Rect outOutsets,
        InputChannel outInputChannel) {

    final int type = attrs.type;
	//attrs即Activity的Window中的LayoutParams, 这里值是FIRST_APPLICATION_WINDOW

    synchronized(mWindowMap) {
        //displayId为具体的显示屏，一般没有外接都是默认的显示屏
        //DisplayContent为显示屏上显示的内容，主要是一些Window对应的WindowState
        final DisplayContent displayContent = getDisplayContentLocked(displayId);

        //如果WindowMap已经加入过了，就直接返回，不用再生成对应的WindowState了
        if (mWindowMap.containsKey(client.asBinder())) {
            return WindowManagerGlobal.ADD_DUPLICATE_ADD;
        }

        boolean addToken = false;
        WindowToken token = mTokenMap.get(attrs.token); 
        //获得Activity对应在WMS中的WindowToken, 在这里已经不为空了，具体见[2. WMS中的token]
        AppWindowToken atoken = null;  
        if (token == null) {
         } else if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            atoken = token.appWindowToken;  //获得AppWindowToken
        } else (XXXX){        
        }
		
        //生成对应的WindowState用于保存Window信息
        WindowState win = new WindowState(this, session, client, token,
                attachedWindow, appOp[0], seq, attrs, viewVisibility, displayContent);

        res = WindowManagerGlobal.ADD_OKAY;
   
        if (addToken) {
            mTokenMap.put(attrs.token, token); //这个在addAppToken中已经加入过了，所以不用加入了
        }
        win.attach();  //Session里 mNumWindow加1，表示现在总共有多少window了
        mWindowMap.put(client.asBinder(), win);  //储存WindowState

        if (type == TYPE_INPUT_METHOD) {//输入法窗口，这里不讨论
        } else if (type == TYPE_INPUT_METHOD_DIALOG) {
        } else {
            addWindowToListInOrderLocked(win, true);  //将WindowState加入到窗口堆栈中
        }
    }
    return res;
}
```

## 1.1 Window的类型
addWindow最后会根据不同的Window类型做不同的处理，那这些Window类型是什么呢？

**attrs.type** 这个指明 Window的类型, 它的值在如下代码中初始化

```
private final WindowManager.LayoutParams mWindowAttributes = new WindowManager.LayoutParams();

public LayoutParams() {
    super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    type = TYPE_APPLICATION;  //默认为TYPE_APPLICATION类型
    format = PixelFormat.OPAQUE;
}
```
由代码可知，LayoutParams默认的Type是TYPE_APPLICATION = 2; 即普通的应用窗口

但是会在**handleResumeActivity**被修改掉，如下
```
--- in handleResumeActivity

WindowManager.LayoutParams l = r.window.getAttributes();
a.mDecor = decor;
l.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION; //修改窗口类型
wm.addView(decor, l);
```

但是在addView之前修改了窗口类型为TYPE_BASE_APPLICATION = 1类型，这个类型是基类，是Activity中最底层的窗口，换句话说，其它的窗口都会叠加在该窗口之上，比如Dialog窗口(它使用默认的TYPE_APPLICATION)。 这也说明type值越大，它就越在上面。

Android定义的窗口值如下:

- **应用程序窗口**

|定义|值|意义|
|-|-|-|
|FIRST_APPLICATION_WINDOW|1|第一个窗口，也就是TYPE_BASE_APPLICATION窗口|
|TYPE_BASE_APPLICATION|1|Activity的基窗口|
|TYPE_BASE_APPLICATION|2|默认窗口类型|
|TYPE_APPLICATION_STARTING|3|应用程序启动过程中的中间窗口，当真下窗口适配完会关闭该窗口|
|LAST_APPLICATION_WINDOW|99|应用程序最后一个窗口|

- **子窗口**
- **系统窗口**
其它两种窗口请参考 [Android入门之窗口类型](http://blog.csdn.net/geloin/article/details/42779025)(http://blog.csdn.net/geloin/article/details/42779025)

**Question:** 如果将type设为大于99会怎么呢？

在WMS的addWindow会对type进行权限检查
```
int res = mPolicy.checkAddPermission(attrs, appOp);
```
即如果type不属于 应用窗口/子窗口/系统窗口，直接会报异常。

---

而真正将Window加入到窗口堆栈的函数是 **addWindowToListInOrderLocked**

## 1.2 addWindowToListInOrderLocked

```
private void addWindowToListInOrderLocked(final WindowState win, boolean addToToken) {
    if (win.mAttachedWindow == null) { //新启动的Activity, 没有attach的Window
        final WindowToken token = win.mToken; //WindowState中的mToken
        int tokenWindowsPos = 0;
        if (token.appWindowToken != null) { //这里显然不为null,在生成AppWindowToken时指向 见图p2
            tokenWindowsPos = addAppWindowToListLocked(win); //进入这个分支
        } 
}
```

```
private int addAppWindowToListLocked(final WindowState win) {
    final DisplayContent displayContent = win.getDisplayContent();
    final IWindow client = win.mClient;
    final WindowToken token = win.mToken;

    final WindowList windows = displayContent.getWindowList(); //得到DisplayContent中所有的WindowState, 
```

此时窗口堆栈顺序如下:

![窗口堆栈顺序](http://upload-images.jianshu.io/upload_images/5688445-0253085e3b3d76f0.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
    WindowList tokenWindowList = getTokenWindowsOnDisplay(token, displayContent); 
    //当前Activity MainActivity是第一次启动，所以它并没有其它Window

    final ArrayList<Task> tasks = displayContent.getTasks(); //这里会得到所有的task
```

![此时的Stack_Task的关系](http://upload-images.jianshu.io/upload_images/5688445-f97459046cefe889.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![tasks的值](http://upload-images.jianshu.io/upload_images/5688445-e6b6742ad3cbed57.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

接着看addAppWindowToListLocked后面的代码
```
    int taskNdx;
    int tokenNdx = -1;
    for (taskNdx = tasks.size() - 1; taskNdx >= 0; --taskNdx) { //当taskNdx = 1时
        AppTokenList tokens = tasks.get(taskNdx).mAppTokens;  //此时的task里仅有一个MainActivity中的AppWindowToken
        for (tokenNdx = tokens.size() - 1; tokenNdx >= 0; --tokenNdx) { //tokenNdx = 0
            final AppWindowToken t = tokens.get(tokenNdx); //此时t 为 MainActivity中的AppWindowToken
            if (t == token) { //相等
                --tokenNdx; //此时tokenNdx = -1
                if (tokenNdx < 0) { //进入此分支
                    --taskNdx;  //寻找前一个task, 此时taskNdx = 0
                    if (taskNdx >= 0) { //进入该分支
                        //Launcher Task中的也仅有一个App token, 这里减1的目的是当 add 的时候，以tokenNdx为索引，加在后面
                        tokenNdx = tasks.get(taskNdx).mAppTokens.size() - 1; 
                    }
                }
                break;
            }
        }
        if (tokenNdx >= 0) { //此时tokenNdx = 0, 直接break掉
            break;
        }
    }


    // Continue looking down until we find the first
   // token that has windows on this display.
    //此时taskNdx = 0, tokenNdx = 0
    for ( ; taskNdx >= 0; --taskNdx) { 
        AppTokenList tokens = tasks.get(taskNdx).mAppTokens; //获得Launcher Task中所有的tokens
        for ( ; tokenNdx >= 0; --tokenNdx) {
            final AppWindowToken t = tokens.get(tokenNdx);  //这里获得的是Launcher的token
            tokenWindowList = getTokenWindowsOnDisplay(t, displayContent); //获得Launcher token中的WinState
            final int NW = tokenWindowList.size(); //这里NW为1，因为没有其它子窗口
            if (NW > 0) {
                pos = tokenWindowList.get(NW-1);  //直接获得最后的win state (这里也是Launcher的WinState)
                break;
            }
        }
    }

    if (pos != null) { //pos已经不为空了
        WindowToken atoken = mTokenMap.get(pos.mClient.asBinder()); //
        if (atoken != null) { //获得Launcher的 WindowToken
            final int NC = atoken.windows.size();
            if (NC > 0) {
                WindowState top = atoken.windows.get(NC-1);
                if (top.mSubLayer >= 0) {
                    pos = top;
                }
            }
        }
        placeWindowAfter(pos, win); //在Launcher后插入Win (MainActivity)
        return tokenWindowsPos;  //返回
    }
}
```
![此时窗口堆栈顺序](http://upload-images.jianshu.io/upload_images/5688445-bffe866e8eec2e84.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 二、Z -order
前面已经将Window加入到了窗口堆栈，并不是Z order,下面来看下什么是Z order。

由于前面窗口堆栈已经新加入了一个window, 即窗口堆栈变化了，那么此时就要计算 Z order了
具体是在 addWindow的最后了
```
--- in addWindow()

mLayersController = new WindowLayersController(this);
```
WMS初始化时初始化WindowLayersController, 该类主要是为DisplayContent里的Windows也就是Window堆栈指定layer, 也就是计算它们的Z order。 计算Z order时，从窗口堆栈的底部到顶部，层级越高，那么它的 layer 越大，即它的Z order越大。

再继续讲计算 Z -order之前先简单看下与Z order有关的几个变量, 具体在WindowState中
```
--- in WindowState

final WindowStateAnimator mWinAnimator;
int mLayer;

final int mBaseLayer;
final int mSubLayer;
```

WindowStateAnimator 是一个Window的动画以及对Surface的操作类，它类中有个Animator的 Z order值，
- mLayer 
mLayer是Base的Z -order值，它的值是动态算出来的。
- mAnimLayer
它的值是由mLayer算出来的, SurfaceFlinger中的Layer的Z order也就是mAnimLayer。

可以从定义看出 mBaseLayer, mSubLayer是final, 是不可再重新赋值的，所以它们的值是固定的，很显然是在WindowState初始化时赋值的。

```
--- in WindowState构造函数中

        if ((mAttrs.type >= FIRST_SUB_WINDOW && mAttrs.type <= LAST_SUB_WINDOW)) {
          //子窗口，不讨论
        } else {
            mBaseLayer = mPolicy.windowTypeToLayerLw(a.type)
                    * WindowManagerService.TYPE_LAYER_MULTIPLIER  //该值是10000
                    + WindowManagerService.TYPE_LAYER_OFFSET;   //该值为1000
            mSubLayer = 0;
       }
```
```
public int windowTypeToLayerLw(int type) {
   if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
            return 2;
   }
}
```

**以非子窗口为例**
此时mSubLayer=0,  而mBaseLayer与Window类型相关，以本文例子为例, 应用程序的窗口映射的z-order为2, 所以经过计算, mBaseLayer就为21000

 **注意:并不代表 Window type值越大，它的Z -order值越大，type值会被windowTypeToLayerLw重新映射。**

下面来看下是怎么计算z order值的
```
mLayersController.assignLayersLocked(displayContent.getWindowList());

final void assignLayersLocked(WindowList windows) {
    int curBaseLayer = 0;  //临时的基值
    int curLayer = 0;   //当前的值
    boolean anyLayerChanged = false;   //只要一个Z order值变了，这里就会为true
    for (int i = 0, windowCount = windows.size(); i < windowCount; i++) {
        final WindowState w = windows.get(i);
        boolean layerChanged = false;  //针对每一个window

        int oldLayer = w.mLayer;
        if (w.mBaseLayer == curBaseLayer || w.mIsImWindow || (i > 0 && w.mIsWallpaper)) {
            curLayer += WINDOW_LAYER_MULTIPLIER; //WINDOW_LAYER_MULTIPLIER值为5,
        } else {
            curBaseLayer = curLayer = w.mBaseLayer;
        }
        assignAnimLayer(w, curLayer); //设置Z order值，以及动画的Z order

        if (w.mLayer != oldLayer || w.mWinAnimator.mAnimLayer != oldLayer) {
            layerChanged = true;
            anyLayerChanged = true;
        }

        if (w.mAppToken != null) {
            mHighestApplicationLayer = Math.max(mHighestApplicationLayer,
                    w.mWinAnimator.mAnimLayer); //获得应用窗口中最大值，注意这里只是针对应用窗口。
        }
        collectSpecialWindows(w);  //收集特殊的窗口

        if (layerChanged) {
            w.scheduleAnimationIfDimming();
        }
    }

    adjustSpecialWindows();  //针对特殊窗口再做调整, 不讨论

    if (mService.mAccessibilityController != null && anyLayerChanged
            && windows.get(windows.size() - 1).getDisplayId() == Display.DEFAULT_DISPLAY) {
        mService.mAccessibilityController.onWindowLayersChangedLocked();
    }
}
```

![Z order值](http://upload-images.jianshu.io/upload_images/5688445-4ff66258f82d1c34.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 三、将 Z order更新到Native层

Z order值是Surface里的概念(SurfaceFlinger中对应的是Layer类), 由于addWindow发生的很早，此时并没有Surface. 所以并不是在这里设备Z order值的，

准备的说是在创建Surface过后，

```
WindowSurfaceController createSurfaceLocked() {
        ...
        //layer stack表示是该Surface显示在哪个显示设备上，
        final int layerStack = w.getDisplayContent().getDisplay().getLayerStack();
        //将 layerstack与 z -order值(mAnimlayer)设置到Surface对应的SurfaceFlinger中的Layer中
        mSurfaceController.setPositionAndLayer(mTmpSize.left, mTmpSize.top, layerStack, mAnimLayer);
        mLastHidden = true;
}
```
最后通过Layer.cpp中的setLayer函数将Z order值保存到Layer的mCurrentState中的z变量中。
```
bool Layer::setLayer(uint32_t z) { 
    if (mCurrentState.z == z)
        return false; 
    mCurrentState.sequence++;
    mCurrentState.z = z; 
    mCurrentState.modified = true;
    setTransactionFlags(eTransactionNeeded);
    return true;
}
```
