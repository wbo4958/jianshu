https://www.jianshu.com/p/2bff4ecd86c9 已经大概说了下Android Input的整体流程，但是还有些小细节并没有详细说，而这篇文章主要讲了Input是如何加入输入设备，以及input是如何解析输入设备上报的事件的。

# 一、input发现输入设备

## 1.1  扫描输入设备
EventHub::getEvents()在第一次进入的时候，会根据 mNeedToScanDevices 的值去决定是否去扫描设备，而 mNeedToScanDevices 默认是为true的
```
        if (mNeedToScanDevices) {
            mNeedToScanDevices = false;
            scanDevicesLocked();
            mNeedToSendFinishedDeviceScan = true;
        }
```
scanDevicesLocked()这个函数完成两件事, 一个是去扫描 /dev/input 下所有的输入设备,另一个是创建一个 VIRTUAL KEYBOARD 设备,顾名思义，虚拟键盘。

```
void EventHub::scanDevicesLocked() {
    status_t res = scanDirLocked(DEVICE_PATH);
    if(res < 0) {
        ALOGE("scan dir failed for %s\n", DEVICE_PATH);
    }
    if (mDevices.indexOfKey(VIRTUAL_KEYBOARD_ID) < 0) {
        createVirtualKeyboardLocked();
    }
}
```

scanDirLocked函数会通过openDeviceLocked打开 /dev/input 下的每一个输入设备来决定是否将该设备加入到input框架中去。

如在 pixel 手机中, 有如下的输入设备
```
sailfish:/ # ls /dev/input
event0 event1 event2 event3 event4 event5 event6 mice
```

openDeviceLocked()函数人主要功能有

1. 是通过 ioctl 向驱动查询输入设备的相关信息
如 device name,  driver version, device identifier(vendor/version/bus ...) 等， 如果查询失败，则说明驱动有问题，input就不会将该输入设备加入到input框架当中。

2. 如果驱动没问题，则通过loadConfigurationLocked 去查找并加载configure文件
如果有configure文件，则通过 PropertyMap::load将configure文件里的内容加载进来

3. 通过 ioctl 去查询该 输入设备上报什么事件, 如 key/ abs /rel  ...等等事件
```
    ioctl(fd, EVIOCGBIT(EV_KEY, sizeof(device->keyBitmask)), device->keyBitmask);
    ioctl(fd, EVIOCGBIT(EV_ABS, sizeof(device->absBitmask)), device->absBitmask);
    ioctl(fd, EVIOCGBIT(EV_REL, sizeof(device->relBitmask)), device->relBitmask);
    ioctl(fd, EVIOCGBIT(EV_SW, sizeof(device->swBitmask)), device->swBitmask);
    ioctl(fd, EVIOCGBIT(EV_LED, sizeof(device->ledBitmask)), device->ledBitmask);
    ioctl(fd, EVIOCGBIT(EV_FF, sizeof(device->ffBitmask)), device->ffBitmask);
    ioctl(fd, EVIOCGPROP(sizeof(device->propBitmask)), device->propBitmask);
```

4. 根据这些上报的事件类型，设备device的 classes, 如INPUT_DEVICE_CLASS_KEYBOARD, 并加载对应的 key layout map 和  key character map

5. 最后再通过
```
void EventHub::addDeviceLocked(Device* device) {
    mDevices.add(device->id, device);
    device->next = mOpeningDevices;
    mOpeningDevices = device;
}
```
加入到 mDevices 和 mOpeningDevices中。

