> EventBus 是一种 publish/subscribe 事件总线，适用于 Android和Java.

[EventBus](https://github.com/greenrobot/EventBus)

![EventBus Overview](https://upload-images.jianshu.io/upload_images/5688445-6f0dc84d3c69a2d8.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

EventBus 涉及两个步骤，
- register 就是注册事件接收者， 
- post publish事件。

register(Test.this), 将Test类中所有定义了**@subscribe**的函数通过反射查找出放到一个**subscriptionsByEventType Map**中, 该Map的key是Event Type类型，如图中所示，分别为 Bar.class和Foo.class. 而该 Map的 Value对应的是一个List, 也就是说同一种Event Type可以定义多个接收函数, 这些接收函数存放在一个List里，最后保存到Map里。

post(new Bar()), Publish一个Event Type为 Bar对象，从**subscriptionsByEventType Map**通过Bar.class找到用于接收 Bar 事件的List, 最后对List里所有的方法根据订阅时的规则依次执行。

还有一个比较重要的结构体是 **typesBySubscriber** 该Map主要是用于记录一个类比如 Test.class 是否已经注册过了。

# EventBus调用注册函数的几种方法。
EventBus在Post一个事件类型后，对应注册该类型的接收函数就会被调用，而调用时可以会几种不同的方式，主要是由注册函数定义的线程模型决定.

```
post -> postSingleEvent -> postSingleEventForEventType -> postToSubscription
```
```
private void postToSubscription(Subscription subscription, Object event, boolean isMainThread) {
        switch (subscription.subscriberMethod.threadMode) {
            case POSTING:
                invokeSubscriber(subscription, event);
                break;
            case MAIN:
                if (isMainThread) {
                    invokeSubscriber(subscription, event);
                } else {
                    mainThreadPoster.enqueue(subscription, event);
                }
                break;
            case MAIN_ORDERED:
                if (mainThreadPoster != null) {
                    mainThreadPoster.enqueue(subscription, event);
                } else {
                    // temporary: technically not correct as poster not decoupled from subscriber
                    invokeSubscriber(subscription, event);
                }
                break;
            case BACKGROUND:
                if (isMainThread) {
                    backgroundPoster.enqueue(subscription, event);
                } else {
                    invokeSubscriber(subscription, event);
                }
                break;
            case ASYNC:
                asyncPoster.enqueue(subscription, event);
                break;
            default:
                throw new IllegalStateException("Unknown thread mode: " + subscription.subscriberMethod.threadMode);
        }
    }
```

- **POSTING**
定义成POSTING的接收函数，当事件发生时，就在EventBus.getDefault().post()所在的线程中调用.
- **MAIN**
如果当前是Main线程, 直接调用该注册方法。如果当前不是Main线程，则将该注册方法通过主线程的Handler  post到主线程去执行。
- **MAIN_ORDERED**
MAIN_ORDERED与MAIN的区别在于，不管三七二十一，把注册方法post到主线程中，这样如果当前是主线程的话，注册接收的方法不会立即执行，而是放在主线程所有的Message的最后去执行。

- **BACKGROUND**
如果当前是主线程，则将注册方法 post到background线程中去执行。如果当前是非主线程，则直接调用

- **ASYNC**
Async线程类型，将注册方法post到线程池中去执行。


**BACKGROUND与ASYNC的区别**
```
class AsyncPoster implements Runnable, Poster {

    private final PendingPostQueue queue;
    private final EventBus eventBus;

    AsyncPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        queue = new PendingPostQueue();
    }

    public void enqueue(Subscription subscription, Object event) {
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        queue.enqueue(pendingPost);
        eventBus.getExecutorService().execute(this);
    }

    @Override
    public void run() {
        PendingPost pendingPost = queue.poll();
        if(pendingPost == null) {
            throw new IllegalStateException("No pending post available");
        }
        eventBus.invokeSubscriber(pendingPost);
    }

}
```
```
final class BackgroundPoster implements Runnable, Poster {

    private final PendingPostQueue queue;
    private final EventBus eventBus;

    private volatile boolean executorRunning;

    BackgroundPoster(EventBus eventBus) {
        this.eventBus = eventBus;
        queue = new PendingPostQueue();
    }

    public void enqueue(Subscription subscription, Object event) {
        PendingPost pendingPost = PendingPost.obtainPendingPost(subscription, event);
        synchronized (this) {
            queue.enqueue(pendingPost);
            if (!executorRunning) {
                executorRunning = true;
                eventBus.getExecutorService().execute(this);
            }
        }
    }

    @Override
    public void run() {
        try {
            try {
                while (true) {
                    PendingPost pendingPost = queue.poll(1000);
                    if (pendingPost == null) {
                        synchronized (this) {
                            // Check again, this time in synchronized
                            pendingPost = queue.poll();
                            if (pendingPost == null) {
                                executorRunning = false;
                                return;
                            }
                        }
                    }
                    eventBus.invokeSubscriber(pendingPost);
                }
            } catch (InterruptedException e) {
                eventBus.getLogger().log(Level.WARNING, Thread.currentThread().getName() + " was interruppted", e);
            }
        } finally {
            executorRunning = false;
        }
    }

}

```
BACKGROUND与ASYNC都继承于Runnable, 执行时都是通过线程池去调用执行，而它们的区别在于，对于ASYNC的方式，都是直接提交线程池直接执行，意思是，如果当前有多个注册的事件都是ASYNC,而这些事件都发生了，那它们都会同时在线程池中被调用。意思是多线程运行。

而BACKGROUND线程，当注册的BACKGROUND事件同时发生时，它们会被enqueue到**同一个线程**中去依次执行。当前，这个同时是有时间限制，BACKGROUND线程去poll在1s，如果没有事件时，它会退出去， 如果这时BACKGROUND再次发生时，此时BACKGROUND可能运行于线程池中另一个线程，而非之前那个线程，不过所有BACKGROUND的事件都是依次在**一个线程**中执行，而这**一个线程**并不是真正意义上的一个线程。



