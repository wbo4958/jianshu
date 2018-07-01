> 本内容基于自己对于代码的理解以及网上大牛的博客参考写的，作为回顾时的参考之用。
# 一、SurfaceFlinger事务处理handleTransaction()

这里为什么要用Transaction(事务)这个词呢？ 我们从数据库事务概念大致可以看出一点端倪, 它的其中一个很重要的信息就是说**"将一组相关操作组合成一个单元去处理"**, 这个有点意思，这个思路也运用在了SurfaceFlinger里，为什么呢？handleTransaction就是处理一次事务，因为每一个Vsync去刷新一次，但是在一个Vsync周期里，Layer中的属性可能改变了，由于Layer的属性有很多，如果改变一个就去触发相关操作，这样显然是浪费资源的事情，所以正确的做法就是当Layer属性的值改变了，然后记录下来，最后在 handleTransaction 一次就处理完了。

事务标志位有三种基本类形(可以是多种组合)

```
enum {
    eTransactionNeeded        = 0x01,  //Layer的属性发生变化了，表示需要处理事务
    eTraversalNeeded          = 0x02,  //遍历的是SurfaceFlinger中所有的Layer
    eDisplayTransactionNeeded = 0x04,  //这个是显示器相关的事务,如显示器hotplug
    eTransactionMask          = 0x07   //掩码
};
```

## 1.1 SurfaceFlinger中几个重要变量

- State mCurrentState
  SurfaceFlinger下一帧的状态

- State mDrawingState
  当前正在绘制的状态，这个是SurfaceFlinger处理完事务后更新出来的状态，是最终的状态

- mVisibleRegionsDirty 
表示当前可见区域是否脏了，如果脏了的话，比如(layer added/removed, Display added/remove相关)在最后合成的时候会对每个屏幕重建layer stack, 但是一般都为false

```
struct State {
    LayerVector layersSortedByZ;  //SurfaceFlinger中所有的按照Z order排序的 Layer
    DefaultKeyedVector< wp<IBinder>, DisplayDeviceState> displays;  //外接的显示屏
};
```

## 1.2 handleTransaction
```
void SurfaceFlinger::handleTransaction(uint32_t transactionFlags)
{
    ATRACE_CALL();

    // here we keep a copy of the drawing state (that is the state that's
    // going to be overwritten by handleTransactionLocked()) outside of
    // mStateLock so that the side-effects of the State assignment
    // don't happen with mStateLock held (which can cause deadlocks).
    //保存 mDrawingState, 但是这里根本没有使用到
    State drawingState(mDrawingState);

    Mutex::Autolock _l(mStateLock);
    const nsecs_t now = systemTime();
    mDebugInTransaction = now;

    // Here we're guaranteed that some transaction flags are set
    // so we can call handleTransactionLocked() unconditionally.
    // We call getTransactionFlags(), which will also clear the flags,
    // with mStateLock held to guarantee that mCurrentState won't change
    // until the transaction is committed.

    //获得SurfaceFlinger中的Transaction标志位
    transactionFlags = getTransactionFlags(eTransactionMask);
    handleTransactionLocked(transactionFlags); //真正处理事务的函数

    mLastTransactionTime = systemTime() - now;
    mDebugInTransaction = 0;
    invalidateHwcGeometry();
    // here the transaction has been committed
}
```

## 1.3 handleTransactionLocked

