> 本内容基于自己对于代码的理解以及网上大牛的博客参考写的，作为回顾时的参考之用。

# 1. handlePageFlip
```
bool SurfaceFlinger::handleMessageInvalidate() {
    ATRACE_CALL();
    return handlePageFlip();
}
```

page flip 意思是翻页

```
bool SurfaceFlinger::handlePageFlip()
{
    Region dirtyRegion;
    nsecs_t pageFlipTimestamp = systemTime();
    mAnimFrameTracker.setPageFlipTime(pageFlipTimestamp);

    bool visibleRegions = false;
    const LayerVector& layers(mDrawingState.layersSortedByZ);
    bool frameQueued = false;

#ifdef USE_PHS
    mAdd->phsHints.onPreHandlePageFlip();
#endif

    // Store the set of layers that need updates. This set must not change as
    // buffers are being latched, as this could result in a deadlock.
    // Example: Two producers share the same command stream and:
    // 1.) Layer 0 is latched
    // 2.) Layer 0 gets a new frame
    // 2.) Layer 1 gets a new frame
    // 3.) Layer 1 is latched.
    // Display is now waiting on Layer 1's frame, which is behind layer 0's
    // second frame. But layer 0's second frame could be waiting on display.
    Vector<Layer*> layersWithQueuedFrames;
    for (size_t i = 0, count = layers.size(); i<count ; i++) {
        const sp<Layer>& layer(layers[i]);
        if (layer->hasQueuedFrame()) {//该Layer是否有 QUEUED buffer
            frameQueued = true;
            layer->decrementSwapInterval(); //一个vsync来了，就表示需要swap一次。 Consumer的mSwapInterval如果大于0就减1
            if (layer->shouldPresentNow(mPrimaryDispSync)) { //是否需要显示该Layer
                layersWithQueuedFrames.push_back(layer.get());
            } else {
                layer->useEmptyDamage();//如果不需要显示，则该Layer的脏区域为empty
            }
        } else {
            layer->useEmptyDamage();
        }
#ifdef USE_PHS
        mAdd->phsHints.sendHintsPreLatch(layer);
#endif
    }

    bool layerHadDirtyRegion = false;
    //layersWithQueuedFrames保存需要进行合成的Layer
    for (size_t i = 0, count = layersWithQueuedFrames.size() ; i<count ; i++) {
        Layer* layer = layersWithQueuedFrames[i];
        //计算Layer的脏区域
        const Region dirty(layer->latchBuffer(visibleRegions, pageFlipTimestamp));
        layer->useSurfaceDamage();
        layerHadDirtyRegion |= !dirty.isEmpty();
        const Layer::State& s(layer->getDrawingState());
        //修改Layer所在的Display上的脏区域
        invalidateLayerStack(s.layerStack, dirty);
#ifdef USE_PHS
        mAdd->phsHints.sendHintsPostLatch(layer);
#endif
    }

    mVisibleRegionsDirty |= visibleRegions;

    if (layerHadDirtyRegion) {
        mDebugHandlePageFlipHadDirtyRegionCount++;
    }

    // If we will need to wake up at some time in the future to deal with a
    // queued frame that shouldn't be displayed during this vsync period, wake
    // up during the next vsync period to check again.
    if (frameQueued && layersWithQueuedFrames.empty()) {
        //如果Layer中有QUEUED的帧，但是在该VSYNC检查出来不需要显示Layer, 那么说明QUEUED的帧要显示的条件还不满足，则触发下一个 VSYNC
        signalLayerUpdate();
    }

    // Only continue with the refresh if there is actually new work to do
    // 返回值确定是否需要刷新显示
    return !layersWithQueuedFrames.empty();
}
```
# 2. shouldPresentNow
```
bool Layer::shouldPresentNow(const DispSync& dispSync) const {
    //如果是自动刷新，或是sideband buffer(Tv相关的)发生变化了, mSidebandStreamChanged主要是通过setSidebandStream改变的，
    if (mSidebandStreamChanged || mAutoRefresh) {
        return true;
    }    

    // 如果swap的间隔还大于0，表示还需多个VSYNC，则不需要刷新
    if (mSurfaceFlingerConsumer->getSwapInterval() > 0) { 
        return false;
    }    

    Mutex::Autolock lock(mQueueItemLock);
    //Layer都没有Queued的frame，就不需要显示了
    if (mQueueItems.empty()) {
        return false;
    }
    
    //第一个QUEUED的帧刷新时间
    auto timestamp = mQueueItems[0].mTimestamp;
    nsecs_t expectedPresent =
            mSurfaceFlingerConsumer->computeExpectedPresent(dispSync);

    // Ignore timestamps more than a second in the future
    //如果第一个queued的frame在未来1s之内显示，这个是比较可信的，
    //反之，一个frame要求在1S后显示，这个可能就有问题，所以此时就让它显示
    bool isPlausible = timestamp < (expectedPresent + s2ns(1));

    //如果超时了赶紧刷新出来
    bool isDue = timestamp < expectedPresent;
    return isDue || !isPlausible;
}
```

