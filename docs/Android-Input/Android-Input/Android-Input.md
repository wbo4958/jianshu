https://www.jianshu.com/p/2bff4ecd86c9
本篇博客主要是过一下Android Input的框架，在熟悉Android input框架后，以及与App的事件传递流程后，再过一下事件的处理流程，以及ANR是怎样发生的。如果在不了解input的框架情况下就去直接按键等事件就会觉得很卡。

# 一、Android Input的工作模型
![](http://upload-images.jianshu.io/upload_images/5688445-8be5b80935aa6bf6.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 1.1InputDispatcher
InputDispatcher单独run在InputDispatcher线程中
### 1.1.1 InputDispatcher的模型
InputDispatcher的实现模型是Looper的机制，其底层根本还是属于epoll机制. 只不过Input并没有使用Looper相关的Message相关的功能，也就是说没有MessageQueue了，仅是单纯的使用Looper的addFd功能，以及它的epoll阻塞唤醒功能。

InputDispatcher单独运行在一个线程当中，当线程启动时，它会不停的调用threadLoop, 
```
bool InputDispatcherThread::threadLoop() {
    mDispatcher->dispatchOnce();
    return true;
}
```
每一次threadLoop都会调用InputDispatcher的dispatchOnce函数
```
void InputDispatcher::dispatchOnce() {
    nsecs_t nextWakeupTime = LONG_LONG_MAX;
    { // acquire lock
        AutoMutex _l(mLock);
        mDispatcherIsAliveCondition.broadcast();

        // Run a dispatch loop if there are no pending commands.
        // The dispatch loop might enqueue commands to run afterwards.
        if (!haveCommandsLocked()) {
            dispatchOnceInnerLocked(&nextWakeupTime);
        }

        // Run all pending commands if there are any.
        // If any commands were run then force the next poll to wake up immediately.
        if (runCommandsLockedInterruptible()) {
            nextWakeupTime = LONG_LONG_MIN;
        }
    } // release lock

    // Wait for callback or timeout or wake.  (make sure we round up, not down)
    nsecs_t currentTime = now();
    int timeoutMillis = toMillisecondTimeoutDelay(currentTime, nextWakeupTime);
    mLooper->pollOnce(timeoutMillis);
}
```
上面dispatchOnce先会尝试去获得pending的Commands，然后处理这些pending的命令。当这些都处理完成后，会调用Looper的pollOnce，传进去的参数是timeout. 正常情况下，如果当前没有唤醒，或没有fd的回调(这个后面会讲，也就是App消费了input事件的回调), 那么InputDispatcher线程就一直block在Looper的epoll那里，直到被唤醒。具体可以参考[Android Handler/Looper](https://www.jianshu.com/p/48cf21ad637b)

### 1.1.2 Looper的唤醒
***1.1.1***已经大致的说了下InputDispatcher线程的工作模型，没有事件时它会block在Looper的epoll处. 那它啥时候被唤醒呢？其实很简单，查找哪些地方调用了 mLooper->wake();，还有一个地方是App消费了input事件的回调(后面讲)

```
notifyConfigurationChanged()
notifyKey()
notifyMotion()
notifySwitch();
injectInputEvent()
setInputWindows()
setFocusedApplication()
setInputDispatchMode()
setInputFilterEnabled()
transferTouchFocus()
registerInputChannel()
unregisterInputChannel()
monitor()
```
上面这些函数都有调用到mLooper->wake的可能。
如notifyKey()/notifyMotion() 等， **"间接"**来自`InputReader`线程的通知。
setInputWindows()/setFocusedApplication()等，**"间接"**来自`android.display`线程的通知。

如果 App 消费了Input事件， Looper也会被唤醒，接着handleReceiveCallback被回调。

这里以**notifyConfigurationChanged**为例
```
void InputDispatcher::notifyConfigurationChanged(const NotifyConfigurationChangedArgs* args) {
    bool needWake;
    { // acquire lock  防止多线程同时访问，这里加了一个mLock的互斥锁。
        AutoMutex _l(mLock);
        ConfigurationChangedEntry* newEntry = new ConfigurationChangedEntry(args->eventTime);
        needWake = enqueueInboundEventLocked(newEntry);
    } // release lock

    if (needWake) {
        mLooper->wake();
    }
}
```
`notifyConfigurationChanged`其实挺简单的，生成一个ConfigurationChangedEntry，然后通过enqueueInboundEventLocked函数加入到mInboundQueue队列中。具体的可以看参考下enqueueInboudnEventLocked. 然后根据needWake决定是否唤醒Looper, 这个needWake,默认在mInboundQueue里没有数据时为true, 当mInboundQueue里有数据时，此时Looper应该已经被唤醒了，且正在处理mInboundQueue里的命令，此时已经是wake的状态，所以没有必要再次wake一次。

```
    Queue<EventEntry> mInboundQueue; 
```
mInboundQueue申明为一个队列，主要是保存**InputReader**中传过来的EventEntry.

EventEntry主要有如下的几种类型
![EventEntry](http://upload-images.jianshu.io/upload_images/5688445-0240ab132002b99d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
mPendingEvent是EventEntry类型，它根据type类型转为具体的EventEntry，如 MotionEntry, KeyEntry等等。

### 1.1.3 InputDispatcher处理Commands
从***1.1***的dispatchOnce()代码中可以看出，InputDispatcher在当前没有commands时会直接调用dispatchOnceInnerLocked一次，而dispatchOnceInnerLocked的目的就是去获得Commands.

如果当前有Commands了(比如***1.2***已经ConfigurationEventEntry)，就不会去获得Commands，而是直接run已经有的Commands.

```
void InputDispatcher::dispatchOnceInnerLocked(nsecs_t* nextWakeupTime) {
    if (! mPendingEvent) {
        if (mInboundQueue.isEmpty()) {
            if (!mPendingEvent) {
                return;
            }
        } else {
            mPendingEvent = mInboundQueue.dequeueAtHead();
        }
        ...
    } //这个if块意在获得一个mPendingEvent，如果没有Pending的event, 直接返回掉

   //如果已经走到下面，mPendingEvent是不为空的，也就是有待处理的事件
    switch (mPendingEvent->type) {
    case EventEntry::TYPE_CONFIGURATION_CHANGED: {
        done = dispatchConfigurationChangedLocked(currentTime, typedEntry);
        break;
    }
    case EventEntry::TYPE_DEVICE_RESET: {
    case EventEntry::TYPE_KEY: {
    case EventEntry::TYPE_MOTION: {
}
```
这里依然以mPendingEntry为ConfigurationChangedEntry为例, 
```
bool InputDispatcher::dispatchConfigurationChangedLocked(
        nsecs_t currentTime, ConfigurationChangedEntry* entry) {
    CommandEntry* commandEntry = postCommandLocked(
            & InputDispatcher::doNotifyConfigurationChangedInterruptible);
    commandEntry->eventTime = entry->eventTime;
    return true;
}
```
```
InputDispatcher::CommandEntry* InputDispatcher::postCommandLocked(Command command) {
    CommandEntry* commandEntry = new CommandEntry(command);
    mCommandQueue.enqueueAtTail(commandEntry);
    return commandEntry;
}
```
生成一个Commands,它的函数指针指向doNotifyConfigurationChangedInterruptible. 

dispatchOnce在runCommandsLockedInterruptible()里去处理所有的Commands,这时会调用doNotifyConfigurationChangedInterruptible,

```
void InputDispatcher::doNotifyConfigurationChangedInterruptible(
        CommandEntry* commandEntry) {
    mLock.unlock();
    mPolicy->notifyConfigurationChanged(commandEntry->eventTime);
    mLock.lock();
}
```
最终调用mPolicy, 也就是NativeInputManager中的notifyConfigurationChanged, 将结果返回到java层去。

**这种情况并没有包含 Key/Motion这样的事件情况，(后续会继续介绍)**

### 1.1.4 小结
- a). InputDispatcher使用Looper的epoll模型, 意味着在没有命令处理时会block在epoll处
- b). 当IMS(java层) 或 InputReader有事件要dispatch时，它们会唤醒InputDispatcher
- c). InputDispatcher被唤醒后，会从mInboundQueue队列中查找pending的event, 然后生成对应的Commands, 最后执行这些Commands.

