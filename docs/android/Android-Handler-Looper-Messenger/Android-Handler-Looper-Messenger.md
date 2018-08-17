![Handler类图](http://upload-images.jianshu.io/upload_images/5688445-b413aa2309a69101.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 一、Looper模型建立
当一个线程在run方法调用Looper的以下操作后
```
Looper.prepare()
Looper.loop()
```
Looper/Handler的模型就建立起来了
![Looper模型](http://upload-images.jianshu.io/upload_images/5688445-a21174a00478c257.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

这里需要注意是在 MessageQueue.next()函数中
```
        int nextPollTimeoutMillis = 0;
        for (;;) {
            nativePollOnce(ptr, nextPollTimeoutMillis);
           ...
```
第一次进去传入到epoll_wait的timeout为0，也就是说立马返回POLL_TIMEOUT。

pollInner中会返回epoll唤醒的原因, 默认有如下几种情况
- POLL_WAKE  = -1
默认情况
- POLL_ERROR = -2
epoll_wait返回错误
- POLL_TIMEOUT = -3
epoll是因为超时唤醒
- POLL_CALLBACK = -4
epoll唤醒是有具体的事件发生

pollInner在epoll唤醒后会根据唤醒原因决定是否需要继续 epoll_wait, 在上述的情况下pollInner会返回，进而 java 层的nativePollOnce也就返回了。

java层的next在nativePollOnce后发现并没有Message，所以此时计算得到 `nextPollTimeoutMillis = -1`, 然后再次触发 nativePollOnce,会造成epoll_wait无限的等待下去。

# 二、Java层发送Message
线程在建立起Looper模型后，就可以往Handler发送Message了。
Handler调用sendMessage最终都是通过
```
    public boolean sendMessageAtTime(Message msg, long uptimeMillis) {
        MessageQueue queue = mQueue;
        if (queue == null) {
            return false;
        }
        return enqueueMessage(queue, msg, uptimeMillis);
    }
    private boolean enqueueMessage(MessageQueue queue, Message msg, long uptimeMillis) {
        msg.target = this; //设置target Handler
        if (mAsynchronous) {
            msg.setAsynchronous(true);
        }
        return queue.enqueueMessage(msg, uptimeMillis);
    }
    boolean enqueueMessage(Message msg, long when) {
        ...
        synchronized (this) {
            if (mQuitting) { //是否正在退出
                msg.recycle();
                return false;
            }

            msg.markInUse();  //标记当前Message正在使用
            msg.when = when; //什么时候发生
            Message p = mMessages; //获得 Message 头部节点
            boolean needWake;
            if (p == null || when == 0 || when < p.when) {
               //第一个节点
                // New head, wake up the event queue if blocked.
                msg.next = p;
                mMessages = msg;
                needWake = mBlocked; //mBlocked在没有消息处理时都为true
            } else {
                // Inserted within the middle of the queue.  Usually we don't have to wake
                // up the event queue unless there is a barrier at the head of the queue
                // and the message is the earliest asynchronous message in the queue.
                needWake = mBlocked && p.target == null && msg.isAsynchronous();
                Message prev;
                //将当前Message按照when时间插入到对应位置
                for (;;) {
                    prev = p;
                    p = p.next;
                    if (p == null || when < p.when) {
                        break;
                    }
                    if (needWake && p.isAsynchronous()) {
                        needWake = false;
                    }
                }
                msg.next = p; // invariant: p == prev.next
                prev.next = msg;
            }

            // We can assume mPtr != 0 because mQuitting is false.
            if (needWake) {  // 唤醒
                nativeWake(mPtr);
            }
        }
        return true;
    }
```
Handler发送消息，主要是将消息保存到 mMessages中，注意 Message本身是一个单身链表，当mMessages里的消息是按照被调用处理的时间排序的，从小到大，也就是排在最前的消息优先被处理。
当消息被插入时，然后根据当前情况(mBlocked)来决定是否需要唤醒epoll

![Java层发送Message](http://upload-images.jianshu.io/upload_images/5688445-a8154b2367d1f74d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

如图所示
- 1. java层首先将Message按照时间顺序保存到mMessages中
- 2. 然后调用nativeWake去唤醒epoll, (前提是当前是IDLE的状态)
唤醒的方法其实很简单就是往mWakeFd时写入一些数据，然后Epoll就监听到了mWakeFd事件
- 3. epoll在被唤醒后 依次遍历 native MessageEnvelopes. 以及一些LooperCalllback的callbacks
- 4. pollInner返回 -> nativePollOnce返回，
- 5. Looper.loop获得MessageQueue的Message,然后dispatchMessage.

# 三、Native 发送消息
从前面第二小节可以看出，epoll在被唤醒后，会先依次处理 native的 MessageEnvelopes里的消息。 现在来看下 native MessageEnvelopes是怎么构建的。

在Looper.cpp里支持多种sendMessage形式的消息发送，但是最终都是调用到 sendMessageAtTime()这个函数

```
void Looper::sendMessageAtTime(nsecs_t uptime, const sp<MessageHandler>& handler,
     const Message& message) {
    size_t i = 0;
    { // acquire lock
        AutoMutex _l(mLock);
       //按照时间顺序插入到 mMessageEnvelopes中
        size_t messageCount = mMessageEnvelopes.size();
        while (i < messageCount && uptime >= mMessageEnvelopes.itemAt(i).uptime) {
            i += 1;
        }   

        MessageEnvelope messageEnvelope(uptime, handler, message);
        mMessageEnvelopes.insertAt(messageEnvelope, i, 1); 
        if (mSendingMessage) {
            return;
        }   
    } // release lock

    // Wake the poll loop only when we enqueue a new message at the head.
    if (i == 0) {
        wake();
    }   
}
```
从上面可以看出， native也是支持Looper/Handler消息模型的，具体的用法可以参考 SurfaceFlinger里面的 dispatchRefresh 方法
http://androidxref.com/8.0.0_r4/xref/frameworks/native/services/surfaceflinger/MessageQueue.cpp#51

![Native send message](http://upload-images.jianshu.io/upload_images/5688445-962166cba7d6c624.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
如图所示
- 1. 先向 mMessageEnvelopes 中按照时间顺序插入Message
- 2. 如果mMessageEnvelopes里没有消息，当前插入的是第一个，则调用wake唤醒 epoll
- 3. epoll在唤醒后，会遍历 mMessageEnvelopes里的消息，依次处理。

**这里需要注意**：
只有当前的Message是第一个时才调用wake，而非第一个，则不会唤醒。这个是什么原因呢？？？ 如果当前插入的message没有delay时，岂不时会有delay???

# 四、addFd

由前面可知，Looper/Handler的模型主要是依靠不断的唤醒epoll来实现的，主要的手段是唤醒 mWakeFd, 或者是epoll的timeout, timeout手段主要是针对 sendMessageDelayed()这样的函数。

其实 epoll 的唤醒除了 mWakeFd的唤醒和epoll的timeout外，还支持监听其它事件。
主要是通过Looper::addFd来实现的。

```
int Looper::addFd(int fd, int ident, int events, Looper_callbackFunc callback, void* data) {
    return addFd(fd, ident, events, callback ? new SimpleLooperCallback(callback) : NULL, data);
}
int Looper::addFd(int fd, int ident, int events, const sp<LooperCallback>& callback, void* data) {
     ...
    { // acquire lock
        AutoMutex _l(mLock);

        Request request;  //创建Request
        request.fd = fd;
        request.ident = ident;
        request.events = events;
        request.seq = mNextRequestSeq++;
        request.callback = callback;
        request.data = data;
        if (mNextRequestSeq == -1) mNextRequestSeq = 0; // reserve sequence number -1

        struct epoll_event eventItem; //创建epoll_event
        request.initEventItem(&eventItem); 

        ssize_t requestIndex = mRequests.indexOfKey(fd);
        if (requestIndex < 0) {
            int epollResult = epoll_ctl(mEpollFd, EPOLL_CTL_ADD, fd, & eventItem);
            ...
            mRequests.add(fd, request);  //将Request加入到mRequests中
     ....
}
```
从上面的代码可以看出addFd就是创建一个Request，并添加到epoll监听池中，并插入到mRequests中。
其中调用到addFd的可以是native的code, 也可以java层的代码,流程如下

![addFd](http://upload-images.jianshu.io/upload_images/5688445-e77612fdc5ffb91b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- 1. native层addFd  监听 fd1
- 2. java层通过MessageQueue调用nativeSetFileDescriptorEvents去监听 fd2
- 3. 当fd1和fd2分别有事件发生时，此时epoll将被被唤醒
- 4. epoll检测出来发现是fd1, fd2事件发生了，
- 5. 然后将它们作为Response放入到mResponses中，
- 6. 针对fd1,  调用 LooperCallback里的handleEvent事件
- 7. 针对fd2, 调用到Java层的handleEvent()

# 五、Messenger
Messenger是AIDL和Handler的结合体，所以如果事先不了解AIDL的话，直接去学习Messenger就会觉得比较卡。

![Messenger](https://upload-images.jianshu.io/upload_images/5688445-5615155d09f0fa4e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

Messenger是AIDL的封装，底层是通过AIDL进行跨进程通信，只不过可以不用像AIDL那样定义AIDL这么麻烦，Messenger直接使用IMessenger.aidl，不通的消息可以通过Message.what来区分，这样会比较简单。注意: Messenger服务端的的处理是放在一个线程里的，而不是在binder线程中操作的，原理很简单，就是在binder线程中，将Message发送给Handler所在的线程。
**注意**， 这里的Handler线程并不一定是在UI线程中(大多数情况是在UI线程中), 可以是自己定义的非UI线程里的Handler, (如HandlerThread.getLooper())

Messenager和AIDL相比较,
- Messenager很简单，不用自定义AIDL, 它可以通过Message.what来区分不同的消息。
- Messenager将所有的消息都route到一个线程中操作，这里不用考虑同步的需求。而AIDL收到的消息都是在binder线程中，如果想在binder线程中操作，势必会考虑同步的问题

# 六、 小结
一般App开发主要只用到Handler/Looper的Message处理，但是其实Handler/Looper的功能远大于此, 支持jni层的Message， 支持fd 监听
它们的处理顺序如下
Jni Messages -> fd event -> Java Message
