> 本内容基于自己对于代码的理解以及网上大牛的博客参考写的，作为回顾时的参考之用。

SurfaceFlinger在经过事务处理以及Page Flip之后，所有的数据都准备好，最后一步就是把内容刷新到屏幕上。

# 1. handleMessageRefresh
```
void SurfaceFlinger::handleMessageRefresh() {
    ATRACE_CALL();

#ifdef ENABLE_FENCE_TRACKING
    nsecs_t refreshStartTime = systemTime(SYSTEM_TIME_MONOTONIC);
#else
    nsecs_t refreshStartTime = 0; 
#endif
    static nsecs_t previousExpectedPresent = 0; 
    nsecs_t expectedPresent = mPrimaryDispSync.computeNextRefresh(0);
    static bool previousFrameMissed = false;
    bool frameMissed = (expectedPresent == previousExpectedPresent);
    if (frameMissed != previousFrameMissed) {
        ATRACE_INT("FrameMissed", static_cast<int>(frameMissed));
    }    
    previousFrameMissed = frameMissed;

    if (CC_UNLIKELY(mDropMissedFrames && frameMissed)) {
        // Latch buffers, but don't send anything to HWC, then signal another
        // wakeup for the next vsync
        preComposition();
        repaintEverything();
    } else {
        preComposition();
        rebuildLayerStacks();
        //setUpHWComposer先会遍历各个设备DisplayDevice，然后根据可见layer数量，调用createWorkList创建hwc_layer_list_t列表，
        //然后在每个设备上遍历可见layer，将layer的mActiveBuffer设置到HWComposer中去，最后调用了HWComposer的prepare函数。
        setUpHWComposer();
        doDebugFlashRegions();
        //合成所有层的图像, 经过这一步后，就显示新的内容了。
        doComposition();
        postComposition(refreshStartTime);
    }    

    previousExpectedPresent = mPrimaryDispSync.computeNextRefresh(0);
}
```
setUpHWComposer 参考[Android6.0 图像合成过程详解（一） setUpHWComposer函数](http://blog.csdn.net/kc58236582/article/details/52856341)

doComposition请[Android6.0 图像合成过程详解（二） doComposition函数](http://blog.csdn.net/kc58236582/article/details/52868973)

# 2. preComposition
```
void SurfaceFlinger::preComposition()
{
    mPowerHintThread->requestPowerHint(); //电源管理相关

    bool needExtraInvalidate = false;
    const LayerVector& layers(mDrawingState.layersSortedByZ);
    const size_t count = layers.size();
    for (size_t i=0 ; i<count ; i++) {
        if (layers[i]->onPreComposition()) { //调用Layer的 onPreComposition
            needExtraInvalidate = true;
        }
    }
    if (needExtraInvalidate) {
        signalLayerUpdate(); //如果需要invalidate的话，触发下一个VSYNC
    }
}
```
- **Layer.cpp**
```
bool Layer::onPreComposition() {
    mRefreshPending = false;
    //自动刷新或还有QUEUED的Frame, 以及设置了side band
    return mQueuedFrames > 0 || mSidebandStreamChanged || mAutoRefresh; 
}
```
# 3. rebuildLayerStacks
```
void SurfaceFlinger::rebuildLayerStacks() {
    // rebuild the visible layer list per screen
    if (CC_UNLIKELY(mVisibleRegionsDirty)) { //重建屏幕的Layer Stack
        ATRACE_CALL();
        mVisibleRegionsDirty = false;
        invalidateHwcGeometry();

        const LayerVector& layers(mDrawingState.layersSortedByZ);
        for (size_t dpy=0 ; dpy<mDisplays.size() ; dpy++) {
            Region opaqueRegion;
            Region dirtyRegion;
            Vector< sp<Layer> > layersSortedByZ;
            const sp<DisplayDevice>& hw(mDisplays[dpy]);
            const Transform& tr(hw->getTransform());
            const Rect bounds(hw->getBounds());
            if (hw->isDisplayOn()) {
                //对该DISPLAY的所有Layer计算可视化区域
                SurfaceFlinger::computeVisibleRegions(layers,
                        hw->getLayerStack(), dirtyRegion, opaqueRegion);

                const size_t count = layers.size();
                for (size_t i=0 ; i<count ; i++) {
                    const sp<Layer>& layer(layers[i]);
                    const Layer::State& s(layer->getDrawingState());
                    if (s.layerStack == hw->getLayerStack()) {
                        Region drawRegion(tr.transform(
                                layer->visibleNonTransparentRegion));
                        drawRegion.andSelf(bounds);
                        if (!drawRegion.isEmpty()) {
                            layersSortedByZ.add(layer);
                        }
                    }
                }
            }
            //设置 on-screen上的变量， layers,  dirty区域
            hw->setVisibleLayersSortedByZ(layersSortedByZ);
            hw->undefinedRegion.set(bounds); //整个屏幕size
            hw->undefinedRegion.subtractSelf(tr.transform(opaqueRegion)); //这个没有内容的区域，一般就是黑色区域了
            hw->dirtyRegion.orSelf(dirtyRegion); //dirty 区域
        }
    }
}
```
#4. computeVisibleRegions
```
计算该Display上的可视区域
void SurfaceFlinger::computeVisibleRegions(
        const LayerVector& currentLayers, uint32_t layerStack,
        Region& outDirtyRegion, Region& outOpaqueRegion)
{
    ATRACE_CALL();

    //
    Region aboveOpaqueLayers;
    //
    Region aboveCoveredLayers;
    Region dirty;

    outDirtyRegion.clear();

    size_t i = currentLayers.size();
    while (i--) { //按照Z轴从大到小，也就是从顶至下计算
        const sp<Layer>& layer = currentLayers[i];

        // start with the whole surface at its current location
        const Layer::State& s(layer->getDrawingState());

        // only consider the layers on the given layer stack
        //只考虑一个DISPLAY device上所有的Layer
        if (s.layerStack != layerStack)
            continue;

        /*
         * opaqueRegion: area of a surface that is fully opaque.
         */
        Region opaqueRegion; //具体一层Layer的完全不透明区域

        /*
         * visibleRegion: area of a surface that is visible on screen
         * and not fully transparent. This is essentially the layer's
         * footprint minus the opaque regions above it.
         * Areas covered by a translucent surface are considered visible.
         */
        Region visibleRegion;  //具体一层Layer的可见区域

        /*
         * coveredRegion: area of a surface that is covered by all
         * visible regions above it (which includes the translucent areas).
         */
        Region coveredRegion;//具体一层Layer的被覆盖的区域

        /*
         * transparentRegion: area of a surface that is hinted to be completely
         * transparent. This is only used to tell when the layer has no visible
         * non-transparent regions and can be removed from the layer list. It
         * does not affect the visibleRegion of this layer or any layers
         * beneath it. The hint may not be correct if apps don't respect the
         * SurfaceView restrictions (which, sadly, some don't).
         */
        Region transparentRegion; //具体一层的透明区域


        // handle hidden surfaces by setting the visible region to empty
        if (CC_LIKELY(layer->isVisible())) {
            //是否是半透明状态
            const bool translucent = !layer->isOpaque(s);
            //计算Layer的可视区域
            Rect bounds(s.active.transform.transform(layer->computeBounds()));
            // 具体一层Layer的初始化可视区域
            visibleRegion.set(bounds);
            if (!visibleRegion.isEmpty()) {//如果该Layer层可视区域不为空
                // Remove the transparent area from the visible region
                if (translucent) { //如果是半透明的区域, 计算透明区域大小
                    const Transform tr(s.active.transform);
                    if (tr.preserveRects()) { //如果矩阵是一些常规矩阵，比如平移、缩放、旋转这些
                        // transform the transparent region
                        //获得透明的区域
                        transparentRegion = tr.transform(s.activeTransparentRegion);
                    } else {
                        //矩阵变换太复杂，不用优化了. 囧 ...
                        // transformation too complex, can't do the
                        // transparent region optimization.
                        transparentRegion.clear();
                    }
                }

                // compute the opaque region
                const int32_t layerOrientation = s.active.transform.getOrientation();
                if (s.alpha==255 && !translucent &&
                        ((layerOrientation & Transform::ROT_INVALID) == false)) {
                    // the opaque region is the layer's footprint
                    opaqueRegion = visibleRegion; //完全不透明的区域
                }
            }
        }

        // Clip the covered region to the visible region
        // 当前区域被前面的Layer覆盖的区域 
        coveredRegion = aboveCoveredLayers.intersect(visibleRegion);

        // Update aboveCoveredLayers for next (lower) layer
        //之前的Layer和自己所占的总的区域，相对于下一个Layer,就是已经覆盖的总区域
        aboveCoveredLayers.orSelf(visibleRegion);

        // subtract the opaque region covered by the layers above us
        //除了之前的Layer覆盖的区域外，我这层Layer还剩多少可视区域
        visibleRegion.subtractSelf(aboveOpaqueLayers);

        // compute this layer's dirty region
        if (layer->contentDirty) { //这个值在 Layer的doTransaction中可能为True, 当新旧的sequence不一致时
            // we need to invalidate the whole region
            dirty = visibleRegion;  // invalidate整个区域
            // as well, as the old visible region
            dirty.orSelf(layer->visibleRegion); //整个Dirty的区域要加上上一个可视区域，这个在之前有讲，自己想一下为什么
            layer->contentDirty = false;
        } else {
            /* compute the exposed region:
             *   the exposed region consists of two components:
             *   1) what's VISIBLE now and was COVERED before
             *   2) what's EXPOSED now less what was EXPOSED before
             *
             * note that (1) is conservative, we start with the whole
             * visible region but only keep what used to be covered by
             * something -- which mean it may have been exposed.
             *
             * (2) handles areas that were not covered by anything but got
             * exposed because of a resize.
             */
            const Region newExposed = visibleRegion - coveredRegion;
            const Region oldVisibleRegion = layer->visibleRegion;
            const Region oldCoveredRegion = layer->coveredRegion;
            const Region oldExposed = oldVisibleRegion - oldCoveredRegion;
            //只计算局部dirty区域
            dirty = (visibleRegion&oldCoveredRegion) | (newExposed-oldExposed);
        }
        //Layer的区域减掉上层Layer的覆盖的区域
        dirty.subtractSelf(aboveOpaqueLayers);

        // accumulate to the screen dirty region
        //outDirtyRegion是整个屏幕的脏区域，这个肯定是累加的
        outDirtyRegion.orSelf(dirty);

        // Update aboveOpaqueLayers for next (lower) layer
        aboveOpaqueLayers.orSelf(opaqueRegion);

        // Store the visible region in screen space
        // 保存到这些区域到 Layer中，以便下一次合成使用
        layer->setVisibleRegion(visibleRegion);
        layer->setCoveredRegion(coveredRegion);
        layer->setVisibleNonTransparentRegion(
                visibleRegion.subtract(transparentRegion));
    }
    //完全不透明的区域
    outOpaqueRegion = aboveOpaqueLayers;
}
```
