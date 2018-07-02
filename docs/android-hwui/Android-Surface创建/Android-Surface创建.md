转载请标注来处:  http://www.jianshu.com/p/64e5c866b4ae

# 一、 OverView
Android Surface的创建涉及三个进程
1. **App 进程** 
 Surface本质上是一个Native Window, 并且保存着需要在屏幕上显示的数据(buffer), 它通常包含 triple-buffers 以防止Jank。
那么谁要创建Surface呢? 当然是App了，App需要将自己的内容显示在屏幕上，所以App负责发起Surface创建请求，创建好Surface后, 就可以直接可以在canvas上画图等，最终都会保存到Surface里的buffer里，最后由SurfaceFlinger合成并显示。

2. **System_Server进程**
主要是其中的WindowManagerService, 负责接收APP请求，向SurfaceFlinger发起具体的请求创建Surface, 且WMS需要创建Surface的辅助管理类，如SurfaceControl。
为什么需要与system进程打交道呢？App直接通知SurfaceFlinger创建Surface不就行了？为什么还需要通过system进程呢？个人猜想可能是统一由WMS管理，以及当前系统中的窗口的Z -order计算， 减轻SurfaceFlinger的负担。

3. SurfaceFlinger
为App进程创建具体的Surface, 在SurfaceFlinger里对应成Layer, 然后负责管理、合成显示。

本文以这三个进程创建Surface过程来写。 先来一张 OverView 图