```
void SurfaceFlinger::handleTransactionLocked(uint32_t transactionFlags)
{
    //获得当前所有Layer
    const LayerVector& currentLayers(mCurrentState.layersSortedByZ);
    const size_t count = currentLayers.size();

    // Notify all layers of available frames
    for (size_t i = 0; i < count; ++i) {
        //检查当前Frame是否是SyncPoint需要的(也就说之前想延迟的Buffer已经到来了), 如果是，则设置SyncPoint中mFrameIsAvailable为true
        currentLayers[i]->notifyAvailableFrames();
    }

    /*
     * Traversal of the children
     * (perform the transaction for each of them if needed)
     */
    //遍历所有的Layer, 让Layer去执行自己的事务
    if (transactionFlags & eTraversalNeeded) {
        for (size_t i=0 ; i<count ; i++) {
            const sp<Layer>& layer(currentLayers[i]);
            // 获得Layer的Transaction flags
            uint32_t trFlags = layer->getTransactionFlags(eTransactionNeeded);
            if (!trFlags) continue;
            // Layer处理自己的事务
            const uint32_t flags = layer->doTransaction(0);
            if (flags & Layer::eVisibleRegion) 
                //如果Layer的可见区域改变了，则SurfaceFlinger就标注出当前可视区域改变了
                mVisibleRegionsDirty = true;
        }
    }

    /*
     * Perform display own transactions if needed
     */
    //跟显示器相关的事务，比如hotplug显示屏, 初始化/销除显示屏，显示屏的配置变化，
    if (transactionFlags & eDisplayTransactionNeeded) {
    }

    if (transactionFlags & (eTraversalNeeded|eDisplayTransactionNeeded)) {
        // The transform hint might have changed for some layers
        // (either because a display has changed, or because a layer
        // as changed).
        //
        // Walk through all the layers in currentLayers,
        // and update their transform hint.
        //
        // If a layer is visible only on a single display, then that
        // display is used to calculate the hint, otherwise we use the
        // default display.
        //
        // NOTE: we do this here, rather than in rebuildLayerStacks() so that
        // the hint is set before we acquire a buffer from the surface texture.
        //
        // NOTE: layer transactions have taken place already, so we use their
        // drawing state. However, SurfaceFlinger's own transaction has not
        // happened yet, so we must use the current state layer list
        // (soon to become the drawing state list).
        //
        sp<const DisplayDevice> disp;
        uint32_t currentlayerStack = 0;
        for (size_t i=0; i<count; i++) {
            // NOTE: we rely on the fact that layers are sorted by
            // layerStack first (so we don't have to traverse the list
            // of displays for every layer).
            const sp<Layer>& layer(currentLayers[i]);
            //获得Layer的 layer stack
            uint32_t layerStack = layer->getDrawingState().layerStack;
            
            //通过遍历所有的Display来找到Layer所在的显示屏
            if (i==0 || currentlayerStack != layerStack) {
                currentlayerStack = layerStack;
                // figure out if this layerstack is mirrored
                // (more than one display) if so, pick the default display,
                // if not, pick the only display it's on.
                disp.clear();
                for (size_t dpy=0 ; dpy<mDisplays.size() ; dpy++) {
                    sp<const DisplayDevice> hw(mDisplays[dpy]);
                    if (hw->getLayerStack() == currentlayerStack) {
                        if (disp == NULL) {
                            disp = hw;
                        } else {
                            disp = NULL;
                            break;
                        }
                    }
                }
            }
            if (disp == NULL) {
                // NOTE: TEMPORARY FIX ONLY. Real fix should cause layers to
                // redraw after transform hint changes. See bug 8508397.

                // could be null when this layer is using a layerStack
                // that is not visible on any display. Also can occur at
                // screen off/on times.
                disp = getDefaultDisplayDevice();
            }
            //更新Layer的旋转方向，最终会体现在 BufferQueueCore中的mTransformHint变量
            layer->updateTransformHint(disp);
        }
    }

    /*
     * Perform our own transaction if needed
     */
    //前面都是执行的Layer相关的事务
    //下面就是执行SurfaceFlinger自己的事务
    const LayerVector& layers(mDrawingState.layersSortedByZ);
    if (currentLayers.size() > layers.size()) {
        // layers have been added , 有新的Layer加入
        mVisibleRegionsDirty = true;
    }

    // some layers might have been removed, so
    // we need to update the regions they're exposing.
    //下面有Layer移出了的情况
    if (mLayersRemoved) {
        mLayersRemoved = false;
        mVisibleRegionsDirty = true;
        const size_t count = layers.size();
        for (size_t i=0 ; i<count ; i++) {
            const sp<Layer>& layer(layers[i]);
            if (currentLayers.indexOf(layer) < 0) {
                // this layer is not visible anymore
                // TODO: we could traverse the tree from front to back and
                //       compute the actual visible region
                // TODO: we could cache the transformed region
                const Layer::State& s(layer->getDrawingState());
                //获得移除的Layer的可见区域, 这块可见区域就是dirty的
                Region visibleReg = s.active.transform.transform(
                        Region(Rect(s.active.w, s.active.h)));
                //找到被移除掉的Layer所在的Display, 然后更新Diplay的dirty 区域，也就是对region做或运算
                invalidateLayerStack(s.layerStack, visibleReg);
            }
        }
    }

    //提交事务
    commitTransaction();
    //更新Display中光标的位置
    updateCursorAsync();
}
```
## 1.4 commitTransaction
```
void SurfaceFlinger::commitTransaction()
{
    //mLayersPendingRemoval是保存的是pending 着需要移除的Layer. 比如APP调用destroySurface
    if (!mLayersPendingRemoval.isEmpty()) { 
        // Notify removed layers now that they can't be drawn from
        for (size_t i = 0; i < mLayersPendingRemoval.size(); i++) {
            mLayersPendingRemoval[i]->onRemoved(); //回调 onRemoved， 调用Consumer的consumerDisconnect，清理BufferQueueBuffer
        }    
        mLayersPendingRemoval.clear();
    }    

    // If this transaction is part of a window animation then the next frame
    // we composite should be considered an animation as well.
    mAnimCompositionPending = mAnimTransactionPending;
    //更新 mDrawingState
    mDrawingState = mCurrentState;
    mTransactionPending = false;
    mAnimTransactionPending = false;
    //释放mTransactionCV, 如果SurfaceFlinger正在处理事务，而这时如果调用setTransactionState就可能会一直等着mTransactionCV, 
    //因为setTransactionState可能会改变SurfaceFlinger的Transaction标志位，导致前后不一致
    mTransactionCV.broadcast();
}
```

