其实本不想写这个的，因为老罗的 [Android应用程序UI硬件加速渲染环境初始化过程分析](http://blog.csdn.net/luoshengyang/article/details/45769759) 写得太好了，而且非常详细，所以根本不必重复造轮子。原本只是将它作为即将要写的DisplayList绘制里一个小部分的，结果DisplayList绘制越写越多，篇幅太大了，所以就单独拎了出来

# 一、硬件渲染环境
```
 public void setView(View view, ...) {
    ...
    if (mSurfaceHolder == null) {
        enableHardwareAcceleration(attrs);
    }
    ...
}
```
在ViewRootImpl.java的setView里，会去enable硬件加速功能， 这里先不去深究mSurfaceHolder.
```
mAttachInfo.mHardwareRenderer = ThreadedRenderer.create(mContext, translucent);

renderer = new ThreadedRenderer(context, translucent);

ThreadedRenderer(Context context, boolean translucent) {
    ...
    long rootNodePtr = nCreateRootRenderNode();
    mRootNode = RenderNode.adopt(rootNodePtr);
    mRootNode.setClipToBounds(false);
    mNativeProxy = nCreateProxy(translucent, rootNodePtr);
    ...
}
```
而在enableHardwareAcceleration中会去生成ThreadedRender这个类， ThreadedRender类在初始化时会将native的RenderThread环境准备好. 具体是在生成RenderProxy对象时，会launch RenderThread

```
RenderProxy::RenderProxy(bool translucent, RenderNode* rootRenderNode,  IContextFactory* contextFactory)
        : mRenderThread(RenderThread::getInstance())
```
RenderThread::getInstance()是一个单例函数， 在RenderThread的构造函数中会直接调用run函数, 这样RenderThread线程就运行起来了。RenderThread线程一旦运行起来了会一直循环调用threadLoop函数.
```
bool RenderThread::threadLoop() {
    initThreadLocals();
    int timeoutMillis = -1;
    for (;;) {
        int result = mLooper->pollOnce(timeoutMillis);
        ...
    }
    return false;
}
```
threadLoop函数首先初始化RenderThread环境, 然后进入死循环等着task的唤醒然后执行。这些线程模型都大同小异，就不细讲了。

[Android Surface创建](http://www.jianshu.com/p/64e5c866b4ae)这篇文章讲了关于Android Surface的创建过程， 而RenderThread也要建立与Surface之间的联系.

当App的ViewImplRoot通过relayout向WMS申请创建Surface相关信息后，最后在App端创建了Surface类。 接着RenderThread线程就开始初始化Surface了, 
在`performTraversals`中
```
    if (mAttachInfo.mHardwareRenderer != null) {
    try {
        hwInitialized = mAttachInfo.mHardwareRenderer.initialize(mSurface);
            if (...) {
                mSurface.allocateBuffers()
            }
    } catch (...) {}
```

`mSurface.allocateBuffers()`会提前去分配GraphicBuffer, 以避免在渲染时分配产生的延时，这个后续会讲

```
    boolean initialize(Surface surface) throws OutOfResourcesException {
        ...
        nInitialize(mNativeProxy, surface);
        ...
    }
```
在JNI层通过RenderProxy去初始化

最终的硬件渲染UML图如下所示

![硬件渲染UML图](http://upload-images.jianshu.io/upload_images/5688445-376c8f30cec78cf9.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- ThreadedRender
  这个是Java的类，它主要是将UI线程的渲染操作传递给RenderThread线程，  大部分的调用是同步操作

- RootRenderNode
  这个是Native的类， 它保存着整个UI的DisplayList tree

- RenderProxy
  Native的类，由ThreadedRender创建，它的任务就是传递UI线程的渲染操作给RenderThread线程。

- CanvasContext
  这个类由RenderThread线程创建   

- EglSurface
  系统窗口或 frame buffer 句柄 [EGL接口介绍](http://blog.csdn.net/hgl868/article/details/7074052) , 那它和Surface是啥关系？

- RenderState

- DisplayInfo
  这个是从SurfaceFlinger里拿到的显示器的信息

- Surface
  native window

# 二、Open GL ES环境初始化

在讲绘制之前，先来看下Open GL ES环境的准备, 代码入口是 `nInitialize(mNativeProxy, surface);` 见第一节。而最终又会通过RenderProxy在RenderThread线程中调用到 `CanvasContext::setSurface`. 也就是通过EglManager去创建EGL surface, 调用函数`mEglSurface = mEglManager.createSurface(surface);`
最终的调用过程如下, 参考[EGL接口介绍](http://blog.csdn.net/hgl868/article/details/7074052)


createSurface的初始化顺序如下:
```
// 获得一个与native windowing系统的 Display connection的, 比如Linux下的X window系统
mEglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);

// EGLDisplay初始化, 同时获得 Open GL ES版本号
eglInitialize(mEglDisplay, &major, &minor)

// 通过eglQueryString查询EGLDisplay的extensions信息
initExtensions()

//eglChooseConfig配置frame buffer参数， 并且获得匹配attribs的frame buffer的配置信息，
eglChooseConfig(mEglDisplay, attribs, &mEglConfig, num_configs, &num_configs)

//创建Open GL ES渲染需要的上下文, 
// OpenGL的pipeline从程序的角度看就是一个状态机，有当前的颜色、纹理坐标、变换矩阵、绚染模式等一大堆状态，
//这些状态作用于程序提交的顶点 坐标等图元从而形成帧缓冲内的像素。
// 在OpenGL的编程接口中，Context就代表这个状态机，
//程序的主要工作就是向Context提供图元、设置状态，偶尔也从Context里获取一些信息。
//它的目的是保存输入的渲染数据
mEglContext = eglCreateContext(mEglDisplay, mEglConfig, EGL_NO_CONTEXT, attribs)

//创建EGL surface, 然后就可以用gl API往这个surface里绘制了, 
//目的是保存渲染的输出数据 
EGLSurface surface = eglCreateWindowSurface(mEglDisplay, mEglConfig, window, nullptr)
```

其中eglCreateWindowSurface是创建EGL surface， 参数里面的 window就是native window, 也就是Surface概念.

最后，在绘制之前还需要绑定上下文， 也就是 `eglMakeCurrent`
```
bool EglManager::makeCurrent(EGLSurface surface, EGLint* errOut) {
    if (isCurrent(surface)) return false;

    if (surface == EGL_NO_SURFACE) {
        // Ensure we always have a valid surface & context
        surface = mPBufferSurface;
    }   
    if (!eglMakeCurrent(mEglDisplay, surface, surface, mEglContext)) { 
        ...
    }   
    mCurrentSurface = surface;
    return true;
}
```
[Let’s talk about eglMakeCurrent, eglSwapBuffers, glFlush, glFinish](https://katatunix.wordpress.com/2014/09/17/lets-talk-about-eglmakecurrent-eglswapbuffers-glflush-glfinish/)这篇文章对elgMakeCurrent解释得很到位, 

eglMakeCurrent binds context to the current rendering thread AND TO the draw and read surfaces. draw is used for all GL operations except for any pixel data read back (glReadPixels, glCopyTexImage2D, and glCopyTexSubImage2D), which is taken from the frame buffer values of read.
eglMakeCurrent绑定mEGLContext到RenderThread, 这样RenderThread就可以绘制和读取Surface了，绘制操作基本上适用于所有的GL操作，这里要除去从frame buffer读取的操作(glReadPixels, glCopyTextImag2D ...) 

Therefore, when you call the GL command on a thread T, OpenGL ES will detect which context C was bound to T, and which surface S[draw] and S[read] were bound to C.
因此，当你在线程T中调用GL API时，OpengGL ES会检测到哪个 Context绑定到了线程中， 并且哪个(绘制Surface)、(读Surface)绑定给了Context.