![Surface overview](http://upload-images.jianshu.io/upload_images/5688445-62e9f08043122959.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 二、 与App进程相关的类

## 2.1 WMS中Session类的代理 IWindowSession

IWindowSession主要是App与WMS进行交互的类，它在ViewRootImpl中声明， 在ViewRootImpl构造函数中实例化, 如下代码
```
final IWindowSession mWindowSession;

public ViewRootImpl(Context context, Display display) {
    mWindowSession = WindowManagerGlobal.getWindowSession();
}
```
而mWindowSession具体又是通过 WindowManagerGlobal的静态函数 getWindowSession()获得
```
public static IWindowSession getWindowSession() {
    synchronized (WindowManagerGlobal.class) {
        if (sWindowSession == null) {
             ...
             IWindowManager windowManager = getWindowManagerService();
             sWindowSession = windowManager.openSession(...) //在WMS中打开一个Session	
             ...
        }
        return sWindowSession;
    }
}
```
由定义可知， **sWindowSession**声明为静态变量，可知在整个App的生命中，只有一个sWindowSession实例。
另外 App端的 sWindowSession拿到的是`IWindowSession.Stub.Proxy`即 WMS中Session的代理， 如 **图P1**所示。

## 2.2 Surface类 

Surface是定义在ViewRootImpl中的成员，定义时并实例化了，如下
```
final Surface mSurface = new Surface();
```
由于 ViewRootImpl的个数与addView的调用次数相关，一个Activity可以多次调用addView, 也就是说一个Activity可以有多个Surface. 

另外Surface被定义成final,也就是说mSurface不能重新再赋值了。

**注意:** 在ViewRootImpl中声明的Surface只是一个空壳，它并没有与真正的Surface映射起来，所以此时它是无效的Surface.

App进程通过 relayout 函数去创建真实Surface。

```
mWindowSession.relayout(..., mSurface);
```

# 三、System_Server进程中的Surface

system_server是一大块，而与Surface相关的操作主要由WMS完成的。

## 3.1 Session和SurfaceSession

### 3.1.1 Session类

由第二节可知， Session是通过openSession创建的

```
public IWindowSession openSession(...) {
    Session session = new Session(this, callback, client, inputContext);
    return session;
}
```

### 3.1.2 SurfaceSession类

SurfaceSession的创建是在WindowState生成后创建的, 如
```
win.attach()   //在WMS的addWindow()
void attach() { //WindowState类
    mSession.windowAddedLocked();
}
void windowAddedLocked() {  //在Session类中
    if (mSurfaceSession == null) {
        mSurfaceSession = new SurfaceSession();
        mService.mSessions.add(this);
    }
    mNumWindow++;
}
```
可以把Session类理解APP与WMS之间的已经连接好的会话层。WMS将所有的Session加入到 mSessions里。 

Session和SurfaceSession可以参见图中的**P2**

### 3.1.3 SurfaceSession的native层

接着上面看SurfaceSession的构造函数,
```
private long mNativeClient; // SurfaceComposerClient的Jni对象指针
public SurfaceSession() {
    mNativeClient = nativeCreate();
}
```
调用nativeCreate在JNI中进行创建，具体是创建什么呢？ 从mNativeClient的定义注释可以猜测应该是创建一个SurfaceComposerClient，并且将其JNI对象地址保存到mNativeClient中,

```
static jlong nativeCreate(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient* client = new SurfaceComposerClient();
    ...
    return reinterpret_cast<jlong>(client);  //获得SurfaceComposerClient的地址
}

SurfaceComposerClient::SurfaceComposerClient()
    : mStatus(NO_INIT), mComposer(Composer::getInstance())  { }
```
SurfaceComposerClient的相关UML可以参考图中 **P3**所示。
另外SurfaceComposerClient继承RefBase,所以在初始化后会调用一次onFirstRef
```
void SurfaceComposerClient::onFirstRef() {
    sp<ISurfaceComposer> sm(ComposerService::getComposerService()); //获得SurfaceFlinger service
     sp<ISurfaceComposerClient> conn = sm->createConnection(); 
     //在SurfaceFlinger中创建Connection,也就是一个SurfaceFlinger的一个Client
}
```
createConnection会返回**SurfaceFlinger**中**Client类**的Binder代理**BpSurfaceComposerClient**, 见**P4**所示。

**注意：** `Session/SurfaceSession/SurfaceComposerClient` 都是与APP相关的而非与某个Activity相关, 也就是它们相对于具体APP来说有且仅有一个实例。

## 3.2 SurfaceControl

当与APP相关的WMS(Session/SurfaceSession/BpSurfaceComposerClient)，SurfaceFlinger(Client/BnSurfaceComposerClient 见第四节)相关类已经建立好后，此时就可以创建Surface了。

具体是在 ViewRootImpl的relayoutWindow(), 由performTraversals()调用

```
private void performTraversals() {
	...
	boolean hadSurface = mSurface.isValid(); // 此时mSurface是空壳
	relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);
	if (!hadSurface) {
		if (mSurface.isValid()) {  //mSurface有了正确的引用了
		...
	  }
	}
}
```
在relayoutWindow之前, mSurface仅是一个空壳，而在relayoutWindow之后， mSurface就已经创建好了，且有效。

```
private int relayoutWindow(...)
	int relayoutResult = mWindowSession.relayout(..., mSurface);
}
```
relayoutWindow **“直接”** 调用了WMS的relayoutWindow()

```
public int relayoutWindow(..., Surface outSurface) {
         //获得WindowState, 表示一个Window
	WindowState win = windowForClientLocked(session, client, false); 
	WindowStateAnimator winAnimator = win.mWinAnimator; 
        //创建SurfaceControl
	result = createSurfaceControl(outSurface, result, win, winAnimator); 
}
```
---

### 3.2.1 创建java侧的SurfaceControl

调用WindowStateAnimator的createSurfaceControl
```
private int createSurfaceControl(Surface outSurface, int result, WindowState win,
        WindowStateAnimator winAnimator) {
	WindowSurfaceController surfaceController = winAnimator.createSurfaceLocked();
    if (surfaceController != null) {
        surfaceController.getSurface(outSurface); 
    } 
    ...
    return result;
}

--- in WindowStateAnimator
WindowSurfaceController createSurfaceLocked() {
    mSurfaceController = new WindowSurfaceController(mSession.mSurfaceSession, ...);
    ...
    mSurfaceController.setPositionAndLayer(mTmpSize.left, mTmpSize.top, layerStack, mAnimLayer);
}

public WindowSurfaceController(SurfaceSession s, ...) {
  mSurfaceControl = new SurfaceControl(...)
}
```
它们之间建立的关系图参考**P5**所示,

---
### 3.2.2 创建Native的SurfaceControl

现在看下SurfaceControl的构造函数, 
```
public SurfaceControl(SurfaceSession session,...){
    mNativeObject = nativeCreate(session, name, w, h, format, flags);
}
```
调用jni层的nativeCreate函数来创建native层的SurfaceControl
```
static jlong nativeCreate(...) {
    sp<SurfaceComposerClient> client(android_view_SurfaceSession_getClient(env, sessionObj));
    sp<SurfaceControl> surface = client->createSurface(String8(name.c_str()), w, h, format, flags);
    return reinterpret_cast<jlong>(surface.get());
}
```
代码中 client拿到的是 SurfaceComposerClient, 如**P6**所示， 
```
sp<SurfaceControl> SurfaceComposerClient::createSurface(...)
{    
    sp<SurfaceControl> sur;
    if (mStatus == NO_ERROR) {
        sp<IBinder> handle;
        sp<IGraphicBufferProducer> gbp;
        status_t err = mClient->createSurface(name, w, h, format, flags, &handle, &gbp);
        if (err == NO_ERROR) {
            sur = new SurfaceControl(this, handle, gbp); //创建SurfaceControl
        }
    }
    return sur;
}
```
接着通过**SurfaceComposerClient**在SurfaceFlinger中创建Surface, 并且会返回SurfaceFlinger中的Handle以及IGraphicsBufferProducer的代理,如**P7**所示。

当SurfaceFlinger中创建好Surface后，在WMS的JNI层创建一个SurfaceControl, SurfaceControl顾名思义就是控制、操作Surface的类.

但是特别注意，这里的SurfaceControl实际上是**WMS控制、操作Surface**(比如Surface的Z order值，以及position, 透明度等的设置)的而非APP. 

### 3.2.3 创建Native层的Surface

在Java端的SurfaceControl创建好后，就开始创建真正的Native的Surface。回忆一下之前说的App端的Surface只是一个空壳，因为它还没有与真正的Native端的Surface联系起来。在这一小节将会把App端的Surface与真正的Surface联系起来。

```
--- in WindowSurfaceController
WindowSurfaceController surfaceController = winAnimator.createSurfaceLocked();
if (surfaceController != null) {
    surfaceController.getSurface(outSurface);  //获得surface
}

void getSurface(Surface outSurface) {
    outSurface.copyFrom(mSurfaceControl);
}
```

---
**这里的outSurface是哪个surface??**, 从Session中的relayout定义来看
```
    public int relayout(IWindow window, int seq, WindowManager.LayoutParams attrs,
            int requestedWidth, int requestedHeight, int viewFlags,
            int flags, Rect outFrame, Rect outOverscanInsets, Rect outContentInsets,
            Rect outVisibleInsets, Rect outStableInsets, Rect outsets, Rect outBackdropFrame,
            Configuration outConfig, Surface outSurface) {
```
这个outSurface会是App端传过来的Surface么？之前我一直以为该Surface是App的Surface, 然而并不是

App调用relayout，实际上是IWindowSession的Proxy类中的relayout，代码如下 
```
public int relayout(android.view.IWindow window, int seq, android.view.WindowManager.LayoutParams attrs,  ... , 
        android.view.Surface outSurface) throws android.os.RemoteException {
    android.os.Parcel _data = android.os.Parcel.obtain();
    android.os.Parcel _reply = android.os.Parcel.obtain();
    int _result;
    try {
        ...
        mRemote.transact(Stub.TRANSACTION_relayout, _data, _reply, 0);
        _reply.readException();
        _result = _reply.readInt();
        ...
        if ((0 != _reply.readInt())) {
            outSurface.readFromParcel(_reply);
        }
    } finally {
        _reply.recycle();
        _data.recycle();
    }
    return _result;
}
```
从代码来看App端的outSurface并没有传到_data里，也就是并没有传递给Server端，相反，它是从_reply这个Parcel里读出来的。

现在来看下Server端的处理，在IWindowSession.Stub的onTransact里
```
case TRANSACTION_relayout: {
    ...
    android.view.Surface _arg15;
    _arg15 = new android.view.Surface();
    int _result = this.relayout(...);
    reply.writeNoException();
    reply.writeInt(_result);
    ...
    if ((_arg15 != null)) {
        reply.writeInt(1);
        _arg15.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
    } else {
        reply.writeInt(0);
    }
    return true;
}
```
_arg15这个参数就是Surface, 原来是在Server端(SystemServer进程中)生成的Surface, 也就是图1中那个临时在System_server创建的Surface. 最后将该临时生成的Surface写入到reply的Parcel中。

---

下面的函数就是将native的surface引用到system_serve临时产生的那个surface里
```
--- in Surface.java
public void copyFrom(SurfaceControl other) {
    long surfaceControlPtr = other.mNativeObject; //获得native surfacecontrol的地址
    long newNativeObject = nativeCreateFromSurfaceControl(surfaceControlPtr); //创建native surface
    synchronized (mLock) {
        setNativeObjectLocked(newNativeObject); //将native surface的地址保存到App端的Surface里
    }
}
```

通过将Native Surface的地址保存到SystemServer进程Java侧的临时Surface里

```
static jlong nativeCreateFromSurfaceControl(JNIEnv* env, jclass clazz,
        jlong surfaceControlNativeObj) {
    sp<SurfaceControl> ctrl(reinterpret_cast<SurfaceControl *>(surfaceControlNativeObj));
    sp<Surface> surface(ctrl->getSurface());   
    return reinterpret_cast<jlong>(surface.get());
}

sp<Surface> SurfaceControl::getSurface() const 
{
    if (mSurfaceData == 0) {
        mSurfaceData = new Surface(mGraphicBufferProducer, false); //创建Surface
    }   
    return mSurfaceData;
}
```

---
最后看下App端的Surface是怎么引用到正确的值的呢？
由上面可知App端的Surface通过是`outSurface.readFromParcel(_reply);`引用的
```
----- in Surface.java
public void readFromParcel(Parcel source) {
    synchronized (mLock) {
        mName = source.readString();
        setNativeObjectLocked(nativeReadFromParcel(mNativeObject, source));
    }
}

static jlong nativeReadFromParcel(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);

    android::view::Surface surfaceShim;

    // Calling code in Surface.java has already read the name of the Surface
    // from the Parcel
    surfaceShim.readFromParcel(parcel, /*nameAlreadyRead*/true);
    sp<Surface> self(reinterpret_cast<Surface *>(nativeObject));
    ...
    sp<Surface> sur;
    if (surfaceShim.graphicBufferProducer != nullptr) {
         //生成正确的Surface
        sur = new Surface(surfaceShim.graphicBufferProducer, true);
        sur->incStrong(&sRefBaseOwner);
    }
        
    return jlong(sur.get());
}

//获得 GraphicBufferProducer binder对象
status_t Surface::readFromParcel(const Parcel* parcel, bool nameAlreadyRead) { 
    sp<IBinder> binder;
    status_t res = parcel->readStrongBinder(&binder);
    graphicBufferProducer = interface_cast<IGraphicBufferProducer>(binder);
    return OK;
}
```

具体的类图参见 **P8, P9**

## 3.3 设置position与Layer

事实上这步发生在3.2.3之前

```
mSurfaceController.setPositionAndLayer(mTmpSize.left, mTmpSize.top, layerStack, mAnimLayer);
```
```
void setPositionAndLayer(float left, float top, int layerStack, int layer) {
    SurfaceControl.openTransaction();  
    try {
        mSurfaceX = left;
        mSurfaceY = top;
        try {
            mSurfaceControl.setPosition(left, top);
            mSurfaceControl.setLayerStack(layerStack);
            mSurfaceControl.setLayer(layer);
            mSurfaceControl.setAlpha(0);
            mSurfaceShown = false;
        } catch (RuntimeException e) {
        }
    } finally {
        SurfaceControl.closeTransaction();
    }
}
```
openTransaction与closeTransaction是成对出现的，本质上是为了一次性提交多个属性，而非一次一个一个的提交，这样performance会相对高些，另外frame也会相对稳定些(这个待续).

setPosition/setLayer这些函数调用流程如下

SurfaceControl(java) -> SurfaceControl(jni) -> SurfaceComposerClient -> Composer
以setPosition为例
```
bool Layer::setPosition(float x, float y, bool immediate) {
    if (mCurrentState.requested.transform.tx() == x && mCurrentState.requested.transform.ty() == y)
        return false;
    mCurrentState.sequence++;
    mCurrentState.requested.transform.set(x, y);
    if (immediate && !mFreezePositionUpdates) {
        mCurrentState.active.transform.set(x, y);
    }    
    mFreezePositionUpdates = mFreezePositionUpdates || !immediate;

    mCurrentState.modified = true;
    setTransactionFlags(eTransactionNeeded);
    return true;
}
}
```

getLayerStateLocked首先拿到ComposerState, 如果没有，则新创建一个，最后放到  mComposerStates track起来，最后把相关的属性存放到 CompserState中。最后通过`SurfaceControl.closeTransaction();`一并将ComposerState提交到SurfaceFlinger中对应的Layer类中的mCurrentState中, 并通过 Layer->setPosition将Surface的position设置到Layer中

**注意：** setPositionAndLayer通过setLayer将 Window的Z -order传递下去，这里是WindowStateAnimator的mAnimLayer, 

# 四、SurfaceFlinger中的Surface

## 4.1 创建APP对应的Client
```
sp<ISurfaceComposerClient> SurfaceFlinger::createConnection()
{
    sp<ISurfaceComposerClient> bclient;
    sp<Client> client(new Client(this)); 
    ...
    return bclient;
}
```
Client继承BnSurfaceComposerClient, 与WMS中的BpSurfaceComposerClient相对应。

## 4.2 创建Surface

WMS通过SurfaceFlinger的Client来创建相应的Surface

Client通过MessageCreateLayer将创建Surface的请求放到队列中， 遵循先入先出的原则。 当Message被执行时，会调用SurfacefFlinger的createLayer()函数，咦，这里的 surface就变成了Layer??
```
status_t SurfaceFlinger::createLayer(...) {
    createNormalLayer(...); //其实有两种选择一个是Normal Layer, 另一种是Dim Layer
    addClientLayer(...)
}
```
创建Layer分两步，一步是创建Layer, 另一步是将Layer track起来

### 4.2.1 创建 Layer
```
status_t SurfaceFlinger::createNormalLayer(...)
{
    ...
    *outLayer = new Layer(this, client, name, w, h, flags);
    if (err == NO_ERROR) { 
        *handle = (*outLayer)->getHandle(); 
        *gbp = (*outLayer)->getProducer();
    }
    return err;
}
```
实例化Layer, 而由于Layer继承于RefBase, 在实例化后会调用一次 onFirstRef

```
void Layer::onFirstRef() {
    sp<IGraphicBufferProducer> producer;
    sp<IGraphicBufferConsumer> consumer;
    BufferQueue::createBufferQueue(&producer, &consumer);
    mProducer = new MonitoredProducer(producer, mFlinger);
    mSurfaceFlingerConsumer = new SurfaceFlingerConsumer(consumer, mTextureName);
}
```
在 onFirstRef里会为 Layer创建 BufferQueue模型 也就是典型的生产者/消费者模式，这个在后续讲。
具体可以参考 **P11,  P12, P13, P14**

### 4.2.2 SurfaceFlinger Tracks Layer
```
status_t SurfaceFlinger::addClientLayer(...)
{
        if (mCurrentState.layersSortedByZ.size() >= MAX_LAYERS) {
            return NO_MEMORY;
        }
        mCurrentState.layersSortedByZ.add(lbc);
        mGraphicBufferProducerList.add(IInterface::asBinder(gbc));
    client->attachLayer(handle, lbc);
    return NO_ERROR;
}
```
具体可以参考**P15, P16所示**


# 五、小结

至此，整个Surface的UML图已经建立起来了。
但是仅仅是很生硬的把UML图画出来，是吧？ 下面来小结一下，

## 5.1 **APP/WMS/SurfaceFlinger通信流程创建**
App启动Main Activity，
1. 首先创建好与WMS通信的媒介Session,
2. 然后通过Session将Window加入到WMS中，这时会触发  SurfaceSession的创建
3. SurfaceSession的创建又会在WMS Native创建SurfaceComposerClient, 
4. 最终在SurfaceFlinger里创建与SurfaceComposerClient对应的Client
至此 整个通信通道`(APP(Session) -> WMS, WMS(SurfaceComposerClient) -> SurfaceFlinger(Client))`已经建立起来了。

## 5.2 对Window进行布局(relayout)
 Choreographer会接收 SurfaceFlinger 的Vsync信号， 参考[Android SurfaceFlinger SW Vsync模型](http://www.jianshu.com/p/d3e4b1805c92)
然后执行 doFrame -> performTraverals()
接着通过  Session去请求WMS  对Window布局 (relayout)

relayout 主要是
1. 创建 Jave/Native端的 SurfaceControl
2. 通过 SurfaceComposerClient 通知 Client(SurfaceFlinger中)去创建 Layer, 并由SurfaceFlinger管理起来
3. SurfaceControl创建好后就开始创建真正的 native的Surface
4. App Surface通过relayout返回的parcel创建出App端的Surface

整体代码流程如下图所示

![Surface flowchart](http://upload-images.jianshu.io/upload_images/5688445-2be706dcbdafe8ea.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