# 二、Layer处理自己的事务

## 2.1 Layer中重要变量

- sp<NativeHandle> mSidebandStream;  //sideband buffer stream

- std::list<std::shared_ptr<SyncPoint>> mLocalSyncPoints; 
  //一系列的SyncPoint, 这些SyncPoint是在特定的Frame来了才更新

- State mCurrentState;
  表示Layer下一帧的属性状态，当某个属性变化时，直接操作该变量

- State mDrawingState;  
  表示当前正在绘制的帧的属性状态。Layer处理完事务后，最终的用于绘制的状态

```
struct State {
    Geometry active;  //当前Layer的可见区域
    Geometry requested; //请求的Layer的可见区域, 在Layer做doTransaction时会将 requested赋值给active. setSize/setMatrix/setPosition
    uint32_t z;
    uint32_t layerStack; //layerStack指明当前Layer属于哪个Display，Display的layer stack可以用 hw->getLayerStack获得
    uint8_t alpha;
    uint8_t flags;
    uint8_t mask;
    uint8_t reserved[2];
    int32_t sequence; //当Layer的属性变化时， sequence就会加1
    bool modified;  //当Layer的属性变化了，该变量就会被置为true

    Rect crop;  
    Rect finalCrop; 
    // If set, defers this state update until the Layer identified by handle
    // receives a frame with the given frameNumber
    sp<IBinder> handle; //延迟更新特定的Frame
    uint64_t frameNumber; //这个就是Handle要延迟的Frame号

    // the transparentRegion hint is a bit special, it's latched only
    // when we receive a buffer -- this is because it's "content"
    // dependent.
    Region activeTransparentRegion;
    Region requestedTransparentRegion;
};

struct Geometry {
    uint32_t w;
    uint32_t h;
    Transform transform; //Geometry的传输矩阵
};
```
## 2.2 doTransaction
```
// Layer处理的事务
uint32_t Layer::doTransaction(uint32_t flags) {
    ATRACE_CALL();

    pushPendingState(); //将mCurrentState保存到mPendingStates中 
  //获得Layer的mCurrentState, 这里为什么不直接用mCurrentState呢？是因为防止mCurrentState在处理的时候被其它线程给改变了
    Layer::State c = getCurrentState(); 
    if (!applyPendingStates(&c)) { //针对 SyncPoints相关
        return 0;
    }    

    //获得上一次的Drawing state
    const Layer::State& s(getDrawingState());

    //如果requested的可见区域与旧的可见区域不同了，则size changed
    const bool sizeChanged = (c.requested.w != s.requested.w) ||
                             (c.requested.h != s.requested.h);

    if (sizeChanged) {
        // 如果size changed, 把新的 w|h 设置到 BufferQueueCore中的 mDefaultWidth|mDefaultHeight中去
        mSurfaceFlingerConsumer->setDefaultBufferSize(
                c.requested.w, c.requested.h);
    }

    //如果新的请求的 w|h 与新的原本要显示的区域不同，表明是  resize了
    const bool resizePending = (c.requested.w != c.active.w) ||
            (c.requested.h != c.active.h);
    if (!isFixedSize()) {
        if (resizePending && mSidebandStream == NULL) {
            //resize只发生在非固定尺寸模式，并且sideband layer(sideband buffer stream)为空的情况
            // don't let Layer::doTransaction update the drawing state
            // if we have a pending resize, unless we are in fixed-size mode.
            // the drawing state will be updated only once we receive a buffer
            // with the correct size.
            //
            // in particular, we want to make sure the clip (which is part
            // of the geometry state) is latched together with the size but is
            // latched immediately when no resizing is involved.
            //
            // If a sideband stream is attached, however, we want to skip this
            // optimization so that transactions aren't missed when a buffer
            // never arrives

            flags |= eDontUpdateGeometryState;
        }
    }

    // always set active to requested, unless we're asked not to
    // this is used by Layer, which special cases resizes.
    if (flags & eDontUpdateGeometryState)  { //这个是在resize里面做的，
    } else {
        //这里是允许更新 可见区域
        Layer::State& editCurrentState(getCurrentState());
        if (mFreezePositionUpdates) {
            float tx = c.active.transform.tx();
            float ty = c.active.transform.ty();
            c.active = c.requested;
            c.active.transform.set(tx, ty);
            editCurrentState.active = c.active;
        } else {
            editCurrentState.active = editCurrentState.requested;
            c.active = c.requested;
        }
    }

    if (s.active != c.active) {
        // invalidate and recompute the visible regions if needed
        flags |= Layer::eVisibleRegion;
    }

    // 只要Layer有属性发生变化了，sequence就会加1,这样可以很直观判断是否当前的state和旧的state是否发生变化了
    // 但是这样并不能保证sequence相同，但是属性变化的这种情况
    if (c.sequence != s.sequence) {
        // invalidate and recompute the visible regions if needed
        flags |= eVisibleRegion;
        this->contentDirty = true;

        // we may use linear filtering, if the matrix scales us
        const uint8_t type = c.active.transform.getType();
        mNeedsFiltering = (!c.active.transform.preserveRects() ||
                (type >= Transform::SCALE));
    }

    // If the layer is hidden, signal and clear out all local sync points so
    // that transactions for layers depending on this layer's frames becoming
    // visible are not blocked
    //是否Layer是hide的
    if (c.flags & layer_state_t::eLayerHidden) {
        Mutex::Autolock lock(mLocalSyncPointMutex);
        for (auto& point : mLocalSyncPoints) {
            point->setFrameAvailable();
        }
        mLocalSyncPoints.clear();
    }

    // Commit the transaction
    //提交事务
    commitTransaction(c);
    return flags;
}  
```
## 2.3 pushPendingState
```
void Layer::pushPendingState() {
    //只要Layer的属性改变了，都会将modified置为true
    if (!mCurrentState.modified) {
        return;
    }
    
    // If this transaction is waiting on the receipt of a frame, generate a sync
    // point and send it to the remote layer.
    if (mCurrentState.handle != nullptr) { //表示当前需要等着特定的frame(frame number号标识)
        sp<Handle> handle = static_cast<Handle*>(mCurrentState.handle.get());
        sp<Layer> handleLayer = handle->owner.promote();
        if (handleLayer == nullptr) { 
            ALOGE("[%s] Unable to promote Layer handle", mName.string());
            // If we can't promote the layer we are intended to wait on,
            // then it is expired or otherwise invalid. Allow this transaction
            // to be applied as per normal (no synchronization).
            mCurrentState.handle = nullptr;
        } else {
            //创建一个 SyncPoint， 表示只在 frameNumber的这个Frame才更新 
            auto syncPoint = std::make_shared<SyncPoint>(
                    mCurrentState.frameNumber);
            if (handleLayer->addSyncPoint(syncPoint)) { //加入到 mLocalSyncPoints
                mRemoteSyncPoints.push_back(std::move(syncPoint));
            } else {
                // We already missed the frame we're supposed to synchronize
                // on, so go ahead and apply the state update
                mCurrentState.handle = nullptr;
            }
        }

        // Wake us up to check if the frame has been received
        setTransactionFlags(eTransactionNeeded);
    }
    //将当前的状态保存在mPendingStates, 接下来会处理到
    mPendingStates.push_back(mCurrentState);
}

//提交事务， 也就是更新 Layer的 mDrawingState
void Layer::commitTransaction(const State& stateToCommit) {
    mDrawingState = stateToCommit;
}
```