# 3. latchBuffer
**Layer.cpp**
```
Region Layer::latchBuffer(bool& recomputeVisibleRegions, nsecs_t pageFlipTimestamp)
{
    ATRACE_CALL();

    //如果sideband surface改变了，表示该layer是sideband buffer了， 然后将 mSidebandStreamChanged置为false
    if (android_atomic_acquire_cas(true, false, &mSidebandStreamChanged) == 0) {
        // mSidebandStreamChanged was true
        mSidebandStream = mSurfaceFlingerConsumer->getSidebandStream();
        if (mSidebandStream != NULL) {
            setTransactionFlags(eTransactionNeeded);
            mFlinger->setTransactionFlags(eTraversalNeeded);
        }
        // sideband的情况，需要重新 计算可视区域
        recomputeVisibleRegions = true;

        const State& s(getDrawingState());
        return s.active.transform.transform(Region(Rect(s.active.w, s.active.h)));
    }

    Region outDirtyRegion;
    //当前Layer中有QUEUED的帧，或者Layer是自动刷新的情况
    if (mQueuedFrames > 0 || mAutoRefresh) {

        // if we've already called updateTexImage() without going through
        // a composition step, we have to skip this layer at this point
        // because we cannot call updateTeximage() without a corresponding
        // compositionComplete() call.
        // we'll trigger an update in onPreComposition().
        if (mRefreshPending) {
            return outDirtyRegion;
        }

        // Capture the old state of the layer for comparisons later
        const State& s(getDrawingState());
        const bool oldOpacity = isOpaque(s);
        sp<GraphicBuffer> oldActiveBuffer = mActiveBuffer;

        ...
        
        //生成一个Reject
        Reject r(mDrawingState, getCurrentState(), recomputeVisibleRegions,
                getProducerStickyTransform() != 0, mName.string(),
                mOverrideScalingMode);


        // Check all of our local sync points to ensure that all transactions
        // which need to have been applied prior to the frame which is about to
        // be latched have signaled
        //获得QUEUED队列中第一个Frame号
        auto headFrameNumber = getHeadFrameNumber();
        bool matchingFramesFound = false;
        bool allTransactionsApplied = true;
        {
            //看下延迟的buffer是否到了延迟点了，(根据frame number来判断)
            Mutex::Autolock lock(mLocalSyncPointMutex);
            for (auto& point : mLocalSyncPoints) {
                //还不是defer的那个Frame
                if (point->getFrameNumber() > headFrameNumber) {
                    break;
                }
                //代码走到这里，说明Defer的那个Frame已经来了
                matchingFramesFound = true;

                if (!point->frameIsAvailable()) { //这个在Layer处理事务中已经设置过了
                    // We haven't notified the remote layer that the frame for
                    // this point is available yet. Notify it now, and then
                    // abort this attempt to latch.
                    point->setFrameAvailable();
                    allTransactionsApplied = false;
                    break;
                }

                allTransactionsApplied &= point->transactionIsApplied();
            }
        }
        //DEFER的帧已经到来，但是还没有apply transaction (这个发生在applyPendingStates), 
        //这时需要触发下一个VSYNC，先去apply transaction
        if (matchingFramesFound && !allTransactionsApplied) {
            mFlinger->signalLayerUpdate(); 
            return outDirtyRegion;
        }
        // This boolean is used to make sure that SurfaceFlinger's shadow copy
        // of the buffer queue isn't modified when the buffer queue is returning
        // BufferItem's that weren't actually queued. This can happen in shared
        // buffer mode.
        bool queuedBuffer = false;
        
        status_t updateResult = mSurfaceFlingerConsumer->updateTexImage(&r,
                mFlinger->mPrimaryDispSync, &mAutoRefresh, &queuedBuffer,
                mLastFrameNumberReceived);
        if (updateResult == BufferQueue::PRESENT_LATER) {
            // Producer doesn't want buffer to be displayed yet.  Signal a
            // layer update so we check again at the next opportunity.
            // 延迟显示，触发下一个VSYNC， 即Buffer显示的时间还没到
            mFlinger->signalLayerUpdate();
            return outDirtyRegion;
        } else if (updateResult == SurfaceFlingerConsumer::BUFFER_REJECTED) {
            // If the buffer has been rejected, remove it from the shadow queue
            // and return early
            // Buffer被Reject掉的情况
            if (queuedBuffer) {
                Mutex::Autolock lock(mQueueItemLock);
                mQueueItems.removeAt(0); //从Layer的 mQueueItems列队中移除
                android_atomic_dec(&mQueuedFrames);
            }
            return outDirtyRegion;
        } else if (updateResult != NO_ERROR || mUpdateTexImageFailed) {
            // This can occur if something goes wrong when trying to create the
            // EGLImage for this buffer. If this happens, the buffer has already
            // been released, so we need to clean up the queue and bug out
            // early.
            // 创建 EGLImage 失败的情况
            if (queuedBuffer) {
                Mutex::Autolock lock(mQueueItemLock);
                mQueueItems.clear();
                android_atomic_and(0, &mQueuedFrames);
            }

            // Once we have hit this state, the shadow queue may no longer
            // correctly reflect the incoming BufferQueue's contents, so even if
            // updateTexImage starts working, the only safe course of action is
            // to continue to ignore updates.
            mUpdateTexImageFailed = true;

            return outDirtyRegion;
        }

        //queuedBuffer在非buffer shared模式时为true
        if (queuedBuffer) {
            // Autolock scope
            //这个是GLConsumer里的Frame number号， 在updateAndReleaseLocked里赋值, 即当前需要显示的Frame
            auto currentFrameNumber = mSurfaceFlingerConsumer->getFrameNumber();

            Mutex::Autolock lock(mQueueItemLock);

            // Remove any stale buffers that have been dropped during
            // updateTexImage
            //马上要显示的Frame号是currentFrameNumber, 那么之前QUEUED的frame就过期了，直接移除掉
            while (mQueueItems[0].mFrameNumber != currentFrameNumber) {
                //移除掉过时的 Buffer
                mQueueItems.removeAt(0);
                android_atomic_dec(&mQueuedFrames);
            }
            //现在第一个显示frame了
            mQueueItems.removeAt(0);
        }


        // Decrement the queued-frames count.  Signal another event if we
        // have more frames pending.
        if ((queuedBuffer && android_atomic_dec(&mQueuedFrames) > 1)
                || mAutoRefresh) {
            //如果还有Queued的Buffer，那么通知 SurfaceFlinger在下一个VSYNC时进行更新
            mFlinger->signalLayerUpdate();
        }

        // Trigger another wake up to decrement our swap interval
        if (mSurfaceFlingerConsumer->getSwapInterval() > 1) {
            //如果 swap 间隔还大于1， 表示还需要多个vsync还能显示该buffer，触发 VSYNC
            mFlinger->signalLayerUpdate();
        }

        if (updateResult != NO_ERROR) {
            // something happened!
            //异常发生
            recomputeVisibleRegions = true;
            return outDirtyRegion;
        }

        // update the active buffer
        //mActiveBuffer表示马上要显示的buffer,
        mActiveBuffer = mSurfaceFlingerConsumer->getCurrentBuffer();
        if (mActiveBuffer == NULL) {
            // this can only happen if the very first buffer was rejected.
            return outDirtyRegion;
        }

        //即将进入 refresh的阶段
        mRefreshPending = true;
        mFrameLatencyNeeded = true;
        mFrameTracker.setPageFlipTime(pageFlipTimestamp);
        if (oldActiveBuffer == NULL) {
             // the first time we receive a buffer, we need to trigger a
             // geometry invalidation.
             //如果是第一次接收到Buffer, 需要重新计算可视区域
            recomputeVisibleRegions = true;
         }

        Rect crop(mSurfaceFlingerConsumer->getCurrentCrop());
        const uint32_t transform(mSurfaceFlingerConsumer->getCurrentTransform());
        const uint32_t scalingMode(mSurfaceFlingerConsumer->getCurrentScalingMode());
        if ((crop != mCurrentCrop) ||
            (transform != mCurrentTransform) ||
            (scalingMode != mCurrentScalingMode))
        {
            //保存最新的 crop 与transform 这些变量
            mCurrentCrop = crop;
            mCurrentTransform = transform;
            mCurrentScalingMode = scalingMode;
            recomputeVisibleRegions = true;
        }

        if (oldActiveBuffer != NULL) {
            uint32_t bufWidth  = mActiveBuffer->getWidth();
            uint32_t bufHeight = mActiveBuffer->getHeight();
            if (bufWidth != uint32_t(oldActiveBuffer->width) ||
                bufHeight != uint32_t(oldActiveBuffer->height)) {
                recomputeVisibleRegions = true; //最新的buffer和上一个渲染的buffer的尺寸不一样的，这时需要重新计算可视化区域
                mFreezePositionUpdates = false;
            }
        }
        //透明度相关
        mCurrentOpacity = getOpacityForFormat(mActiveBuffer->format);
        if (oldOpacity != isOpaque(s)) {
            recomputeVisibleRegions = true;
        }

        mCurrentFrameNumber = mSurfaceFlingerConsumer->getFrameNumber();

        // Remove any sync points corresponding to the buffer which was just
        // latched
        {
            Mutex::Autolock lock(mLocalSyncPointMutex);
            auto point = mLocalSyncPoints.begin();
            while (point != mLocalSyncPoints.end()) {
                if (!(*point)->frameIsAvailable() ||
                        !(*point)->transactionIsApplied()) {
                    // This sync point must have been added since we started
                    // latching. Don't drop it yet.
                    ++point;
                    continue;
                }
                // mCurrentFrameNumber是即将要显示的Frame，如果要显示的Frame已经超过了DEFER的帧，则移除掉sync point
                if ((*point)->getFrameNumber() <= mCurrentFrameNumber) {
                    point = mLocalSyncPoints.erase(point);
                } else {
                    ++point;
                }
            }
        }

        //计算出脏区域
        // FIXME: postedRegion should be dirty & bounds
        Region dirtyRegion(Rect(s.active.w, s.active.h));

        // transform the dirty region to window-manager space
        outDirtyRegion = (s.active.transform.transform(dirtyRegion));
    }
    return outDirtyRegion;
}
```
# 4. updateTexImage
```
status_t SurfaceFlingerConsumer::updateTexImage(BufferRejecter* rejecter,
        const DispSync& dispSync, bool* autoRefresh, bool* queuedBuffer,
        uint64_t maxFrameNumber)
{
    ATRACE_CALL();
    ALOGV("updateTexImage");
    Mutex::Autolock lock(mMutex);

    if (mAbandoned) {
        return NO_INIT;
    }

    // Make sure the EGL state is the same as in previous calls.
    // egl环境检查
    status_t err = checkAndUpdateEglStateLocked();
    if (err != NO_ERROR) {
        return err;
    }

    BufferItem item;

    // Acquire the next buffer.
    // In asynchronous mode the list is guaranteed to be one buffer
    // deep, while in synchronous mode we use the oldest buffer.
    //获得要显示出来的Buffer
    // maxFrameNumber是 Layer onFrameAvailable/onFrameReplaced 里的那个Frame号，也就是Layer中的mLastFrameNumberReceived
    err = acquireBufferLocked(&item, computeExpectedPresent(dispSync),
            maxFrameNumber);
    if (err != NO_ERROR) {
        if (err == BufferQueue::NO_BUFFER_AVAILABLE) {
            err = NO_ERROR;
        } else if (err == BufferQueue::PRESENT_LATER) {
            // return the error, without logging
        } else {
            ALOGE("updateTexImage: acquire failed: %s (%d)",
                strerror(-err), err);
        }
        return err;
    }

    // We call the rejecter here, in case the caller has a reason to
    // not accept this buffer.  This is used by SurfaceFlinger to
    // reject buffers which have the wrong size
    int slot = item.mSlot;
    // 检查是否需要 reject buffer
    if (rejecter && rejecter->reject(mSlots[slot].mGraphicBuffer, item)) {
        releaseBufferLocked(slot, mSlots[slot].mGraphicBuffer, EGL_NO_SYNC_KHR);
        return BUFFER_REJECTED;
    }

    if (autoRefresh) {
        *autoRefresh = item.mAutoRefresh;
    }

    // mQueuedBuffer 表示是否该Buffer还被Producer queued, 在shared buffer模式才为false
    if (queuedBuffer) {
        *queuedBuffer = item.mQueuedBuffer;
    }

    // release 旧的buffer, 创建GLConsumer里新的EGLImage
    err = updateAndReleaseLocked(item);
    if (err != NO_ERROR) {
        return err;
    }

    if (!SyncFeatures::getInstance().useNativeFenceSync()) {
        // Bind the new buffer to the GL texture.
        //
        // Older devices require the "implicit" synchronization provided
        // by glEGLImageTargetTexture2DOES, which this method calls.  Newer
        // devices will either call this in Layer::onDraw, or (if it's not
        // a GL-composited layer) not at all.
        err = bindTextureImageLocked();
    }

    if (err == NO_ERROR) {
        mSwapInterval = item.mInterval == 1 ? 0 : (item.mInterval+2)>>1;
    }

    return err;
}
```
# 5. acquireBuffer
```
status_t BufferQueueConsumer::acquireBuffer(BufferItem* outBuffer,
        nsecs_t expectedPresent, uint64_t maxFrameNumber) {
    ATRACE_CALL();

    int numDroppedBuffers = 0;
    sp<IProducerListener> listener;
    {
        Mutex::Autolock lock(mCore->mMutex);

        // Check that the consumer doesn't currently have the maximum number of
        // buffers acquired. We allow the max buffer count to be exceeded by one
        // buffer so that the consumer can successfully set up the newly acquired
        // buffer before releasing the old one.
        int numAcquiredBuffers = 0;
        for (int s : mCore->mActiveBuffers) { //统计mActiveBuffers中已经是 ACQUIRED 的Frame 个数
            if (mSlots[s].mBufferState.isAcquired()) { //是否已经是 ACQUIRED 的Buffer
                ++numAcquiredBuffers;
            }
        }
        //异常检测 
        if (numAcquiredBuffers >= mCore->mMaxAcquiredBufferCount + 1) {
            return INVALID_OPERATION;
        }

        bool sharedBufferAvailable = mCore->mSharedBufferMode &&
                mCore->mAutoRefresh && mCore->mSharedBufferSlot !=
                BufferQueueCore::INVALID_BUFFER_SLOT;

        // In asynchronous mode the list is guaranteed to be one buffer deep,
        // while in synchronous mode we use the oldest buffer.
        //既不是shared buffer mode，而且也没有Queued 的buffer
        if (mCore->mQueue.empty() && !sharedBufferAvailable) {
            return NO_BUFFER_AVAILABLE;
        }

        //queue中的第一个 buffer
        BufferQueueCore::Fifo::iterator front(mCore->mQueue.begin());

        // If expectedPresent is specified, we may not want to return a buffer yet.
        // If it's specified and there's more than one buffer queued, we may want
        // to drop a buffer.
        // Skip this if we're in shared buffer mode and the queue is empty,
        // since in that case we'll just return the shared buffer.
        if (expectedPresent != 0 && !mCore->mQueue.empty()) {
            const int MAX_REASONABLE_NSEC = 1000000000ULL; // 1 second

            // The 'expectedPresent' argument indicates when the buffer is expected
            // to be presented on-screen. If the buffer's desired present time is
            // earlier (less) than expectedPresent -- meaning it will be displayed
            // on time or possibly late if we show it as soon as possible -- we
            // acquire and return it. If we don't want to display it until after the
            // expectedPresent time, we return PRESENT_LATER without acquiring it.
            //
            // To be safe, we don't defer acquisition if expectedPresent is more
            // than one second in the future beyond the desired present time
            // (i.e., we'd be holding the buffer for a long time).
            //
            // NOTE: Code assumes monotonic time values from the system clock
            // are positive.

            // Start by checking to see if we can drop frames. We skip this check if
            // the timestamps are being auto-generated by Surface. If the app isn't
            // generating timestamps explicitly, it probably doesn't want frames to
            // be discarded based on them.
            // 检查后面的 Queue, 找到最近需要显示的Buffer,
            while (mCore->mQueue.size() > 1 && !mCore->mQueue[0].mIsAutoTimestamp) {
                const BufferItem& bufferItem(mCore->mQueue[1]);

                // If dropping entry[0] would leave us with a buffer that the
                // consumer is not yet ready for, don't drop it.
                // maxFrameNumber是这次REFERSH中Layer收到的最近的那个FRAME, 
               // 但是这个BUFFERITEM还在后面才显示，所以说明后面的Buffer都还没过期
                // Consumer 还没有准备好，没必要把上一个buffer drop掉，break掉，显示上一个buffer
                if (maxFrameNumber && bufferItem.mFrameNumber > maxFrameNumber) {
                    break;
                }
                // If entry[1] is timely, drop entry[0] (and repeat). We apply an
                // additional criterion here: we only drop the earlier buffer if our
                // desiredPresent falls within +/- 1 second of the expected present.
                // Otherwise, bogus desiredPresent times (e.g., 0 or a small
                // relative timestamp), which normally mean "ignore the timestamp
                // and acquire immediately", would cause us to drop frames.
                //
                // We may want to add an additional criterion: don't drop the
                // earlier buffer if entry[1]'s fence hasn't signaled yet.
                nsecs_t desiredPresent = bufferItem.mTimestamp;
                //如果这个buffer显示的时间在expected之后，并在将来的1s内会，那么也没有drop掉上一个. 
                // break掉直接显示上一个buffer
                if (desiredPresent < expectedPresent - MAX_REASONABLE_NSEC ||
                        desiredPresent > expectedPresent) {
                    // This buffer is set to display in the near future, or
                    // desiredPresent is garbage. Either way we don't want to drop
                    // the previous buffer just to get this on the screen sooner.
                    break;
                }
                
                //没有过期的Buffer, 一般disconnect后才会过期
                if (!front->mIsStale) {
                    // Front buffer is still in mSlots, so mark the slot as free
                    mSlots[front->mSlot].mBufferState.freeQueued(); //将会从queue里删除

                    // After leaving shared buffer mode, the shared buffer will
                    // still be around. Mark it as no longer shared if this
                    // operation causes it to be free.
                    if (!mCore->mSharedBufferMode &&
                            mSlots[front->mSlot].mBufferState.isFree()) {
                        mSlots[front->mSlot].mBufferState.mShared = false;
                    }

                    // Don't put the shared buffer on the free list
                    if (!mSlots[front->mSlot].mBufferState.isShared()) {
                        mCore->mActiveBuffers.erase(front->mSlot);
                        mCore->mFreeBuffers.push_back(front->mSlot);
                    }

                    listener = mCore->mConnectedProducerListener;
                    ++numDroppedBuffers;
                }

                //上一个已经没有必要显示了, 重新找一个显示的
                mCore->mQueue.erase(front);
                front = mCore->mQueue.begin();
            }

            // See if the front buffer is ready to be acquired
            nsecs_t desiredPresent = front->mTimestamp;
            bool bufferIsDue = desiredPresent <= expectedPresent ||
                    desiredPresent > expectedPresent + MAX_REASONABLE_NSEC;
            bool consumerIsReady = maxFrameNumber > 0 ?
                    front->mFrameNumber <= maxFrameNumber : true;
            // buffer要显示的时间还没到，或者 consumer 还没有准备好,
            if (!bufferIsDue || !consumerIsReady) {
                if(bVQ_StatsEnable && (mCore->mConnectedApi == NATIVE_WINDOW_API_MEDIA)) {

                }
                return PRESENT_LATER;
            }
        }

        int slot = BufferQueueCore::INVALID_BUFFER_SLOT;

        //如果是shared buffer的情况
        if (sharedBufferAvailable && mCore->mQueue.empty()) {
            // make sure the buffer has finished allocating before acquiring it
            mCore->waitWhileAllocatingLocked();

            slot = mCore->mSharedBufferSlot;

            // Recreate the BufferItem for the shared buffer from the data that
            // was cached when it was last queued.
            outBuffer->mGraphicBuffer = mSlots[slot].mGraphicBuffer;
            outBuffer->mFence = Fence::NO_FENCE;
            outBuffer->mCrop = mCore->mSharedBufferCache.crop;
            outBuffer->mTransform = mCore->mSharedBufferCache.transform &
                    ~static_cast<uint32_t>(
                    NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY);
            outBuffer->mScalingMode = mCore->mSharedBufferCache.scalingMode;
            outBuffer->mDataSpace = mCore->mSharedBufferCache.dataspace;
            outBuffer->mFrameNumber = mCore->mFrameCounter;
            outBuffer->mSlot = slot;
            outBuffer->mAcquireCalled = mSlots[slot].mAcquireCalled;
            outBuffer->mTransformToDisplayInverse =
                    (mCore->mSharedBufferCache.transform &
                    NATIVE_WINDOW_TRANSFORM_INVERSE_DISPLAY) != 0;
            outBuffer->mSurfaceDamage = Region::INVALID_REGION;
            outBuffer->mQueuedBuffer = false;
            outBuffer->mIsStale = false;
            outBuffer->mAutoRefresh = mCore->mSharedBufferMode &&
                    mCore->mAutoRefresh;
        } else {
            //找到了即将要显示的Buffer
            slot = front->mSlot;
            *outBuffer = *front;
        }

        ATRACE_BUFFER_INDEX(slot);

        //即将要显示的Buffer没有过期
        if (!outBuffer->mIsStale) {
            mSlots[slot].mAcquireCalled = true; //BUFFER的状态已经是 ACQUIRED 的了
            // Don't decrease the queue count if the BufferItem wasn't
            // previously in the queue. This happens in shared buffer mode when
            // the queue is empty and the BufferItem is created above.
            // 状态变为 ACQUIRED
            if (mCore->mQueue.empty()) {
                mSlots[slot].mBufferState.acquireNotInQueue();
            } else {
                //
                mSlots[slot].mBufferState.acquire();
            }
            mSlots[slot].mFence = Fence::NO_FENCE;
        }

        // If the buffer has previously been acquired by the consumer, set
        // mGraphicBuffer to NULL to avoid unnecessarily remapping this buffer
        // on the consumer side
        if (outBuffer->mAcquireCalled) {
            outBuffer->mGraphicBuffer = NULL;
        }

        //将Buffer从 BufferQueueCore列队里移除
        mCore->mQueue.erase(front);

        // We might have freed a slot while dropping old buffers, or the producer
        // may be blocked waiting for the number of buffers in the queue to
        // decrease.
        mCore->mDequeueCondition.broadcast();

        ATRACE_INT(mCore->mConsumerName.string(), mCore->mQueue.size());

        VALIDATE_CONSISTENCY();
    }

    if (listener != NULL) {
        //对丢掉的buffer进行回调
        for (int i = 0; i < numDroppedBuffers; ++i) {
            listener->onBufferReleased();
        }
    }

    return NO_ERROR;
}
```
# 5. updateAndReleaseLocked
**GLConsumer.cpp**
```
status_t GLConsumer::updateAndReleaseLocked(const BufferItem& item,
        PendingRelease* pendingRelease)
{
    status_t err = NO_ERROR;
    //找出slot
    int slot = item.mSlot;

    if (!mAttached) { //Open GL ES attach
        releaseBufferLocked(slot, mSlots[slot].mGraphicBuffer,
                mEglDisplay, EGL_NO_SYNC_KHR);
        return INVALID_OPERATION;
    }

    // Confirm state.
    err = checkAndUpdateEglStateLocked();
    if (err != NO_ERROR) {
        releaseBufferLocked(slot, mSlots[slot].mGraphicBuffer,
                mEglDisplay, EGL_NO_SYNC_KHR);
        return err;
    }

    // Ensure we have a valid EglImageKHR for the slot, creating an EglImage
    // if nessessary, for the gralloc buffer currently in the slot in
    // ConsumerBase.
    // We may have to do this even when item.mGraphicBuffer == NULL (which
    // means the buffer was previously acquired).
    //创建 EGLImage 此时 mEglImage 已经有acquireBufferLocked中找到的即将要显示的 BufferItem 的 GraphicBuffer
    err = mEglSlots[slot].mEglImage->createIfNeeded(mEglDisplay, item.mCrop);
    if (err != NO_ERROR) {
        releaseBufferLocked(slot, mSlots[slot].mGraphicBuffer,
                mEglDisplay, EGL_NO_SYNC_KHR);
        return UNKNOWN_ERROR;
    }
    // Do whatever sync ops we need to do before releasing the old slot.
    if (slot != mCurrentTexture) {
        err = syncForReleaseLocked(mEglDisplay); //egl 同步相关
        if (err != NO_ERROR) {
            // Release the buffer we just acquired.  It's not safe to
            // release the old buffer, so instead we just drop the new frame.
            // As we are still under lock since acquireBuffer, it is safe to
            // release by slot.
            releaseBufferLocked(slot, mSlots[slot].mGraphicBuffer,
                    mEglDisplay, EGL_NO_SYNC_KHR);
            return err;
        }
    }

    // Hang onto the pointer so that it isn't freed in the call to
    // releaseBufferLocked() if we're in shared buffer mode and both buffers are
    // the same.
    sp<EglImage> nextTextureImage = mEglSlots[slot].mEglImage;

    // release old buffer
    if (mCurrentTexture != BufferQueue::INVALID_BUFFER_SLOT) {
        if (pendingRelease == nullptr) {
            status_t status = releaseBufferLocked(
                    mCurrentTexture, mCurrentTextureImage->graphicBuffer(),
                    mEglDisplay, mEglSlots[mCurrentTexture].mEglFence);
            if (status < NO_ERROR) {
                GLC_LOGE("updateAndRelease: failed to release buffer: %s (%d)",
                        strerror(-status), status);
                err = status;
                // keep going, with error raised [?]
            }
        } else {
            pendingRelease->currentTexture = mCurrentTexture;
            pendingRelease->graphicBuffer =
                    mCurrentTextureImage->graphicBuffer();
            pendingRelease->display = mEglDisplay;
            pendingRelease->fence = mEglSlots[mCurrentTexture].mEglFence;
            pendingRelease->isPending = true;
        }
    }
    // Update the GLConsumer state.
    // 更新 GLConsumer 状态
    mCurrentTexture = slot;
    mCurrentTextureImage = nextTextureImage;
    mCurrentCrop = item.mCrop;
    mCurrentTransform = item.mTransform;
    mCurrentScalingMode = item.mScalingMode;
    mCurrentTimestamp = item.mTimestamp;
    mCurrentQueueBufferTimestamp = item.mQueueBufferTimestamp;
    mCurrentFence = item.mFence;
    mCurrentFrameNumber = item.mFrameNumber;

    computeCurrentTransformMatrixLocked();

    return err;
}
```
