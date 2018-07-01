本文从Android Java层讲解Android Display相关类的初始化。

整体流程如下：
![Display Device Discover](http://upload-images.jianshu.io/upload_images/5688445-fe4ba9e8d536b6c9.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 1. DisplayAdapter获得DisplayDevice

![DisplayManagerService中取得DisplayDevice](http://upload-images.jianshu.io/upload_images/5688445-532bc2c960b42f7c.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

LocalDisplayAdapter从BUILT_IN_DISPLAY_IDS_TO_SCAN中获得DISPLAY ID往SurfaceFlinger中查询Display信息(tryConnectDisplayLocked)

```
    private static final int[] BUILT_IN_DISPLAY_IDS_TO_SCAN = new int[] {
            SurfaceControl.BUILT_IN_DISPLAY_ID_MAIN,
            SurfaceControl.BUILT_IN_DISPLAY_ID_HDMI,
    };
```
```
private void tryConnectDisplayLocked(int builtInDisplayId) {
    //通过display id号去SurfaceFlinger里获得对应的Binder token号
    IBinder displayToken = SurfaceControl.getBuiltInDisplay(builtInDisplayId);
    if (displayToken != null) {
        //通过token号再去SurfaceFlinger获得物理显示器的相关信息
        SurfaceControl.PhysicalDisplayInfo[] configs =
                SurfaceControl.getDisplayConfigs(displayToken);
        int activeConfig = SurfaceControl.getActiveConfig(displayToken);
        int activeColorMode = SurfaceControl.getActiveColorMode(displayToken);
        int[] colorModes = SurfaceControl.getDisplayColorModes(displayToken);
        LocalDisplayDevice device = mDevices.get(builtInDisplayId);
        if (device == null) {
            // Display was added.
            device = new LocalDisplayDevice(displayToken, builtInDisplayId,
                    configs, activeConfig, colorModes, activeColorMode);
            mDevices.put(builtInDisplayId, device);
            sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
        } else {
            ...
        }
    } else {
        ...
    }
}
```

系统启动时，LocalDisplayAdapter的mDevices并没有保存相关DisplayDevice的信息，所以tryConnectDisplayLocked会直接生成一个新的DisplayDevice,然后加入到LocalDisplayAdapter.mDevices中去, 如 **图中标注1** 所示, 最后发送一个DISPLAY_DEVICE_EVENT_ADDED信号通过Display已经加入了。

接下来看下LocalDisplayDevice的初始化函数

```
public LocalDisplayDevice(IBinder displayToken, int builtInDisplayId,
        SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo,
        int[] colorModes, int activeColorMode) {
    //储存信息
    super(LocalDisplayAdapter.this, displayToken, UNIQUE_ID_PREFIX + builtInDisplayId);
    //当前LocalDisplayDevice的Display ID号是多少
    mBuiltInDisplayId = builtInDisplayId;
    updatePhysicalDisplayInfoLocked(physicalDisplayInfos, activeDisplayInfo,
            colorModes, activeColorMode);
    ...
}
```

LocalDisplayDevice继承于DisplayDevice, 首先通过super函数将一些信息存储在DisplayDevice中，比如与SurfaceFlinger通信的Display Token, uniq ID等

然后又通过updatePhysicalDisplayInfoLocked去更新物理显示屏的信息到具体的类中, 如 **图中标注2** 所示。

```
public boolean updatePhysicalDisplayInfoLocked(
        SurfaceControl.PhysicalDisplayInfo[] physicalDisplayInfos, int activeDisplayInfo,
        int[] colorModes, int activeColorMode) {
    //将从SurfaceFlinger获得的物理显示屏的信息全部保存在mDisplayInfos里
    mDisplayInfos = Arrays.copyOf(physicalDisplayInfos, physicalDisplayInfos.length);
    // mActivePhysIndex 指的是当前 Activity 的配置
    mActivePhysIndex = activeDisplayInfo;
    ArrayList<DisplayModeRecord> records = new ArrayList<DisplayModeRecord>();
    boolean modesAdded = false;
    for (int i = 0; i < physicalDisplayInfos.length; i++) {
        //通过将物理显示屏的信息放入到 records 中
        ...
    }

    // Get the currently active mode
    // 获得当前 Activity DisplayModeRecord
    DisplayModeRecord activeRecord = null;
    for (int i = 0; i < records.size(); i++) {
        DisplayModeRecord record = records.get(i);
        if (record.hasMatchingMode(physicalDisplayInfos[activeDisplayInfo])){
            activeRecord = record;
            break;
        }
    }
    // Check whether surface flinger spontaneously changed modes out from under us. Schedule
    // traversals to ensure that the correct state is reapplied if necessary.
    if (mActiveModeId != 0
            && mActiveModeId != activeRecord.mMode.getModeId()) {
        mActiveModeInvalid = true;
        sendTraversalRequestLocked();
    }

    boolean recordsChanged = records.size() != mSupportedModes.size() || modesAdded;
    // If the records haven't changed then we're done here.
    if (!recordsChanged && !forceUpdate) {
        return false;
    }
    // Update the index of modes.
    mHavePendingChanges = true;

    mSupportedModes.clear();
    // 将records加入到mSupportedModes中
    for (DisplayModeRecord record : records) {
        mSupportedModes.put(record.mMode.getModeId(), record);
    }
    
    ...
    return true;
}

```
那这些物理显示屏的信息具体有哪些呢？看下PhysicalDisplayInfo类中的定义便可知
```
public int width;   //显示屏宽度 单位是px
public int height; //显示屏高度 单位是px
public float refreshRate;  //显示屏刷新率  59HZ 60HZ ...
public float density;   //屏幕密度, 表示每粗寸上有多少个点 120/160/...
public float xDpi;
public float yDpi;
public boolean secure;
public long appVsyncOffsetNanos;
public long presentationDeadlineNanos;
```

density可以参考[Pixel Density](https://en.wikipedia.org/wiki/Pixel_density)

LocalDisplayAdapter将从SF获得的显示屏信息DisplayDevice通知DisplayManagerService `Device Added`了。

```
private void handleDisplayDeviceAddedLocked(DisplayDevice device) {
    //getDisplayDeviceInfoLocked函数会将物理显示屏的active的信息保存到mInfo也就是DisplayDeviceInfo里
    DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
    if (mDisplayDevices.contains(device)) {
        return;
    }

    // mDebugLastLoggedDeviceInfo 表示的是上一次的DisplayDeviceInfo的信息
    device.mDebugLastLoggedDeviceInfo = info;
    
    //将DisplayDevice加入到DMS的mDisplayDevices中去
    //如图中标注3所示
    mDisplayDevices.add(device);
    
    //为物理显示屏生成一个 LogicalDisplay
    LogicalDisplay display = addLogicalDisplayLocked(device);
    
    //updateDisplayStateLocked会触发 DISPLAY_DEVICE_EVENT_CHANGED， 
    Runnable work = updateDisplayStateLocked(device);
    if (work != null) {
        work.run();
    }
    if (display != null && display.getPrimaryDisplayDeviceLocked() == device) {
        int colorMode = mPersistentDataStore.getColorMode(device);
        if (colorMode == Display.COLOR_MODE_INVALID) {
            if ((device.getDisplayDeviceInfoLocked().flags
                 & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0) {
                colorMode = mDefaultDisplayDefaultColorMode;
            } else {
                colorMode = Display.COLOR_MODE_DEFAULT;
            }
        }
        display.setRequestedColorModeLocked(colorMode);
    }
    scheduleTraversalLocked(false);
}
```

handleDisplayDeviceAddedLocked函数会为DisplayDevice生成一个对应的LogicalDevice, 同时会将LogicalDevice加入到DisplayManagerService中mLogicalDevices中，同时也将DisplayDevice加入到DisplayManagerService的mDisplayDevices中如 **图中标注3** 和 **图中标注4** 所示

为DisplayDevice生成一个LogicalDisplay

```
private LogicalDisplay addLogicalDisplayLocked(DisplayDevice device) {
    DisplayDeviceInfo deviceInfo = device.getDisplayDeviceInfoLocked();
    boolean isDefault = (deviceInfo.flags
            & DisplayDeviceInfo.FLAG_DEFAULT_DISPLAY) != 0;
    // isDefault 表示的是否是默认的Display
    if (isDefault && mLogicalDisplays.get(Display.DEFAULT_DISPLAY) != null) {
        isDefault = false;
    }

    //如果不是默认的，而且系统只支持单显示屏模式，那就直接退出了
    if (!isDefault && mSingleDisplayDemoMode) {
        return null;
    }

    final int displayId = assignDisplayIdLocked(isDefault);
    final int layerStack = assignLayerStackLocked(displayId);

    LogicalDisplay display = new LogicalDisplay(displayId, layerStack, device);
    
    //用DisplayDevice中的DisplayDeviceInfo更新LogicalDisplay中的mBaseInfoDisplayInfo
    //如 图中标注5 所示
    display.updateLocked(mDisplayDevices);
    if (!display.isValidLocked()) {
        return null;
    }

    // 如图标注3所示
    mLogicalDisplays.put(displayId, display);

    // Wake up waitForDefaultDisplay.
    // 通知 SystemServer 线程继续执行下面的初始化 具体是在 DisplayManagerService的onBootPhase阶段
    // 这样systemserver继续初始化 pkms, wms 等等
    if (isDefault) {
        mSyncRoot.notifyAll();
    }
    
    // 通过 DISPLAY add
    sendDisplayEventLocked(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
    return display;
}
```

handleDisplayDeviceChanged由updateDisplayStateLocked触发, 它的本意是 DisplayDevice(最底层的显示屏信息已经改变了)那么，就要通知改变它所对应的LogicalDisplay中的相关的信息，

```
DisplayManagerService
private void handleDisplayDeviceChanged(DisplayDevice device) {
    synchronized (mSyncRoot) {
        DisplayDeviceInfo info = device.getDisplayDeviceInfoLocked();
        if (!mDisplayDevices.contains(device)) {
            return;
        }

        //mDebugLastLoggedDeviceInfo记录的是上一次的DisplayInfo
        int diff = device.mDebugLastLoggedDeviceInfo.diff(info);
        if (diff == DisplayDeviceInfo.DIFF_STATE) {
            Slog.i(TAG, "Display device changed state: \"" + info.name
                    + "\", " + Display.stateToString(info.state));
        } else if (diff != 0) {
            Slog.i(TAG, "Display device changed: " + info);
        }
        if ((diff & DisplayDeviceInfo.DIFF_COLOR_MODE) != 0) {
            try {
                mPersistentDataStore.setColorMode(device, info.colorMode);
            } finally {
                mPersistentDataStore.saveIfNeeded();
            }
        }
        //更新 Last DeviceInfo
        device.mDebugLastLoggedDeviceInfo = info;

        device.applyPendingDisplayDeviceInfoChangesLocked();
        if (updateLogicalDisplaysLocked()) {  //更新LogicalDisplay中的相关的信息
            scheduleTraversalLocked(false);  
        }
    }
}
```

# 2. WMS中关于Display类相关的初始化

![WindowManagerService中Display相关类初始化](http://upload-images.jianshu.io/upload_images/5688445-90c0d469f17387f8.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
private WindowManagerService(Context context, InputManagerService inputManager,
        boolean haveInputMethods, boolean showBootMsgs, boolean onlyCore,
        WindowManagerPolicy policy) {
    mRoot = new RootWindowContainer(this);
    mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
    mWindowPlacerLocked = new WindowSurfacePlacer(this);
    ...
    //获得 DisplayManager
    mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
    //通过DisplayManager去获得Displays
    mDisplays = mDisplayManager.getDisplays();
    for (Display display : mDisplays) {
        createDisplayContentLocked(display);
    }
}  
```
WMS通过DisplayManager的getDisplays()去获得所有的Display信息，这个可以参考第3小节，
接下来看下 createDisplayContentLocked

```
private void createDisplayContentLocked(final Display display) {
    mRoot.getDisplayContentOrCreate(display.getDisplayId());
}

DisplayContent getDisplayContentOrCreate(int displayId) {
    //检查displayId的DisplayContent是否已经创建过了
    DisplayContent dc = getDisplayContent(displayId);
    if (dc == null) {
        final Display display = mService.mDisplayManager.getDisplay(displayId);
        if (display != null) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                dc = createDisplayContent(display);
            } finally {
                Binder.restoreCallingIdentity(callingIdentity);
            }
        }
    }
    return dc;
}
```

```
private DisplayContent createDisplayContent(final Display display) {
    //生成一个新的DisplayContent
    final DisplayContent dc = new DisplayContent(display, mService, mLayersController,
            mWallpaperController);
    final int displayId = display.getDisplayId();

    final DisplayInfo displayInfo = dc.getDisplayInfo();
    final Rect rect = new Rect();
    //获得Overscan的区域, 这个是配置的 /data/system/display_settings.xml
    mService.mDisplaySettings.getOverscanLocked(displayInfo.name, displayInfo.uniqueId, rect);
    displayInfo.overscanLeft = rect.left;
    displayInfo.overscanTop = rect.top;
    displayInfo.overscanRight = rect.right;
    displayInfo.overscanBottom = rect.bottom;
    if (mService.mDisplayManagerInternal != null) {
         //这里可能会设置 mOverrideDisplayInfo 
        mService.mDisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(
                displayId, displayInfo);
        mService.configureDisplayPolicyLocked(dc);

        // TODO(multi-display): Create an input channel for each display with touch capability.
        if (displayId == DEFAULT_DISPLAY && mService.canDispatchPointerEvents()) {
            dc.mTapDetector = new TaskTapPointerEventListener(
                    mService, dc);
            mService.registerPointerEventListener(dc.mTapDetector);
            mService.registerPointerEventListener(mService.mMousePositionTracker);
        }
    }

    return dc;
}
```


# 3. DisplayManager中相关初始化

![DisplayManager类初始化](http://upload-images.jianshu.io/upload_images/5688445-43d575a4c75bb4e1.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
public Display[] getDisplays(String category) {
    final int[] displayIds = mGlobal.getDisplayIds(); //通过DisplayManagerGlobal去获得DisplayIds
    synchronized (mLock) {
        try {
            if (category == null) {  //进入该分支
                addAllDisplaysLocked(mTempDisplays, displayIds);
            } else if (category.equals(DISPLAY_CATEGORY_PRESENTATION)) {
                ...
            }
            return mTempDisplays.toArray(new Display[mTempDisplays.size()]);
        } finally {
            mTempDisplays.clear();
        }
    }
}
```
```
public int[] getDisplayIds() {
    try {
        synchronized (mLock) {
            if (USE_CACHE) {
                if (mDisplayIdCache != null) {
                    return mDisplayIdCache;
                }
            }

            int[] displayIds = mDm.getDisplayIds(); //通过DisplayManagerService获得当前的显示屏对应的 ID 值 
            if (USE_CACHE) { //是否使用缓存
                mDisplayIdCache = displayIds;
            }
            //注册DisplayManagerCallback回调函数接收onDisplayEvent事件
            //比如 DISPLAY CHANGED/REMOVED/ADDED ... 这时DisplayManager就会更新APP注册过来的回调函数.
            registerCallbackIfNeededLocked();
            return displayIds;
        }
    } catch (RemoteException ex) {
        throw ex.rethrowFromSystemServer();
    }
}
```
```
private void addAllDisplaysLocked(ArrayList<Display> displays, int[] displayIds) {
    for (int i = 0; i < displayIds.length; i++) {
        //getOrCreateDisplayLocked主要是getDisplayInfo接口去请求DMS的DisplayInfo,然后生成Display类
        Display display = getOrCreateDisplayLocked(displayIds[i], true /*assumeValid*/);
        if (display != null) {
            displays.add(display);
        }
    }
} 
```
可以看出来getDisplayInfoInternal主要是从LogicalDisplay中获得mInfo, 如图中标注6所示.
而mInfo的信息大部分是从mBaseDisplayInfo中获得

```
private DisplayInfo getDisplayInfoInternal(int displayId, int callingUid) {
    synchronized (mSyncRoot) {
        LogicalDisplay display = mLogicalDisplays.get(displayId);
        if (display != null) {
            DisplayInfo info = display.getDisplayInfoLocked();
            if (info.hasAccess(callingUid)
                    || isUidPresentOnDisplayInternal(callingUid, displayId)) {
                return info;
            }
        }
        return null;
    }
}
```
# 4. ActivityStackSupervior中与Display的关系

![ASS与Display的关系](http://upload-images.jianshu.io/upload_images/5688445-f36b37fef05288fe.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
void setWindowManager(WindowManagerService wm) {
    synchronized (mService) {
        mDisplayManager =
                (DisplayManager)mService.mContext.getSystemService(Context.DISPLAY_SERVICE);
        //ActivityStackSupervior注册DisplayListener到DisplayManager中去接收Display改变的事件
        mDisplayManager.registerDisplayListener(this, null);
        mDisplayManagerInternal = LocalServices.getService(DisplayManagerInternal.class);
        //获得所有的Display信息
        Display[] displays = mDisplayManager.getDisplays();
        for (int displayNdx = displays.length - 1; displayNdx >= 0; --displayNdx) {
            final int displayId = displays[displayNdx].getDisplayId();
            //为display生成对应的 ActivityDisplay信息
            ActivityDisplay activityDisplay = new ActivityDisplay(displayId);
            mActivityDisplays.put(displayId, activityDisplay);
            calculateDefaultMinimalSizeOfResizeableTasks(activityDisplay);
        }
        ...
    }
}
```