这里以pixel手机的触摸屏输入设备为例
![pixel手机的触摸屏](https://upload-images.jianshu.io/upload_images/5688445-147bae063e555eff.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![pixel手机的gpio键盘](https://upload-images.jianshu.io/upload_images/5688445-dd3ee0a067302d3a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
```
# cat gpio-keys.kl
key 116   POWER
key 115   VOLUME_UP
key 114   VOLUME_DOWN
```

## 1.2 EventHub上报InputReader关于输入设备接入
EventHub发现了输入设备后，紧接着向InputReader上报关于设备加入了
```
        while (mOpeningDevices != NULL) {
            Device* device = mOpeningDevices;
            mOpeningDevices = device->next;
            event->when = now;
            event->deviceId = device->id == mBuiltInKeyboardId ? 0 : device->id;
            event->type = DEVICE_ADDED;
            event += 1;
            mNeedToSendFinishedDeviceScan = true;
            if (--capacity == 0) {
                break;
            }
        }
```
1.1 小节在扫描设备完后将设备加入了 mClosingDevices, 此时Eventhub将mClosingDevices所构成的链表依次加入到 event (DEVICE_ADDED)

InputReader在收到事件后
```
            case EventHubInterface::DEVICE_ADDED:
                addDeviceLocked(rawEvent->when, rawEvent->deviceId);
                break;
```
调用 addDeviceLocked 生成 InputDevice 并加入到 InputReader里的 mDevices中

![input device](https://upload-images.jianshu.io/upload_images/5688445-50bc6abff7637277.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


# 二、Input 处理输入事件

InputReader在收到输入设备的事件后，会调用  processEventsForDeviceLocked  去处理输入事件
```
void InputReader::processEventsForDeviceLocked(int32_t deviceId,
        const RawEvent* rawEvents, size_t count) {
    ssize_t deviceIndex = mDevices.indexOfKey(deviceId);
    InputDevice* device = mDevices.valueAt(deviceIndex);
    device->process(rawEvents, count);
}
```
最终又会调用 InputDevice::process 去处理这些输入事件
```
void InputDevice::process(const RawEvent* rawEvents, size_t count) {
    // Process all of the events in order for each mapper.
    // We cannot simply ask each mapper to process them in bulk because mappers may
    // have side-effects that must be interleaved.  For example, joystick movement events and
    // gamepad button presses are handled by different mappers but they should be dispatched
    // in the order received.
    size_t numMappers = mMappers.size();
    for (const RawEvent* rawEvent = rawEvents; count--; rawEvent++) {
        if (mDropUntilNextSync) {
          ...
        } else if (rawEvent->type == EV_SYN && rawEvent->code == SYN_DROPPED) {
          ...
        } else {
            for (size_t i = 0; i < numMappers; i++) {
                InputMapper* mapper = mMappers[i];
                mapper->process(rawEvent);
            }
        }
    }
}
```
## 2.1 以power键为例
当按一次power键后，EventHub就获得了两个事件，如下log所示
![power_key.JPG](https://upload-images.jianshu.io/upload_images/5688445-b69e94209194c123.JPG?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
可知处理 power 输入事件的InputDevice只有一个KeyboardMapper

```
void KeyboardInputMapper::process(const RawEvent* rawEvent) {
    switch (rawEvent->type) {
    case EV_KEY: {
        int32_t scanCode = rawEvent->code;
        int32_t usageCode = mCurrentHidUsage;
        mCurrentHidUsage = 0;

        if (isKeyboardOrGamepadKey(scanCode)) {
            processKey(rawEvent->when, rawEvent->value != 0, scanCode, usageCode);
        }
        break;
    }
    case EV_MSC: {
        if (rawEvent->code == MSC_SCAN) {
            mCurrentHidUsage = rawEvent->value;
        }
        break;
    }
    case EV_SYN: {
        if (rawEvent->code == SYN_REPORT) {
            mCurrentHidUsage = 0;
        }
    }
    }
}
```

![相关的值](https://upload-images.jianshu.io/upload_images/5688445-437110470f09282d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
第一个输入事件为 EV_KEY, 其对应的code = 116, value = 1, 表示按下
第二个输入事件为 EV_SYN, 对应的code = 0
![image.png](https://upload-images.jianshu.io/upload_images/5688445-93eca68767f2fe41.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
第一个输入事件为 EV_KEY, 其对应的code = 116, value = 0, 表示释放
第二个输入事件为 EV_SYN, 对应的code = 0

## 2.2 触屏事件

![ABS code](https://upload-images.jianshu.io/upload_images/5688445-53670a17e46c784f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- **单点触摸**

**第一个 bunch**
![第一个 bunch](https://upload-images.jianshu.io/upload_images/5688445-22fda6a0413ddaf5.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

type = 3 对应的是 EV_ABS, 然后它的 code 分别为 0x39(ABS_MT_TRACKING_ID), 0x35(ABS_MT_POSITION_X), 0x36(ABS_MT_POSITION_Y), 0x3a(ABS_MT_PRESSURE)
type = 0 对应的是 EV_SYN, 其实 EV_SYN 表示一组输入事件结束

**第二个 bunch**
![第二个 bunch](https://upload-images.jianshu.io/upload_images/5688445-6a43753543e920e4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

type = 3, code 分别是 0x31(ABS_MT_TOUCH_MINOR), 0x3a(ABS_MT_PRESSURE)

**第三个 bunch**
![第三个 bunch](https://upload-images.jianshu.io/upload_images/5688445-83c29e841b5f39a9.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
type = 3, code 分别是  0x3a(ABS_MT_PRESSURE)

**最后 bunch**
![最后 bunch](https://upload-images.jianshu.io/upload_images/5688445-629a826488b8414f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
type = 3, code 分别是  0x3a(ABS_MT_TRACKING_ID)

从上面的连续的bunch, 可以看出，第一个bunch给出来触摸点的位置以及压力，后面的bunch只有压力的改变。也就是手指点击后并没有移动过，否则会有新的position上报

现在来看下代码
```
void MultiTouchInputMapper::process(const RawEvent* rawEvent) {
    TouchInputMapper::process(rawEvent);

    mMultiTouchMotionAccumulator.process(rawEvent);
}

void TouchInputMapper::process(const RawEvent* rawEvent) {
    mCursorButtonAccumulator.process(rawEvent);
    mCursorScrollAccumulator.process(rawEvent);
    mTouchButtonAccumulator.process(rawEvent);

    if (rawEvent->type == EV_SYN && rawEvent->code == SYN_REPORT) {
        sync(rawEvent->when);
    }
}

void MultiTouchMotionAccumulator::process(const RawEvent* rawEvent) {
    if (rawEvent->type == EV_ABS) {
        ...
        if (mCurrentSlot < 0 || size_t(mCurrentSlot) >= mSlotCount) {
          ...
        } else {
            Slot* slot = &mSlots[mCurrentSlot];

            switch (rawEvent->code) {
            case ABS_MT_POSITION_X:
                slot->mInUse = true;
                slot->mAbsMTPositionX = rawEvent->value;
                break;
            case ABS_MT_POSITION_Y:
                slot->mInUse = true;
                slot->mAbsMTPositionY = rawEvent->value;
                break;
            case ABS_MT_TOUCH_MAJOR:
                slot->mInUse = true;
                slot->mAbsMTTouchMajor = rawEvent->value;
                break;
...
}
```
由代码可知 MultiTouchMotionAccumulator 就是将每个 bunch 的事件放到一个 Slot, 在这个单点触摸的例子中，每一个bunch, InputReader dispatch一次，下一个bunch会基于上一个bunch的值，如本例中第二个bunch，本来没有x, y的值，但是由于这次是继承上一次的x, y, 所以 x, y是上一次的值，改变的是仅仅是 pressure的值，其它值未变。

最后经过一系列的运算，通过 dispatchMotion 将触屏事件发送出去

```
void TouchInputMapper::process(const RawEvent* rawEvent) {
    ...
    //每一个bunch都以EV_SYN结束，然后sync
    if (rawEvent->type == EV_SYN && rawEvent->code == SYN_REPORT) {
        sync(rawEvent->when);
    }    
}
```
```
sync -> syncTouch -> processRawTouches -> cookAndDispatch -> dispatchTouches -> dispatchMotion
```

-  **多点触摸**

**第一个bunch**
![多点触摸](https://upload-images.jianshu.io/upload_images/5688445-dbeda5db11fc4d4f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
可见多点与单点触摸的区别在于，多点触摸一次会上报所有点的触摸事件，每个触摸事件之间由 ABS_MT_SLOT 分开，这样MultiTouchMotionAccumulator::process在处理的时候就会将多点触摸放到多个slot中，这样在 syncTouch时就会将多个slot，放到RawState.RawPointerData.Pointer中。

![多点触摸](https://upload-images.jianshu.io/upload_images/5688445-30b0f9d3a22aa965.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