![flow](http://upload-images.jianshu.io/upload_images/5688445-944dc28eb2a93cde.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 1.2 InputReader
InputReader单独运行在InputReaderThread中，它依然继承于Thread类，也就是当InputReaderThread线程运行起来后它会一直调用threadLoop()函数。InputReader并没有使用Looper机制，不过它使用到了EventHub里的 epoll 机制，和Looper的epoll机制一样。

### 1.2.1 EventHub
EventHub在NativeInputManager里初始化，并没有放到InputReader里初始化，其实完全可以放到InputReader里初始化的呢？ why???

```
EventHub::EventHub(void) : ... {
    acquire_wake_lock(PARTIAL_WAKE_LOCK, WAKE_LOCK_ID);
    //创建一个epoll文件字柄
    mEpollFd = epoll_create(EPOLL_SIZE_HINT);
    mINotifyFd = inotify_init(); //创建一个inotify fd
    //inotify 监听 /dev/input 目录
    int result = inotify_add_watch(mINotifyFd, DEVICE_PATH, IN_DELETE | IN_CREATE);
   ...
    //将inotify fd加入到epoll中
    result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mINotifyFd, &eventItem);
    int wakeFds[2];
    //创建一个管道
    result = pipe(wakeFds);
    mWakeReadPipeFd = wakeFds[0];
    mWakeWritePipeFd = wakeFds[1];
    ...
    result = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, mWakeReadPipeFd, &eventItem);
}
```
从EventHub的初始化可以看出，它通过inotify监听 /dev/input里的文件的变化，另外创建了一个管道， 将read fd加入到epoll中去监听，而write fd主要用来唤醒epoll.

从 EventHub::wake()函数可以看出来
```
void EventHub::wake() {
    ssize_t nWrite;
    do {
        nWrite = write(mWakeWritePipeFd, "W", 1);
    } while (nWrite == -1 && errno == EINTR);
}
```
通过调用wake函数，往 write fd中写入一个字节，然后epoll监听的read fd就有事件发生，epoll就被唤醒了，这和Looper的wake机制一模一样。
![EventHub机制](http://upload-images.jianshu.io/upload_images/5688445-8e035d76034cefd0.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


### 1.2.2 InputReader初始化
```
InputReader::InputReader(const sp<EventHubInterface>& eventHub,
        const sp<InputReaderPolicyInterface>& policy,
        const sp<InputListenerInterface>& listener) :
        mContext(this), mEventHub(eventHub), mPolicy(policy),
        mGlobalMetaState(0), mGeneration(1),
        mDisableVirtualKeysTimeout(LLONG_MIN), mNextTimeout(LLONG_MAX),
        mConfigurationChangesToRefresh(0) {
    mQueuedListener = new QueuedInputListener(listener);

    { // acquire lock
        AutoMutex _l(mLock);
        refreshConfigurationLocked(0);
        updateGlobalMetaStateLocked();
    } // release lock
}
```
InputReader的构造函数中初始化了一个QueuedInputListener, 它接收InputListenerInterface作为它的参数，从InputReader调用可知，这个InputListenerInterface其实就是InputDispatcher, QueueInputListener只是作为InputDispatcher的Wrapper.

#### 1.2.2.1 读取Java层中的配置
```
void InputReader::refreshConfigurationLocked(uint32_t changes) {
    mPolicy->getReaderConfiguration(&mConfig);
    mEventHub->setExcludedDevices(mConfig.excludedDeviceNames);
    ...
}
```
refreshConfigurationLocked的主要是通过getReaderConfiguration调用到Java层的配置信息，保存到mConfig里。具体调用到如下的接口
``` java
getVirtualKeyQuietTimeMillis
getExcludedDeviceNames
getHoverTapTimeout
getDoubleTapTimeout
getLongPressTimeout
getHoverTapSlop
```
这些函数具体实现就是去取一些framework-res.jar里的一些配置信息。读到的信息最后都会设置到InputReader里不同的模块中，比如"ecluded device name"会去设置 EventHub 里mExcludedDevices等等。

### 1.2.3 InputReader运作起来
```
bool InputReaderThread::threadLoop() {
    mReader->loopOnce();
    return true;
}
```
InputReaderThread线程开启后会不停的运行threadLoop函数, 而它会调用InputReader的loopOnce函数

```
void InputReader::loopOnce() {
    ... 太多细节就不多说了
    //获得事件, 没有事件就block在EventHub中的epoll处
    size_t count = mEventHub->getEvents(timeoutMillis, mEventBuffer, EVENT_BUFFER_SIZE);

    { // acquire lock
        AutoMutex _l(mLock);
        if (count) {  //有事件了，着手处理事件
            processEventsLocked(mEventBuffer, count);
        }
        ...
    } // release lock

    // Send out a message that the describes the changed input devices.
    if (inputDevicesChanged) {
        mPolicy->notifyInputDevicesChanged(inputDevices);
    }
    //将获得的事件传给InputDispatcher去做处理
    mQueuedListener->flush();
}
```
 
EventHub的getEvents函数太长，这里就不贴出来了， 它主要就是获得事件，这里的事件，并不单单指input事件，它还包括输入设备的add/remove等相关的事件. 

**获得输入设备加入和删除事件**

输入设备的加入和移除事件跟几个变量非常相关.
- mNeedToReopenDevices
   表示需要重新打开输入设备， 它会先close当前已经打开的设置做一些清理工作，具体参见closeAllDevicesLocked() 函数，将没有delete掉的设备用mClosingDevices来表示，最后会把 mNeedToScanDevices 置为true.
-  mClosingDevices
表示当前没有被delete掉的设备，这getEvents里就将这些设备依次删除掉, 并生成 DEVICE_REMOVED事件
- mNeedToScanDevices
该变量表示需要扫描输入设备，并打开输入设备，加入到mDevices中，用mOpeningDevices表示这些设备的Head
- mOpeningDevices
表示刚刚打开的所有的设备，它是一个单链表的HEAD, getEvents会将它所保存的所有刚打开的设备创建一个DEVICE_ADDED事件
- mNeedToSendFinishedDeviceScan
表示finish 扫描输入设备, 会生成一个FINISHED_DEVICE_SCAN事件

![](http://upload-images.jianshu.io/upload_images/5688445-af9b40635e067846.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
getEvents通过将产生的事件放到mEventBuffer所指向的一维数给中，然后通过最后一个事件的地址-mEventBuffer地址就可以得到当前有多少事件，很巧妙。

**获得输入设备的事件**
输入设备加入后，如果没有具体的事件产生的话，它就会进入epoll的阻塞状态。
```
    for (;;) {
        //处理变化的事件
        while (mPendingEventIndex < mPendingEventCount) {
            const struct epoll_event& eventItem = mPendingEventItems[mPendingEventIndex++];
            //针对EventHub::wake
            if (eventItem.data.u32 == EPOLL_ID_WAKE) {
                if (eventItem.events & EPOLLIN) {
                    awoken = true;
                    ...
                }
            }
            ssize_t deviceIndex = mDevices.indexOfKey(eventItem.data.u32);
            Device* device = mDevices.valueAt(deviceIndex);
            if (eventItem.events & EPOLLIN) {
                int32_t readSize = read(device->fd, readBuffer,
                        sizeof(struct input_event) * capacity);
                ...
                } else {
                    int32_t deviceId = device->id == mBuiltInKeyboardId ? 0 : device->id;
                    size_t count = size_t(readSize) / sizeof(struct input_event);
                    for (size_t i = 0; i < count; i++) {
                        struct input_event& iev = readBuffer[i]; //获得具体的输入事件
                        //将输入事件保存到mEventBuf中
                       event->deviceId = deviceId;
                        event->type = iev.type;
                        event->code = iev.code;
                        event->value = iev.value;
                        event += 1; //指向下一个事件
                        capacity -= 1;
        }
        //当有事件后就直接退出， awoken表示通过调用EventHub::wake函数唤醒epoll，也直接退出
        if (event != buffer || awoken) {
            break;
        }

        int pollResult = epoll_wait(mEpollFd, mPendingEventItems, EPOLL_MAX_EVENTS, timeoutMillis);
        if (pollResult < 0) {
        } else {
            // Some events occurred.
            mPendingEventCount = size_t(pollResult);
        }
    }
```
当有事件产生后，epoll_wait就会返回，将有改变的个数放到mPendingEventCount中， 再下一轮的for循环中, 就在while循环中处理变化的事件.

while循环其实挺简单，主要是通过从改变的输入设备中读取输入事件，然后保存到mEventBuf中，然后从getEvents返回。

**处理事件**

```
void InputReader::loopOnce() {  
    size_t count = mEventHub->getEvents(timeoutMillis, mEventBuffer, EVENT_BUFFER_SIZE);
        if (count) {    
            processEventsLocked(mEventBuffer, count); // 处理事件
        } 
    mQueuedListener->flush();
}
```
InputReader在有事件发生后，getEvents就会返回，如果返回的count > 0时，就着手处理这些事件

```
void InputReader::processEventsLocked(const RawEvent* rawEvents, size_t count) {
    for (const RawEvent* rawEvent = rawEvents; count;) {
        int32_t type = rawEvent->type;
        size_t batchSize = 1;
        if (type < EventHubInterface::FIRST_SYNTHETIC_EVENT) {
            int32_t deviceId = rawEvent->deviceId;
            while (batchSize < count) {
                if (rawEvent[batchSize].type >= EventHubInterface::FIRST_SYNTHETIC_EVENT
                        || rawEvent[batchSize].deviceId != deviceId) {
                    break;
                }
                batchSize += 1;
            }
            //处理输入事件
            processEventsForDeviceLocked(deviceId, rawEvent, batchSize);
        } else {
            switch (rawEvent->type) {
            case EventHubInterface::DEVICE_ADDED:
                //输入设备加入
                addDeviceLocked(rawEvent->when, rawEvent->deviceId);
                break;
            case EventHubInterface::DEVICE_REMOVED:
                //输入设备移出
                removeDeviceLocked(rawEvent->when, rawEvent->deviceId);
                break;
            case EventHubInterface::FINISHED_DEVICE_SCAN:
                //configuration 改变
                handleConfigurationChangedLocked(rawEvent->when);
                break;
            }
        }
        count -= batchSize;
        rawEvent += batchSize;
    }
}
```
processEventsLocked根据返回的那些事件依次处理，包括对输入设备的增加和移出，以及输入事件的处理。

这里依然以handleConfigurationChangedLocked为例
```
void InputReader::handleConfigurationChangedLocked(nsecs_t when) {
    NotifyConfigurationChangedArgs args(when);
    mQueuedListener->notifyConfigurationChanged(&args);
}

Vector<NotifyArgs*> mArgsQueue;
void QueuedInputListener::notifyConfigurationChanged(
        const NotifyConfigurationChangedArgs* args) {
    mArgsQueue.push(new NotifyConfigurationChangedArgs(*args));
}
```
handleConfigurationChangedLocked生成一个NotifyConfigurationChangedArgs然后通过QueuedListener，将NotifyConfigurationChangedArgs加入到mArgsQueue这个vector中

![](http://upload-images.jianshu.io/upload_images/5688445-bce3da45c20162d2.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


当InputReader::loopOnce()在处理完事件后会调用 mQueuedListener->flush();

```
void QueuedInputListener::flush() {
    size_t count = mArgsQueue.size();
    for (size_t i = 0; i < count; i++) {
        NotifyArgs* args = mArgsQueue[i];
        args->notify(mInnerListener);
        delete args;
    }
    mArgsQueue.clear();
}

void NotifyConfigurationChangedArgs::notify(const sp<InputListenerInterface>& listener) const {
    listener->notifyConfigurationChanged(this);
}
```
flush函数对mArgsQueue里所有的NotifyArgs，调用notify, 这里mInnerListener也就是InputDispatcher, 如NotifyConfigurationChangedArgs为例，调用InputDispatcher的notifyConfigurationChanged将事件传入到了InputDispatcher中了。

至此InputReader的工作模型就介绍完了。

![](http://upload-images.jianshu.io/upload_images/5688445-38650a0543d24702.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

### 1.3 Input java层与jni层相互调用
已经知道Input工作在三个线程中，一个java线程，两个jni线程(InputReader, InputDispatcher)

- **Java通过jni获得相关信息**

Java层通过各种 nativeXXX去获得jni中的相关信息，具体可以查询InputManagerService.java中的nativeXXX开头的函数， 如
```
nativeGetKeyCodeState()
nativeSetFocusedApplication()
```
它们对应的jni实现如下
```
static jint nativeGetSwitchState(JNIEnv* /* env */, jclass /* clazz */,
        jlong ptr, jint deviceId, jint sourceMask, jint sw) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    return (jint) im->getInputManager()->getReader()->getSwitchState(
            deviceId, uint32_t(sourceMask), sw);
}
```
```
static void nativeSetFocusedApplication(JNIEnv* env, jclass /* clazz */,
        jlong ptr, jobject applicationHandleObj) {
    NativeInputManager* im = reinterpret_cast<NativeInputManager*>(ptr);

    im->setFocusedApplication(env, applicationHandleObj);
}
void NativeInputManager::setFocusedApplication(JNIEnv* env, jobject applicationHandleObj) {
    sp<InputApplicationHandle> applicationHandle =
            android_server_InputApplicationHandle_getHandle(env, applicationHandleObj);
    mInputManager->getDispatcher()->setFocusedApplication(applicationHandle);
}
```
如图，它们都是通过NativeInputManager去获得InputDispatcher或InputReader去做相应的处理，**注意，**这些都是在java线程中调用的，为了线程安全，在相应的实现中都有锁。

- **Jni回调java接口**

同样InputDispatcher和InputReader线程都有可能调用到java层的接口，具体就不多说了。

# 二、Android Input与App
第一节已经基本说了下Android Input的代码结构，input的作用就是获得输入设备产生的事件，并且分发出来，那分发到哪里去了呢？ 当然是分发到了Focused的App里了。

```
                if ((mWindowAttributes.inputFeatures
                        & WindowManager.LayoutParams.INPUT_FEATURE_NO_INPUT_CHANNEL) == 0) {
                    mInputChannel = new InputChannel(); //生成一个InputChannel
                }
                ...
                try {
                    ...
                    res = mWindowSession.addToDisplay(mWindow, mSeq, mWindowAttributes,
                            getHostVisibility(), mDisplay.getDisplayId(),
                            mAttachInfo.mContentInsets, mAttachInfo.mStableInsets,
                            mAttachInfo.mOutsets, mInputChannel);
```
App在addView时，会在ViewRootImpl中生成InputChannel， InputChannel实现了Parcelable， 所以它可以通过Binder传输。具体是通过addDisplay()将当前window加入到WMS中管理，同时也会有相应的input的处理.

## 2.1 SystemServer端中WMS的addWindow
```
public int addWindow(Session session, IWindow client, ... InputChannel outInputChannel) {
            final boolean openInputChannels = (outInputChannel != null
                    && (attrs.inputFeatures & INPUT_FEATURE_NO_INPUT_CHANNEL) == 0);
            if  (openInputChannels) {
                win.openInputChannel(outInputChannel);
            }
}
```
addWindow会通过WindowState去openInputChannel()
```
    void openInputChannel(InputChannel outInputChannel) {
        String name = getName();
        InputChannel[] inputChannels = InputChannel.openInputChannelPair(name);
        mInputChannel = inputChannels[0];
        mClientChannel = inputChannels[1];
        mInputWindowHandle.inputChannel = inputChannels[0];
        if (outInputChannel != null) {
            mClientChannel.transferTo(outInputChannel);
            mClientChannel.dispose();
            mClientChannel = null;
        } else {
        }
        mService.mInputManager.registerInputChannel(mInputChannel, mInputWindowHandle);
    }
```
openInputChannel做了三件事，
一是通过openInputChannelPair也就是nativeOpenInputChannelPair去打开一组socket用于跨进程通信. 具体可以参考
```
android_view_InputChannel_nativeOpenInputChannelPair ->  InputChannel::openInputChannelPair()
```
- **创建一对socket pair**
![](http://upload-images.jianshu.io/upload_images/5688445-55d0713c9955f21b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- **transfer给outInputChannel**
```
        if (outInputChannel != null) {
            mClientChannel.transferTo(outInputChannel);
            mClientChannel.dispose();
            mClientChannel = null;
```
![](http://upload-images.jianshu.io/upload_images/5688445-2e9b6700f8bace92.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


- **注册InputChannel和InputWindowHandle给Input**

```
mService.mInputManager.registerInputChannel(mInputChannel, mInputWindowHandle);
```
![](http://upload-images.jianshu.io/upload_images/5688445-5c8581b5a2edae47.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
在registerInputChannel后，**InputDispatcher**就开始监听App在Server端的InputChannel了。

## 2.2 App端获得InputChannel
SystemServer端生成的InputChannel是在SystemServer进程中，App进程不能直接访问其地址，那App是怎么获得InputChannel的呢？ 当然是通过Binder了.
App的ViewRootImpl在调用addToDisplay返回后，ViewRootImpl里的InputChannel就指向了正确的InputChannel, 它是Client端，即Client端的fd与SystemServer进程中Server端的fd组成 socket pair, 它们就可以双向通信了。 那App端的InputChannel是如何正确的Client的InputChannel呢？

在 IWindowSession类中
```
            public int addToDisplay(... android.view.InputChannel outInputChannel)  {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                    ...
                    mRemote.transact(Stub.TRANSACTION_addToDisplay, _data, _reply, 0);
                    ...
                    if ((0 != _reply.readInt())) {
                        outInputChannel.readFromParcel(_reply);
                    }
            }
```
Binder Proxy端(App)端中 ViewRootImpl中的InputChannel是从Parcel里读出来的。

```
        public boolean onTransact(...) {
                case TRANSACTION_addToDisplay: {
                    ...
                    android.view.InputChannel _arg8;
                    _arg8 = new android.view.InputChannel();
                    int _result = this.addToDisplay(...);
                    reply.writeNoException();
                    reply.writeInt(_result);
                    ...
                    if ((_arg8 != null)) {
                        reply.writeInt(1);
                        _arg8.writeToParcel(reply, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                    } else {
                        reply.writeInt(0);
                    }
                    return true;
                }
```
Binder Server端(SystemServer)在 onTransact里生成一个局部的InputChannel，在addDisplay处理完后，就将InputChannel序列化到Parcel中传递到App端.
```
static void android_view_InputChannel_nativeWriteToParcel(JNIEnv* env, jobject obj,
        jobject parcelObj) {
    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel) {
        NativeInputChannel* nativeInputChannel =
                android_view_InputChannel_getNativeInputChannel(env, obj);
        if (nativeInputChannel) {
            sp<InputChannel> inputChannel = nativeInputChannel->getInputChannel();

            parcel->writeInt32(1);
            parcel->writeString8(inputChannel->getName());
            parcel->writeDupFileDescriptor(inputChannel->getFd());
        } else {
            parcel->writeInt32(0);
        }   
    }   
}
```
序列化的过程其实就三个，写name, 然后writeDupFileDescriptor, dup文件句柄。

反序列化过程
```

static void android_view_InputChannel_nativeReadFromParcel(JNIEnv* env, jobject obj,
        jobject parcelObj) {
    if (android_view_InputChannel_getNativeInputChannel(env, obj) != NULL) {
        jniThrowException(env, "java/lang/IllegalStateException",
                "This object already has a native input channel.");
        return;
    }

    Parcel* parcel = parcelForJavaObject(env, parcelObj);
    if (parcel) {
        bool isInitialized = parcel->readInt32();
        if (isInitialized) {
            String8 name = parcel->readString8();
            int rawFd = parcel->readFileDescriptor();
            int dupFd = dup(rawFd);
            if (dupFd < 0) {
                return;
            }

            InputChannel* inputChannel = new InputChannel(name, dupFd);
            NativeInputChannel* nativeInputChannel = new NativeInputChannel(inputChannel);
            android_view_InputChannel_setNativeInputChannel(env, obj, nativeInputChannel);
        }
    }
}
```
反序化就是在 App端 native层生成一个InputChannel，然后dup 文件句柄，设置等等
![](http://upload-images.jianshu.io/upload_images/5688445-7558f0ec796bc400.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 2.3 App端注册InputChannel到Looper
通过 2.2 小节，App端已经获得了InputChannel，以及正确的socket fd. 那要怎么利用起来呢？

ViewRootImpl在addDisplay后，会生成一个`WindowInputEventReceiver`
```
                if (mInputChannel != null) {
                    ...
                    mInputEventReceiver = new WindowInputEventReceiver(mInputChannel,
                            Looper.myLooper());
                }
```
Looper.myLooper()是App进程Main线程的Looper.

```
    public InputEventReceiver(InputChannel inputChannel, Looper looper) {
        mInputChannel = inputChannel;
        mMessageQueue = looper.getQueue();
        mReceiverPtr = nativeInit(new WeakReference<InputEventReceiver>(this),
                inputChannel, mMessageQueue);

        mCloseGuard.open("dispose");
    }
```
WindowInputEventReceiver会调用父类InputEventReceiver构造函数，然后通过nativeInit函数将InputChannel的fd加入到Looper的epoll中去。

```
static jlong nativeInit(JNIEnv* env, jclass clazz, jobject receiverWeak,
        jobject inputChannelObj, jobject messageQueueObj) {
    sp<InputChannel> inputChannel = android_view_InputChannel_getInputChannel(env,
            inputChannelObj);
    sp<MessageQueue> messageQueue = android_os_MessageQueue_getMessageQueue(env, messageQueueObj);
    sp<NativeInputEventReceiver> receiver = new NativeInputEventReceiver(env,
            receiverWeak, inputChannel, messageQueue);
    status_t status = receiver->initialize();

    receiver->incStrong(gInputEventReceiverClassInfo.clazz); // retain a reference for the object
    return reinterpret_cast<jlong>(receiver.get());
}
```
```
status_t NativeInputEventReceiver::initialize() {
    setFdEvents(ALOOPER_EVENT_INPUT);
    return OK;
}
void NativeInputEventReceiver::setFdEvents(int events) {
    if (mFdEvents != events) {
        mFdEvents = events;
        int fd = mInputConsumer.getChannel()->getFd();
        if (events) {
            mMessageQueue->getLooper()->addFd(fd, 0, events, this, NULL);
        } else {
            mMessageQueue->getLooper()->removeFd(fd);
        }
    }
}
```
也就是说在 App进程的Main线程的Looper中监听InputChannel的Client端。当有事件发生时，Looper就会回调 NativeInputEventReceiver::handleEvent()
![](http://upload-images.jianshu.io/upload_images/5688445-8096362d1f03cb8e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 2.4 小节
App通过Binder获得InputChannel的client端，然后将fd加入到App进程的Main线程中监听。

# 三、input事件的传递流程
## 3.1 Input事件传递Overview
在了解了input框架和App端与Input的关系后，input的按键等相关事件的传递过程就相当简单了。
![Keyevent.png](http://upload-images.jianshu.io/upload_images/5688445-36a726b0ce9ed6c4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
图中绿色方块表示调用java的方法.

InputReader在将事件加入到mInboundQueue之前会尝试interceptKey, 如果按键被截获成功，那么在InputDispatcher的红色块会被drop掉
以及filterInputEvent. 如果filter成功，那在InputReader线程中就直接返回，不会再将Event传递到InputDispatcher中.

另外
![](http://upload-images.jianshu.io/upload_images/5688445-f437211e6f85e20b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
一个InputDevice可支持多种Mapper, 取决于mClasses的值， 具体是在
```
InputDevice* InputReader::createDeviceLocked(int32_t deviceId, int32_t controllerNumber,
        const InputDeviceIdentifier& identifier, uint32_t classes) {
    InputDevice* device = new InputDevice(&mContext, deviceId, bumpGenerationLocked(),
            controllerNumber, identifier, classes);
    ...
    // Vibrator-like devices.
    if (classes & INPUT_DEVICE_CLASS_VIBRATOR) {
        device->addMapper(new VibratorInputMapper(device));
    }    

    // Keyboard-like devices.
    uint32_t keyboardSource = 0; 
    int32_t keyboardType = AINPUT_KEYBOARD_TYPE_NON_ALPHABETIC;
    if (classes & INPUT_DEVICE_CLASS_KEYBOARD) {
        keyboardSource |= AINPUT_SOURCE_KEYBOARD;
    }    
    if (classes & INPUT_DEVICE_CLASS_ALPHAKEY) {
        keyboardType = AINPUT_KEYBOARD_TYPE_ALPHABETIC;
    }    
    if (classes & INPUT_DEVICE_CLASS_DPAD) {
        keyboardSource |= AINPUT_SOURCE_DPAD;
    }    
    if (classes & INPUT_DEVICE_CLASS_GAMEPAD) {
        keyboardSource |= AINPUT_SOURCE_GAMEPAD;
    }

    if (keyboardSource != 0) {
        device->addMapper(new KeyboardInputMapper(device, keyboardSource, keyboardType));
    }
    ...
```

## 3.2 Native中Focused的App与Window

![](http://upload-images.jianshu.io/upload_images/5688445-2b2ffc2228107c3a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

InputApplicationHandle表示的是一个Focused的Application
InputWindowHandle表示是当前系统中所有的Window, 当然这里是指可以接收Input事件的窗口, 它可以是多个，只不过只一个当前获得焦点的窗口。

### 3.1 设置Focused 的 InputApplicationHandle
当一个App resumed后，AMS就会调用setResumedActivityUncheckLocked去更新AMS的一些状态,  接着通知WMS去setFocusedApp
![](http://upload-images.jianshu.io/upload_images/5688445-9e6230c9c45c24f1.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

### 3.2 设置Focused 的 InputWindowHandle
![](http://upload-images.jianshu.io/upload_images/5688445-341360851398769f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
void NativeInputManager::setInputWindows(JNIEnv* env, jobjectArray windowHandleObjArray) {
    Vector<sp<InputWindowHandle> > windowHandles; //保存所有从JAVA层传入的InputWindowHandle

    if (windowHandleObjArray) {
        jsize length = env->GetArrayLength(windowHandleObjArray);
        for (jsize i = 0; i < length; i++) {
            jobject windowHandleObj = env->GetObjectArrayElement(windowHandleObjArray, i);
            //获得一个InputWindowHandle
            sp<InputWindowHandle> windowHandle =
                    android_server_InputWindowHandle_getHandle(env, windowHandleObj);
            if (windowHandle != NULL) {
                //保存到windowHandles里
                windowHandles.push(windowHandle);
            }    
        }    
    }    

    mInputManager->getDispatcher()->setInputWindows(windowHandles);
```
```
void InputDispatcher::setInputWindows(const Vector<sp<InputWindowHandle> >& inputWindowHandles) {
    { // acquire lock
        AutoMutex _l(mLock);
        //获得旧的所有的window的InputWindowHandle
        Vector<sp<InputWindowHandle> > oldWindowHandles = mWindowHandles;
        mWindowHandles = inputWindowHandles; //保存所有InputWindowHandle

        sp<InputWindowHandle> newFocusedWindowHandle;
        bool foundHoveredWindow = false;
        for (size_t i = 0; i < mWindowHandles.size(); i++) {
            const sp<InputWindowHandle>& windowHandle = mWindowHandles.itemAt(i);
            //updateInfo这里是去获得java层对应的InputWindowHandle的值, 并保存到InputWindowInfo里
            //只有当InputWindowHandle里有InputChannel时，这个Window才可能接收 input事件
            if (!windowHandle->updateInfo() || windowHandle->getInputChannel() == NULL) {
                mWindowHandles.removeAt(i--);
                continue;
            }
            //只有当InputWindowHandle hasFocus值为true时，此时将会改变focused InputWindowHandle
            if (windowHandle->getInfo()->hasFocus) {
                newFocusedWindowHandle = windowHandle;
            }    
        }    

        if (mFocusedWindowHandle != newFocusedWindowHandle) {
            if (mFocusedWindowHandle != NULL) {
               // Focused InputWindowHandle改变了，此时会cancel掉上一个Focused的Window的事件
                sp<InputChannel> focusedInputChannel = mFocusedWindowHandle->getInputChannel();
                if (focusedInputChannel != NULL) {
                    CancelationOptions options(CancelationOptions::CANCEL_NON_POINTER_EVENTS,
                            "focus left window");
                    synthesizeCancelationEventsForInputChannelLocked(
                            focusedInputChannel, options);
                }    
            }    
            //指向最新的Focused InputWindowHandle
            mFocusedWindowHandle = newFocusedWindowHandle;
        }
        //release没在mWindowsHandle里的旧的InputWindowHandle的信息
        for (size_t i = 0; i < oldWindowHandles.size(); i++) {
            const sp<InputWindowHandle>& oldWindowHandle = oldWindowHandles.itemAt(i);
            if (!hasWindowHandleLocked(oldWindowHandle)) {
                oldWindowHandle->releaseInfo();
            }
        }
    } // release lock

    // Wake up poll loop since it may need to make new input dispatching choices.
    mLooper->wake();
}
```
从代码中可以看出，将java层中所有的InputWindowHandle都会加入到InputDispatcher里来保存，然后遍历所有的InputWindowHandle，根据其是否获得了焦点来将它设置为mFocusedWindowHandle 

特别注意的是，InputWindowHandle里的InputWindowInfo的值都是通过获得Java层对应的InputWindowHandle的值。具体可以参见 `NativeInputWindowHandle::updateInfo()`

### 3.3 找到Focused的App与Window
由3.1, 3.2小节的知识，找到Focused的App与Window就非常简单了，为什么需要找到这两个呢？因为当前有输入事件，输入事件需要传递给当前获得焦点的App的窗口.
```
int32_t InputDispatcher::findFocusedWindowTargetsLocked(nsecs_t currentTime,
        const EventEntry* entry, Vector<InputTarget>& inputTargets, nsecs_t* nextWakeupTime) {
    int32_t injectionResult;
    String8 reason;

    //当前Focused的App是否正在add window, 意思是还没有Focused的window
    if (mFocusedWindowHandle == NULL) {
        if (mFocusedApplicationHandle != NULL) {
            injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                    mFocusedApplicationHandle, NULL, nextWakeupTime,
                    "Waiting because no window has focus but there is a "
                    "focused application that may eventually add a window "
                    "when it finishes starting up.");

            goto Unresponsive;
        }
        //当前也没有Focused的 App
        injectionResult = INPUT_EVENT_INJECTION_FAILED;
        goto Failed;
    }

    // Check permissions.  //检查是否具体INJECT_EVENT的权限
    if (! checkInjectionPermission(mFocusedWindowHandle, entry->injectionState)) {
        goto Failed;
    }

    // Check whether the window is ready for more input.
   //进一步检查是否需要 drop
    reason = checkWindowReadyForMoreInputLocked(currentTime,
            mFocusedWindowHandle, entry, "focused");
    if (!reason.isEmpty()) { //如果 reason不为空，就drop掉
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplicationHandle, mFocusedWindowHandle, nextWakeupTime, reason.string());
        goto Unresponsive;
    }

    // Success!  Output targets.
    injectionResult = INPUT_EVENT_INJECTION_SUCCEEDED;
    //找到正确的InputChannel，加入到InputTargets中
    addWindowTargetLocked(mFocusedWindowHandle,
            InputTarget::FLAG_FOREGROUND | InputTarget::FLAG_DISPATCH_AS_IS, BitSet32(0),inputTargets);

    // Done.
Failed:
Unresponsive:
    ...
    return injectionResult;
}
```
经过findFocusedWindowTargetsLocked后就找到了正确的InputChannel, 然后通过socket通信就将事件传输到App端了。

### 3.4 ANR是怎么发生的？
![ANR](http://upload-images.jianshu.io/upload_images/5688445-fc383657c8474777.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
可以看出ANR发生的源头是 handleTargetsNotReadyLocked. 从字面上来看应该是InputTargets还没有Ready，它主要是在3.3中的findFocusedWindowTargetsLocked中调用

情况一：mFocusedApplicationHandle != null, mFocusedWindowHandle== null

情况二：mFocusedApplicationHandle 与 mFocusedWindowHandle都不空的情况
 - 当前Focused的window  PAUSED了
 - Focused的window的 Connection都没有，也就是还没有注册
 - Focused的window的 Connection 不正常
 - Focused的window的 Connection里塞满了输入事件, 还在等着App去finish掉事件
 - 针对 KeyEvent情况，必须上一个事件完成了才行
 - 针对Touch事件的情况

### 3.4.1 正常事件的流程
以 Key事件为例,  Key事件按下是ACTION_DOWN, 抬起是ACTION_UP, 现在来看下这两个事件的正常流程如下.

1. 先将事件加入到outBoundQueue,然后publishKeyEvent到App Main线程
![image.png](http://upload-images.jianshu.io/upload_images/5688445-68f6e2ca2e65ceca.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
2. 然后立马将A事件从outboundQueue中剥离，加入到waitQueue中
![image.png](http://upload-images.jianshu.io/upload_images/5688445-20344ed74c25ef19.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
3. App线程处理完按键事件了
App线程会调用nativeFinishInputEvent，进一步调用 sendFinishedSignal 向 SystemServer发送哪个按键事件已经被finish, 最后从waitQueue中移出掉事件
![image.png](http://upload-images.jianshu.io/upload_images/5688445-fde27f8672b80941.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

### 3.4.2 ANR发生
**1. 假设 App 在处理 A事件(ACTION_DOWN), 没有返回。**
![image.png](http://upload-images.jianshu.io/upload_images/5688445-62978122e2b809e8.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

**2. 这时来了一个B事件(ACTION_UP).**
这时findFocusedWindowTargetsLocked在checkWindowReadyForMoreInputLocked时发现waitQueue里不为空，这时就要调用handleTargetsNotReadyLocked了
```
    reason = checkWindowReadyForMoreInputLocked(currentTime,
            mFocusedWindowHandle, entry, "focused");
    if (!reason.isEmpty()) {
        injectionResult = handleTargetsNotReadyLocked(currentTime, entry,
                mFocusedApplicationHandle, mFocusedWindowHandle, nextWakeupTime, reason.string());
        goto Unresponsive;
    }
```

```
int32_t InputDispatcher::handleTargetsNotReadyLocked(...) {
    if (applicationHandle == NULL && windowHandle == NULL) {
       //一般不会进入该分支，这个情况一般是系统刚启动或systemserver重启的情况
    } else {
       // mInputTargetWaitCause 默认情况下是INPUT_TARGET_WAIT_CAUSE_NONE
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY) {
            nsecs_t timeout;
           //获得超时时间，默认是5s
            if (windowHandle != NULL) {
                timeout = windowHandle->getDispatchingTimeout(DEFAULT_INPUT_DISPATCHING_TIMEOUT);
            } else if (applicationHandle != NULL) {
                timeout = applicationHandle->getDispatchingTimeout(
                        DEFAULT_INPUT_DISPATCHING_TIMEOUT);
            } else {
                timeout = DEFAULT_INPUT_DISPATCHING_TIMEOUT;
            }
           //设置cause为 APPLICATION_NOT_READY状态
            mInputTargetWaitCause = INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY;
            mInputTargetWaitStartTime = currentTime;
            //超时时间是dispatch这个事件的时间+5
            mInputTargetWaitTimeoutTime = currentTime + timeout;
            mInputTargetWaitTimeoutExpired = false;

            //重新设置正确的mInputTargetWaitApplicationHandle
            mInputTargetWaitApplicationHandle.clear();

            if (windowHandle != NULL) {
                mInputTargetWaitApplicationHandle = windowHandle->inputApplicationHandle;
            }
            if (mInputTargetWaitApplicationHandle == NULL && applicationHandle != NULL) {
                mInputTargetWaitApplicationHandle = applicationHandle;
            }
        }
    }
    // 并不会进入，这里显示mInputTargetWaitTimeoutTime = current + 5s
    if (currentTime >= mInputTargetWaitTimeoutTime) {
        onANRLocked(currentTime, applicationHandle, windowHandle,
                entry->eventTime, mInputTargetWaitStartTime, reason);
        ...
    } else {
        return INPUT_EVENT_INJECTION_PENDING;
    }
}
```
这时候B事件就会设置正确的mInputTargetWaitCause, mInputTargetWaitApplicationHandle, mInputTargetWaitTimeoutTime.

如果隔了5s后，App Main线程还是没有返回，这时再来了一个C 事件，此时，在handleTargetsNotReadyLocked里就要发生ANR了
```
int32_t InputDispatcher::handleTargetsNotReadyLocked(...) {
    if (applicationHandle == NULL && windowHandle == NULL) {
       //一般不会进入该分支，这个情况一般是系统刚启动或systemserver重启的情况
    } else {
       // 此时的mInputTargetWaitCause 是INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY
      //不会进入该分支
        if (mInputTargetWaitCause != INPUT_TARGET_WAIT_CAUSE_APPLICATION_NOT_READY) {
          ...
        }
    }
    // 由于是隔了5s 左右，此时currentTime 就 大于 mInputTargetWaitTimeoutTime, 这时就要产生ANR了
    if (currentTime >= mInputTargetWaitTimeoutTime) {
        onANRLocked(currentTime, applicationHandle, windowHandle,
                entry->eventTime, mInputTargetWaitStartTime, reason);
        ...
    } else {
        return INPUT_EVENT_INJECTION_PENDING;
    }
}
```

**3. 假设A事件(ACTION_DOWN)在5s内被consumed了， 那2中就不会发生ANR，那2中的B事件是何时在dispatch出去的呢？**
```
void InputDispatcher::dispatchOnceInnerLocked(nsecs_t* nextWakeupTime) {
    ...
        done = dispatchKeyLocked(currentTime, typedEntry, &dropReason, nextWakeupTime);
    ...
    if (done) {
        if (dropReason != DROP_REASON_NOT_DROPPED) {
            dropInboundEventLocked(mPendingEvent, dropReason);
        }
        mLastDropReason = dropReason;

        releasePendingEventLocked();
        *nextWakeupTime = LONG_LONG_MIN;  // force next poll to wake up immediately
    }
}
```
```
bool InputDispatcher::dispatchKeyLocked(nsecs_t currentTime, KeyEntry* entry,
        DropReason* dropReason, nsecs_t* nextWakeupTime) {
    ...
    Vector<InputTarget> inputTargets;
    int32_t injectionResult = findFocusedWindowTargetsLocked(currentTime,
            entry, inputTargets, nextWakeupTime);
    if (injectionResult == INPUT_EVENT_INJECTION_PENDING) {
        return false;
    }
```
如果findFocusedWindowTargetsLocked返回为INPUT_EVENT_INJECTION_PENDING, 那dispatchKeyLocked就直接返回false, 在本例中，此处返回 false.
所以dispatchOnceInnerLocked并不会处理 if (done)， 这就意思着不会调用releasePendingEventLocked，进而mPendingEvent也不会被置为NULL, 这样下一轮dispatchOnceInnerLocked中就会发现mPendingEvent不为NULL, 就继续dispatch上一次没有被dispatch出去的Event, 好巧妙。

# 四、小结
1. Input工作于三个线程, android.display, InputReader, InputDispatcher线程
2. Input与App的通信是通过socket.
3. InputReader使用EventHub里的epoll机制， InputDispatcher使用Looper中的epoll机制
4. App在consume掉input事件后，会通过nativeFinishInputEvent去通知Input移出到waitQueue里等待的事件，防止ANR.
5. ANR的发生需要三个事件，第一个事件，让App线程处理，且 App线程不返回, 第二个事件开始计时，默认5s, 第三个事件在5s结束后来到，此时产生ANR
