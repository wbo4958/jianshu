转载请标注出处：http://www.jianshu.com/p/7bf306c09c7e

先推荐一篇很不错的关于DisplayList构建的文章 [Android N中UI硬件渲染（hwui）的HWUI_NEW_OPS(基于Android 7.1)](http://blog.csdn.net/jinzhuojun/article/details/54234354)
看得出来作者对于硬件加速这块研究的很透彻， 对于一些概念性的东西解释的很到位，强烈建议大家去拜读一下。

而本文以具体的例子(**MyView绘制**)来解释DisplayList的构建过程，相信会更加直观, 更易理解DisplayList相关的代码与概念。

# 一、前言

## 1.1 代码环境
本文就是一个很简单的Android sample，onCreate里去inflate activity_main.xml
```
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ...
    }
```
**activity_main.xml**
```
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical">

    <TextView
        android:id="@+id/sample_text"
        android:layout_width="match_parent"
        android:layout_height="60dp"
        android:gravity="center"
        android:textSize="20sp"
        android:text="Hello World!" />
    
    <cc.bobby.debugapp.MyView
        android:layout_width="match_parent"
        android:layout_height="100dp" />
</LinearLayout>
```
而MyView也就是override了 onDraw函数，这个见第二节。

最终的整个View图大致如下所示

![图1 View Hierarchy](http://upload-images.jianshu.io/upload_images/5688445-e554c18b089bae24.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 1.2 updateRootDisplayList的递归调用过程
基于1.1的View Hierarchy的代码调用过程如下所示


![图2 DisplayList构建过程](http://upload-images.jianshu.io/upload_images/5688445-23d13a20080071b7.jpg?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


# 二、MyView的回调 onDraw

> MyView的onDraw(Canvas canvas)回调函数允许开发者在已经获得的Canvas上绘制了， 这些绘制就是直接在显示设备上画图了么？ 当然不是，实际上它仅仅是将绘制命令保存到 DisplayList 里面。

来看下自定义的 MyView中的 onDraw函数
```
protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);

    mPaint.setColor(Color.RED);
    canvas.drawCircle(100, 100, 100, mPaint); //绘制一个圆，圆心(100, 100), 半径100

    canvas.save();
    canvas.translate(250, 0);  //坐标系向右移动250

    mPaint.setColor(Color.GRAY);
    canvas.drawRect(0, 0, 200, 200, mPaint);  //在新的坐标系中画一个200x200的正方形

    mPaint.setColor(Color.YELLOW);

    Path path = new Path();
    path.moveTo(500, 0);
    path.lineTo(700, 0);
    path.lineTo(500, 200);
    path.close();
    canvas.drawPath(path, mPaint);   //在新的坐标系中画一个三角形
    canvas.restore();
}
```

最终绘制出来的图如下MyView所示，一个圆，一个正方形，一个三角形
![图3 MyView](http://upload-images.jianshu.io/upload_images/5688445-ff2b0c6e63c3cc39.JPG?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

那这些绘制命令是怎么保存到DisplayList中的呢？

![图4 Canvas类图](http://upload-images.jianshu.io/upload_images/5688445-90518cb50cecb153.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

如图所示，RenderNode在绘制时会创建一个DisplayListCanvas,而对应于Native的是一个RecordingCanvas(这个HWUI_NEW_OPS宏已经被默认为true了), 这个RecordingCanvas会将后续的绘制命令保存到DisplayList当中， 其中
- mSnapshot: 表示当前的快照，用来记录当前绘制的坐标系
- mFirstSnapshot: 一个初始快照，保存初始化的一些值

**注意:** 一个RenderNode可以有多个Snapshot, 这取决于程序调用 canvas.save的个数，所有的Snapshot通过单链表(*previous)组织起来，表头由 mSnapshot 指定。

## 2.1 没有Canvas.save

正常情况下，如果没有 canvas.save， 所有的绘制都是在mSnapshot中进行
如 MyView 中的 drawCircle
```
    mPaint.setColor(Color.RED);
    canvas.drawCircle(getPivotX(), getPivotY(), getHeight()/2, mPaint);
```
drawCircle在Native层的调用过程如下

```
void RecordingCanvas::drawCircle(float x, float y, float radius, const SkPaint& paint) {
    if (radius <= 0) return;
    drawOval(x - radius, y - radius, x + radius, y + radius, paint);
}

void RecordingCanvas::drawOval(float left, float top, float right, float bottom, const SkPaint& paint) {
    addOp(alloc().create_trivial<OvalOp>(
            Rect(left, top, right, bottom),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            refPaint(&paint)));
}
```
mState.currentSnapshot() 即 mSnapshot，Snapshot中的transform是一个Matrix4的矩阵类，它主要保存当前Snapshot中的 translate/rotate/scale等值， 其实就是坐标系的值。

drawCircle在MyView的(100, 100)位置画一个半径为100的圆圈， 它在RecordingCanvas中表示如下,

![图5 drawCircle](http://upload-images.jianshu.io/upload_images/5688445-cd825d29c5b8e4a7.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 2.2 有canvas.save的情况

接着看下onDraw后面的绘制
```
    canvas.save();
    canvas.translate(250, 0);  //坐标系向右移动250

    mPaint.setColor(Color.GRAY);
    canvas.drawRect(0, 0, 200, 200, mPaint);  //在新的坐标系中画一个200x200的正方形

    mPaint.setColor(Color.YELLOW);

    Path path = new Path();
    path.moveTo(500, 0);
    path.lineTo(700, 0);
    path.lineTo(500, 200);
    path.close();
    canvas.drawPath(path, mPaint);   //在新的坐标系中画一个三角形
    canvas.restore();
```
canvas.save()在Native中使用一个新Snapshot_2来保存后续的绘制，因为canvas可能会有一些translate/scale又或者是rotate的操作， 而这些操作又会导致坐标系的改变，如果直接在当前Snapshot_1中绘制，一旦坐标系变了，那可能会对后续的绘制命令造成意料之外的结果。
接下来我们来看下canvas.save的实现 

```
    public int save() {
        return native_save(mNativeCanvasWrapper, MATRIX_SAVE_FLAG | CLIP_SAVE_FLAG);
    }
```
native_save中第二个参数指明是否将当前的Snapshot_1中的Matrix/clip相关信息保存到新的Snapshot_2中，即是否是基于当前坐标系绘制。而 native_save最终会调用CanvasState的saveSnapshot

```
int CanvasState::saveSnapshot(int flags) {
    mSnapshot = allocSnapshot(mSnapshot, flags);
    return mSaveCount++;
}
Snapshot* CanvasState::allocSnapshot(Snapshot* previous, int savecount) {
    void* memory;
    if (mSnapshotPool) {
        memory = mSnapshotPool;
        mSnapshotPool = mSnapshotPool->previous;
        mSnapshotPoolCount--;
    } else {
        memory = malloc(sizeof(Snapshot));
    }   
    return new (memory) Snapshot(previous, savecount);
}
```
mSnapshotPool是一个Snapshot的内存沲子，因为Java层的DisplayListCanvas是临时绘制，最终都会回收掉，同样native的RecordingCanvas一样，因此为了避免重复的申请/释放内存，索性就不释放，只需重置一下就好， 而Snapshot在一个Canvas的个数取决于canvas.save的调用次数, 尽管对调用次数没有限制，但是防止内存被消耗完，与save对应的restore会释放掉多于10个以上的Snapshot，即一个RecordingCanvas最多保存10个Snapshot内存, 并储存在 mSnapshotPool内存沲子里。

最终的RecordingCanvas绘制后的类图如下所示

![图6 canvas.save](http://upload-images.jianshu.io/upload_images/5688445-81b79c662349c329.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

注意: 事实上 canvas.restore会将 Snapshot_2回收到 mSnapshotPool中，为了方便，这里就不再刻意画出来。
从图中可以看出来，对坐标系的变换比如translate会直接操作Snapshot的transform所指向的Matrix4, 而绘制命令(由RecordedOp表示)如 drawRect/drawPath会保存到DisplayList的ops vector中, 

DisplayList中的chunk表示一组RecordedOp, 它用于记录一组RecordedOp在ops中的位置区域，如图中所示 chunk的beginOpIndex=0, endOpIndex=3, 表示ops[0], ops[1], ops[2]是一组InOder的绘制命令。

在Java层与Chunk相关的两个函数被设置成了hide, 即开发者不能直接调用
```
insertInorderBarrier()
insertReorderBarrier()
```
而这两个函数最终会影响 RecordingCanvas mDeferredBarrierType，最终影响addOp这个函数

以上是MyView在canvas里绘制过程， 下面来看下DisplayList是怎样保存到MyView的RenderNode中的

# 三、MyView保存DisplayList到RenderNode中

MyView的绘制过程
```
MyView.draw(canvas_LinearLayout_2, ViewGroup parent, long drawingTime)   //MyView开始draw, 注意此时传进来还是canvas_LinearLayout_2
    MyView.updateDisplayListIfDirty   //生成canvas_MyView(1200x100)
        draw(canvas_MyView)  //MyView开始draw  此时的canvas: canvas_MyView
            drawBackground(canvas_MyView) //不讨论这个
            onDraw(canvas_MyView)   //回调MyView的onDraw
            onDrawForeground(canvas_MyView) //略过
            
        MyView.mRenderNode.end(canvas_MyView) //MyView的结束recording display list	
	canvas_LinearLayout_2.drawRenderNode(MyView.mRenderNode) //将MyView的DisplayList加入到LinearLayout_2的DisplayList中
```

MyView在updateDisplayListIfDirty函数中会去获得一张Canvas，用来记录绘制命令
```
public RenderNode updateDisplayListIfDirty() {
    ...
    final DisplayListCanvas canvas = renderNode.start(width, height);
    ...
}
```

![图7 RenderNode and Canvas](http://upload-images.jianshu.io/upload_images/5688445-7df0c9940e33444e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

onDraw过程请参考第一节

现在来看下 MyView.mRenderNode.end(canvas_MyView)
```
    public void end(DisplayListCanvas canvas) {
        long displayList = canvas.finishRecording();
        nSetDisplayList(mNativeRenderNode, displayList);
        canvas.recycle(); //将Java层中的canvas回收到sPool中
        mValid = true;  //mValid=true表示RenderNode中DisplayList已经有效了
    }
```
canvas.finishRecording()函数会直接返回native中RecordingCanvas所指示的DisplayList地址
然后通过 nSetDisplayList将DisplayList保存到Native的RenderNode的mStagingDisplayList中, 如下图所示

![图8 将cavnas中的DisplayList保存到MyView的Native RenderNode当中](http://upload-images.jianshu.io/upload_images/5688445-cb7454c4bb0d03d1.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 四、LinearLayout_2保存MyView的DisplayList

第二节仅仅是将DisplayList保存到MyView的RenderNode中了，扩展到一般性，即每个View都有自己的RenderNode, DisplayList, 各个View之间有没有联系? 如果有，那它们是怎样联系起来的呢？

接下来看第二节开始的最后那块代码,
```
canvas_LinearLayout_2.drawRenderNode(MyView.mRenderNode) 
```
canvas_LinearLayout_2即是LinearLayout_2的canvas, 而MyView又是LinearLayout_2的一个子view, 它们之间通过DisplayListCanvas的drawRenderNode 有了相关联系，

```
public void drawRenderNode(RenderNode renderNode) {
    nDrawRenderNode(mNativeCanvasWrapper, renderNode.getNativeDisplayList());
    //mNativeCanvasWrapper指向LinearLayout_2对应的native canvas, 
    //renderNode是MyView的RenderNode, 这里获得的renderNode对应jni中的RenderNode地址
}
```
nDrawRenderNode最终后调用到jni android_view_DisplayListCanvas_drawRenderNode
```
static void android_view_DisplayListCanvas_drawRenderNode(JNIEnv* env,
        jobject clazz, jlong canvasPtr, jlong renderNodePtr) {
    Canvas* canvas = reinterpret_cast<Canvas*>(canvasPtr);    // LinearLayout_2的canvas
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr); //MyView的RenderNode
    canvas->drawRenderNode(renderNode);
}
```
接着来看下drawRenderNode()
```
void RecordingCanvas::drawRenderNode(RenderNode* renderNode) {
    auto&& stagingProps = renderNode->stagingProperties();
    RenderNodeOp* op = alloc().create_trivial<RenderNodeOp>(
            Rect(stagingProps.getWidth(), stagingProps.getHeight()),
            *(mState.currentSnapshot()->transform),
            getRecordedClip(),
            renderNode);
    int opIndex = addOp(op); //加入到DisplayList的ops中
    if (CC_LIKELY(opIndex >= 0)) {
        int childIndex = mDisplayList->addChild(op); //加入到 DisplayList的chirldren中，

        // update the chunk's child indices
        DisplayList::Chunk& chunk = mDisplayList->chunks.back();
        chunk.endChildIndex = childIndex + 1;

        if (renderNode->stagingProperties().isProjectionReceiver()) {
            // use staging property, since recording on UI thread
            mDisplayList->projectionReceiveIndex = opIndex;
        }
    }
}
```
由代码可见，drawRenderNode会将子View的RenderNode封装进一个RenderNodeOp插入到ops中，作为一个绘制命令，这个绘制命令的意思是绘制整个子View, 而非普通的 OvalOp, RectOp。最后也将它插入到 children中，表示是子View(并不是说children里保存的仅仅是子View, 像绘制背景这样的也会保存到 children, 为了简单，就认为children保存的是子View的RenderNode吧).

最后LinearLayout_2绘制完TextView和MyView的UML图如下所示, 这样子，父View与子View的DisplayList就构建起联系了。

![图9 Linearlayout与子View的DisplayList图](http://upload-images.jianshu.io/upload_images/5688445-11b54c1d1f05ac42.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 五、DisplayList的树形图

```
    private void updateRootDisplayList(View view, HardwareDrawCallbacks callbacks) {
        updateViewTreeDisplayList(view); //此处View是DecorView, 更新View Tree的DisplayList
        if (mRootNodeNeedsUpdate || !mRootNode.isValid()) {
            DisplayListCanvas canvas = mRootNode.start(mSurfaceWidth, mSurfaceHeight);
            try {
                final int saveCount = canvas.save();
                canvas.translate(mInsetLeft, mInsetTop);
                callbacks.onHardwarePreDraw(canvas);

                canvas.insertReorderBarrier();
                canvas.drawRenderNode(view.updateDisplayListIfDirty());
                canvas.insertInorderBarrier();

                callbacks.onHardwarePostDraw(canvas);
                canvas.restoreToCount(saveCount);
                mRootNodeNeedsUpdate = false;
            } finally {
                mRootNode.end(canvas);
            }
        }
    }
```
updateViewTreeDisplayList更新了整个UI的树形DisplayList, 此时整个RenderNode头是DecorView,  而在updateViewTreeDisplayList后面的代码中又会将DecorView的RenderNode也就是DisplayList保存到ThreadedRenderer的的RootRenderNode中。

至此整个UI的DisplayList树形图就画完了，盗用[[Android N中UI硬件渲染（hwui）的HWUI_NEW_OPS(基于Android 7.1)](http://blog.csdn.net/jinzhuojun/article/details/54234354)](http://blog.csdn.net/jinzhuojun/article/details/54234354)中的图

![DisplayList树形图](http://upload-images.jianshu.io/upload_images/5688445-20716706c0aa22d9.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
