```
    Picasso.get() //
        .load(url) //
        .placeholder(R.drawable.placeholder) //
        .error(R.drawable.error) //
        .fit() //
        .tag(context) //
        .into(view);
```
Picasso框架只用一行就实现了图片的加载，对于App开发者来说真的不要太方便。
项目地址[Picasso](https://github.com/square/picasso), 截止目前(2018/01/16)已经有14.8K个赞！是一个非常优雅的图片加载框架。

# 一、Picasso
![Picasso Overview](http://upload-images.jianshu.io/upload_images/5688445-2be0272ad39c715f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

Picasso 2.x版本用的是 Picasso.with(Context) 需要你自己传Context到Picasso里，而现在 3.x已经不需要再传Context了。
```
  static volatile Picasso singleton = null;
  public static Picasso get() {
    if (singleton == null) {
      synchronized (Picasso.class) {
        if (singleton == null) {
          if (PicassoProvider.context == null) {
            throw new IllegalStateException("context == null");
          }
          singleton = new Builder(PicassoProvider.context).build();
        }
      }
    }
    return singleton;
  }
```
Picasso被设计成一个单例， Builder模式，初始化时使用的是恶汉初始化模式。
Picasso传入Builder里的Context是PicassoProvider.context, 该 context 是在`PicassoProvider`里初始化的
```
public final class PicassoProvider extends ContentProvider {
  static Context context;
  @Override public boolean onCreate() {
    context = getContext();
    return true;
  }
}
```
PicassoProvider是一个ContentProvider, 该类存在的惟一目的就是获得一个context, 而该context其实就是Application Context. 那为什么不直接声明一个Application而要使用ContentProvider? 
**注意**，这里没有直接使用getApplicationContext是因为没有传context过来。与context完全解耦了.
使用ContentProvider也能达到同样的目的，是因为ContentProvider的初始化很早，在ActivityThread的handBindApplication里就要初始化ContentProvider.

来看下build, Builder在参数准备好后就开始 "build", build的主要作用就是创建Picasso的实例
```
    public Picasso build() {
      Context context = this.context;

      if (downloader == null) {
        downloader = new OkHttp3Downloader(context);
      }
      if (cache == null) {
        cache = new LruCache(context);
      }
      if (service == null) {
        service = new PicassoExecutorService();
      }
      if (transformer == null) {
        transformer = RequestTransformer.IDENTITY;
      }

      Stats stats = new Stats(cache);

      Dispatcher dispatcher = new Dispatcher(context, service, HANDLER, downloader, cache, stats);

      return new Picasso(context, dispatcher, cache, listener, transformer, requestHandlers, stats,
          defaultBitmapConfig, indicatorsEnabled, loggingEnabled);
    }
  }
```
## 1.2 downloader
Picasso里默认使用OkHttp 作为下载器, 以后有机会再看下OkHttp的代码吧
```
  public OkHttp3Downloader(final Context context) {
    this(Utils.createDefaultCacheDir(context));
  }
```
OkHttp3Downloader会在App的cache目录下创建picasso-cache用于缓存， 最大的缓存容量是当前可用空间的%2,  最后获得OkHttpClient.

## 1.3 LruCache
Picasso主要目的是图片加载，在Picasso里使用的是Bitmap, 为了performance, 将最近使用到的Bitmap保存到内存里(LRU规则)，这样下次如果想再次获得该bitmap的时候，就可以直接从memory里取，就省掉了从网络或磁盘IO操作，提高了Performance.

其实能将Bitmap作为Cache的主要原因是在LruCache里有一个LinkedHashMap, LinkedHashMap继承于HashMap，有HashMap的所有性质，另外，LinkedHashMap自己实现了一个双向链表。记录插入到LinkedHashMap里的顺序，这样非常适合做LRU。

另外，如果Bitmap没有被使用到的话，也就是没有引用到它的话，该bitmap就会被GC掉，相反，如果bitmap被放到了 LruCache里了，这样bitmap也就有了引用, 它也就不会被GC掉，所以如果bitmap被LruCache移出了，那该bitmap也就被GC了。

**注意**, 理论上将所有的Bitmap都缓存起来的话，这样会大大提高performance, 然而一个APP的所运行的内存是有限的，所以LruCache的大小也就被限制了，Picasso默认将App当前可用的内存的15%作为bitmap缓存。

## 1.4 PicassoExecutorService
Picasso默认创建一个线程池去执行任务，默认最多3个core线程，且最多3个线程，但是也不是绝对的。Picasso会根据当前网络情况来动态改变策略。
```
  void adjustThreadCount(NetworkInfo info) {
    if (info == null || !info.isConnectedOrConnecting()) {
      setThreadCount(DEFAULT_THREAD_COUNT);
      return;
    }
    switch (info.getType()) {
      case ConnectivityManager.TYPE_WIFI:
      case ConnectivityManager.TYPE_WIMAX:
      case ConnectivityManager.TYPE_ETHERNET:
        setThreadCount(4);
        break;
      case ConnectivityManager.TYPE_MOBILE:
        switch (info.getSubtype()) {
          case TelephonyManager.NETWORK_TYPE_LTE:  // 4G
          case TelephonyManager.NETWORK_TYPE_HSPAP:
          case TelephonyManager.NETWORK_TYPE_EHRPD:
            setThreadCount(3);
            break;
          case TelephonyManager.NETWORK_TYPE_UMTS: // 3G
          case TelephonyManager.NETWORK_TYPE_CDMA:
          case TelephonyManager.NETWORK_TYPE_EVDO_0:
          case TelephonyManager.NETWORK_TYPE_EVDO_A:
          case TelephonyManager.NETWORK_TYPE_EVDO_B:
            setThreadCount(2);
            break;
          case TelephonyManager.NETWORK_TYPE_GPRS: // 2G
          case TelephonyManager.NETWORK_TYPE_EDGE:
            setThreadCount(1);
            break;
          default:
            setThreadCount(DEFAULT_THREAD_COUNT);
        }
        break;
      default:
        setThreadCount(DEFAULT_THREAD_COUNT);
    }
  }
```
总结如下:
- wifi, Ethernet:  4
- 4G: 3
- 3G: 2
- 2G: 1

## 1.5 Dispatcher
Dispatcher从名字来看，它是作为一个分发器，Dispatcher创建了一个HandlerThread，然后创建了一个DispatcherHandler，该DispatcherHandler运行在HandlerThread线程当中。

```
    this.dispatcherThread = new DispatcherThread();
    this.dispatcherThread.start();
    this.handler = new DispatcherHandler(dispatcherThread.getLooper(), this);
```

## 1.6 Picasso
最后将downloader/cache/service ...等等传入到Picasso里，生成 一个Picasso实例

# 二、RequestCreator
Picasso.get(), 将Picasso的整体架构创建出来了，接着通过load().placeholder().error().fit().tag() 构造 RequestCreator.

![RequestCreator Overview](http://upload-images.jianshu.io/upload_images/5688445-1b9cf51b8f1104d4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

RequestCreator本身保存着Picasso传过来的placeholder, error id, 以及一些memory/network policy相关的策略. 
而Request也是被设计成的Builder模式，该类的主要作用是对Image做一些处理，比如裁剪，旋转等等...

当所有的准备完后，就开始加载了，加载的方法也很简单，直接调用RequestCreator的load(imageview)函数.
```
  public void into(ImageView target, Callback callback) {
    long started = System.nanoTime();
    checkMain();  //必须在主线程中调用，否则抛出异常

    if (target == null) {  
      throw new IllegalArgumentException("Target must not be null.");
    }

    if (!data.hasImage()) {  //如果没有没有指定image的地址，
      picasso.cancelRequest(target);  //取消之前的请求
      if (setPlaceholder) {
        setPlaceholder(target, getPlaceholderDrawable()); //在target view里设置传入的place holder
      }
      return;
    }

    if (deferred) {  //defer为true的条件是 调用了  fit()，也就是说将image 去适配view的大小。
      if (data.hasSize()) { //fit与 resize互斥
        throw new IllegalStateException("Fit cannot be used with resize.");
      }
      int width = target.getWidth();
      int height = target.getHeight(); //获得 ImageView的大小
      if (width == 0 || height == 0 || target.isLayoutRequested()) { //如果ImageView还没有进行layout，则推迟请求
        if (setPlaceholder) {  //设置 place holder
          setPlaceholder(target, getPlaceholderDrawable());
        }
        //延迟请求, 直接返回
        picasso.defer(target, new DeferredRequestCreator(this, target, callback));
        return;
      }
      //设置Request.Builder里的targetWidth,与targetHeight
      data.resize(width, height);
    }
    //通过 Request.Builder直接build, 创建Request
    Request request = createRequest(started);
    //生成一个request key, 将参数基本上写入到该 request key中
    String requestKey = createKey(request);

    //是否直接从Memory中读，这个就是从LruCache中获取
    if (shouldReadFromMemoryCache(memoryPolicy)) {
      Bitmap bitmap = picasso.quickMemoryCacheCheck(requestKey);
      if (bitmap != null) {
        picasso.cancelRequest(target);
        //如果从内存中获得命中，直接将该bitmap设置到该imageview中，然后返回.
        setBitmap(target, picasso.context, bitmap, MEMORY, noFade, picasso.indicatorsEnabled);
        if (picasso.loggingEnabled) {
          log(OWNER_MAIN, VERB_COMPLETED, request.plainId(), "from " + MEMORY);
        }
        if (callback != null) {
          callback.onSuccess();
        }
        return;
      }
    }

    //如果前面没有命中，下面就开始从其它地方去下载图片去显示
    if (setPlaceholder) {
      setPlaceholder(target, getPlaceholderDrawable());
    }

    //生成一个Action然后提交
    Action action =
        new ImageViewAction(picasso, target, request, memoryPolicy, networkPolicy, errorResId,
            errorDrawable, requestKey, tag, callback, noFade);

    picasso.enqueueAndSubmit(action);
  }
```
**into**的流程其实挺简单的. 下面来看下几个重要的概念

## 2.1 defer 延迟请求
如果code使用了fit()的话，RequestCreator会将fit() 视为 defer的操作，也就是延迟操作。 为什么会这样的设计呢?
fit的目的是让图片去适应ImageView的大小，但是ImageView如果**还没有测量过**的话，它的width与height是没有值的, 所以需要延迟去请求。
一种典型的case就是在onCreate里就直接用Picasso去加载图片，在onCreate里，ImageView还没有被测量到此时它的width和height都为0, 所以此时用延迟加载能满足需求。
实现原理是什么呢？ 就是defer一个DeferredRequestCreator
```
picasso.defer(target, new DeferredRequestCreator(this, target, callback));
```
defer的操作其实很简单，就是将该ImageView作为一个key放到targetToDeferredRequestCreator Map里
```
  void defer(ImageView view, DeferredRequestCreator request) {
    if (targetToDeferredRequestCreator.containsKey(view)) {
      cancelExistingRequest(view);
    }
    targetToDeferredRequestCreator.put(view, request);
  }
```

来看下DeferredRequestCreator的构造函数
```
  DeferredRequestCreator(RequestCreator creator, ImageView target, Callback callback) {
    this.creator = creator;
    this.target = new WeakReference<>(target);
    this.callback = callback;
    //监听ImageView的attach动作
    target.addOnAttachStateChangeListener(this);

    // Only add the pre-draw listener if the view is already attached.
    // See: https://github.com/square/picasso/issues/1321
    if (target.getWindowToken() != null) {
      onViewAttachedToWindow(target);
    }
  }
```
当ImageView attach的时候会调用 onViewAttachedToWindow这个函数
```
  @Override public void onViewAttachedToWindow(View view) {
    view.getViewTreeObserver().addOnPreDrawListener(this);
  }
  @Override public boolean onPreDraw() {
    ImageView target = this.target.get();
    if (target == null) {
      return true;
    }

    ViewTreeObserver vto = target.getViewTreeObserver();
    if (!vto.isAlive()) {
      return true;
    }

    int width = target.getWidth();
    int height = target.getHeight();

    if (width <= 0 || height <= 0 || target.isLayoutRequested()) {
      return true;
    }

    target.removeOnAttachStateChangeListener(this);
    vto.removeOnPreDrawListener(this);
    this.target.clear();

    this.creator.unfit().resize(width, height).into(target, callback);
    return true;
  }
```
原理很简单，就是使用ViewTreeObserver了onPreDraw, 当得到了ImageView的正确的width/height后， 再重新调用` this.creator.unfit().resize(width, height).into(target, callback);`函数,  最后into会调用到enqueueAndSubmit, 这里面会去从targetToDeferredRequestCreator里移出掉 DeferredRequestCreator.

## 2.2 Action

```
    Action action =
        new ImageViewAction(picasso, target, request, memoryPolicy, networkPolicy, errorResId,
            errorDrawable, requestKey, tag, callback, noFade);

    picasso.enqueueAndSubmit(action);
```
在into()的最后, 为这次图片加载生成了一个action, 这个action就代表一次图片加载（当然不同的action意义不同）, 然后对于Picasso前端而言，只需要提交action即可，并不需要具体知道downloader以及一些调度是怎么样的。只需要知道当加载完成后通知action去做具体的事情就好。这种设计与具体的downloader分隔开，与downloader完全解耦。
![Action over](http://upload-images.jianshu.io/upload_images/5688445-f722647e7a6ec667.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
Action是一个抽象类, 有两个抽象的方法  complete 和 error
```
  abstract void complete(Bitmap result, Picasso.LoadedFrom from);
  abstract void error(Exception e);
```
当Action被正确执行且返回会回调 complete, 传递给下载好的bitmap以及from(从哪里获得的)

对于 ImageViewAction，
```
 @Override public void complete(Bitmap result, Picasso.LoadedFrom from) {
    if (result == null) {
      throw new AssertionError(
          String.format("Attempted to complete action with no result!\n%s", this));
    }

    ImageView target = this.target.get();
    if (target == null) {
      return;
    }

    Context context = picasso.context;
    boolean indicatorsEnabled = picasso.indicatorsEnabled;
    PicassoDrawable.setBitmap(target, context, result, from, noFade, indicatorsEnabled);

    if (callback != null) {
      callback.onSuccess();
    }
  }
```
complete回调后就会直接设置bitmap到该ImageView里面
```
  @Override public void error(Exception e) {
    ImageView target = this.target.get();
    if (target == null) {
      return;
    }
    Drawable placeholder = target.getDrawable();
    if (placeholder instanceof AnimationDrawable) {
      ((AnimationDrawable) placeholder).stop();
    }
    if (errorResId != 0) {
      target.setImageResource(errorResId);
    } else if (errorDrawable != null) {
      target.setImageDrawable(errorDrawable);
    }

    if (callback != null) {
      callback.onError(e);
    }
  }
```
如果返回Error的话，就会设置error image到ImageView

# 三、提交 Action
![Action提交执行过程](http://upload-images.jianshu.io/upload_images/5688445-bfa4705dcfba1fba.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
如图所示，Action的提交 涉及多个线程之间的通信，Main线程主要是提交请求，Dispatcher线程接收请求，然后向线程池提交执行请求，线程池里的线程在下载好数据后，就将结果传回给Dispatcher线程，Dispatcher线程在接收到请求后，再打包将一串数据传给Main线程，最后Main线程再做具体的显示操作。

## 3.1 Main线程向Dispatcher线程提交Action
当经过前面的准备后，就开始提交Action了，
```
  void enqueueAndSubmit(Action action) {
    Object target = action.getTarget();
    if (target != null && targetToAction.get(target) != action) {
      // This will also check we are on the main thread.
      cancelExistingRequest(target);
      targetToAction.put(target, action);
    }
    submit(action);
  }
``` 
targetToAction是一个Map用于记录所提交过的Action, 如果对于一个target的Action已经存在于 targetToAction里了，那么这个target新的Action对cancel 它之前的Request, 这样也是合理的，因为后续的Action必然是想请求最新的，那旧的自然就没有必要存在了，

submit的作用就是将action 传到 DispatchThread里去处理，主要是向DispatchHandler里发一条REQUEST_SUBMIT message即可.

## 3.2 Dispatcher线程向线程池请求执行下载任务

Dispatcher线程在接收到Main线程的请求 Action后，会调用performSubmit
```
  void performSubmit(Action action, boolean dismissFailed) {
    // 如果Picasso调用了pauseTag, 那么这里就不会被Dispatcher去线程池请求执行下载任务，除非等到下次resumeTag发生后
    if (pausedTags.contains(action.getTag())) {
      pausedActions.put(action.getTarget(), action);
      return;
    }

    //检查是否已经向线程池提交过请求了，如果提交过相同的请求，特别注意，如果request的url不一样，
   //这里的action.getKey()也就不一样，所以会是两种不同的请求
    BitmapHunter hunter = hunterMap.get(action.getKey());
    if (hunter != null) {
      hunter.attach(action);  //将action attach到BitmapHunter中
      return;
    }

    if (service.isShutdown()) { //线程池挂掉了
      return;
    }

    //生成一个BitmapHunter，然后提交给线程池执行
    hunter = forRequest(action.getPicasso(), this, cache, stats, action);
    hunter.future = service.submit(hunter);
    hunterMap.put(action.getKey(), hunter);  //保存到hunterMap当中
    if (dismissFailed) {
      failedActions.remove(action.getTarget());
    }
  }
```
### 3.2.1 BitmapHunter的RequestHandler
Picasso目前支持很多种不同的source加载，比如https/http,  File,  Asset相关等等
```
  static BitmapHunter forRequest(Picasso picasso, Dispatcher dispatcher, Cache cache, Stats stats,
      Action action) {
    Request request = action.getRequest();
    List<RequestHandler> requestHandlers = picasso.getRequestHandlers();

    for (int i = 0, count = requestHandlers.size(); i < count; i++) {
      RequestHandler requestHandler = requestHandlers.get(i);
      if (requestHandler.canHandleRequest(request)) {
        //一般都会在这里正确的返回
        return new BitmapHunter(picasso, dispatcher, cache, stats, action, requestHandler);
      }
    }
    //一般不会走到这里
    return new BitmapHunter(picasso, dispatcher, cache, stats, action, ERRORING_HANDLER);
  }
```
picasso.getRequestHandlers()会返回如下的Handler去处理不同的请求，比如https/http就使用NetworkReqeustHandler,    file:// 相关的就使用FileRequestHandler, 等等。
```
    allRequestHandlers.add(new ContactsPhotoRequestHandler(context));
    allRequestHandlers.add(new MediaStoreRequestHandler(context));
    allRequestHandlers.add(new ContentStreamRequestHandler(context));
    allRequestHandlers.add(new AssetRequestHandler(context));
    allRequestHandlers.add(new FileRequestHandler(context));
    allRequestHandlers.add(new NetworkRequestHandler(dispatcher.downloader, stats));
    requestHandlers = Collections.unmodifiableList(allRequestHandlers);
```

### 3.2.2 BitmapHunter
![BitmapHunter overview](http://upload-images.jianshu.io/upload_images/5688445-02f0397adc016d9a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
其中RequestHandler是具体的下载实现，不同的类型使用不同的RequestHandler, Action表示一次具体的请求.

BitmapHunter在生成好后就直接提交给线程池去工作.  另外

##  3.3 线程池中线程执行BitmapHunter.run
当线程池某一个线程开始执行BitmapHunter时，首先会调用该 run 方法。

```
  @Override public void run() {
    try {
      updateThreadName(data); //更新线程名字

      result = hunt(); //具体的load操作

      //下面就是dispatch 结果， fail或complete
      if (result == null) {
        dispatcher.dispatchFailed(this); 
      } else {
        dispatcher.dispatchComplete(this);
      }
    } catch (NetworkRequestHandler.ResponseException e) { //异常处理，  dispatchFail
      if (!NetworkPolicy.isOfflineOnly(e.networkPolicy) || e.code != 504) {
        exception = e;
      }
      dispatcher.dispatchFailed(this);
    } catch (IOException e) {
      exception = e;
      dispatcher.dispatchRetry(this);
    } catch (OutOfMemoryError e) {
      StringWriter writer = new StringWriter();
      stats.createSnapshot().dump(new PrintWriter(writer));
      exception = new RuntimeException(writer.toString(), e);
      dispatcher.dispatchFailed(this);
    } catch (Exception e) {
      exception = e;
      dispatcher.dispatchFailed(this);
    } finally {
      //重新设置IDLE名字
      Thread.currentThread().setName(Utils.THREAD_IDLE_NAME);
    }
  }
```
来看下hunt命令
```
  Bitmap hunt() throws IOException {
    Bitmap bitmap = null;

    //根据  memory 策略，是否从Memory cache里读
    if (shouldReadFromMemoryCache(memoryPolicy)) {
      bitmap = cache.get(key);
      if (bitmap != null) {
        stats.dispatchCacheHit();
        loadedFrom = MEMORY;
        return bitmap;
      }
    }

    networkPolicy = retryCount == 0 ? NetworkPolicy.OFFLINE.index : networkPolicy;
    //调用具体的RequestHandler去load
    RequestHandler.Result result = requestHandler.load(data, networkPolicy);
    if (result != null) {
      loadedFrom = result.getLoadedFrom();
      exifOrientation = result.getExifOrientation();
      bitmap = result.getBitmap();

      // If there was no Bitmap then we need to decode it from the stream.
      if (bitmap == null) {
        Source source = result.getSource();
        try {
          //如果从上面没有获得bitmap， 直接从stream去 decode ???为什么会这样？？
          bitmap = decodeStream(source, data);
        } finally {
          try {
            //noinspection ConstantConditions If bitmap is null then source is guranteed non-null.
            source.close();
          } catch (IOException ignored) {
          }
        }
      }
    }

    if (bitmap != null) {
      //下面是对bitmap作一些矩阵变换，比如旋转,  resize 等等
      if (data.needsTransformation() || exifOrientation != 0) {
        synchronized (DECODE_LOCK) {
          if (data.needsMatrixTransform() || exifOrientation != 0) {
            bitmap = transformResult(data, bitmap, exifOrientation);
          }
          if (data.hasCustomTransformations()) {
            bitmap = applyCustomTransformations(data.transformations, bitmap);
          }
        }
      }
    }

    return bitmap;
  }
```
hunt方法其实很简单，就是调用  RequestHandler 中load方法(该load方法会自动去load bitmap)， 最后再对load成功的bitmap作一些矩阵运算。返回 bitmap即可。

当bitmap成功获得后，线程池中的线程就会通知dispatcher.dispatchComplete下载完成的信息, 其实就是像DispatcherThread发送一条 HUNTER_COMPLETE 信息

## 3.4  Dispatcher线程处理Bitmap
Dispatcher线程在收到HUNTER_COMPLETE后，就开始处理bitmap, 具体是通过performComplete来完成的
```
  void performComplete(BitmapHunter hunter) {
    //是否将bitmap保存到Memory  cache 中
    if (shouldWriteToMemoryCache(hunter.getMemoryPolicy())) {
      cache.set(hunter.getKey(), hunter.getResult());
    }
    //移出掉hunterMap
    hunterMap.remove(hunter.getKey());
    batch(hunter);  //对从所有线程池回来的bitmap打成包发送
  }

  private void batch(BitmapHunter hunter) {
    if (hunter.isCancelled()) {
      return;
    }
    if (hunter.result != null) {
      hunter.result.prepareToDraw();
    }
    batch.add(hunter); //将线程池回来的BitmapHunter打包，然后200ms才发送到主线程, 
    if (!handler.hasMessages(HUNTER_DELAY_NEXT_BATCH)) {
      handler.sendEmptyMessageDelayed(HUNTER_DELAY_NEXT_BATCH, BATCH_DELAY);
    }
  }

  void performBatchComplete() {
    List<BitmapHunter> copy = new ArrayList<>(batch);
    batch.clear();
   //向主线程发送HUNTER_BATCH_COMPLETE
  
   mainThreadHandler.sendMessage(mainThreadHandler.obtainMessage(
              HUNTER_BATCH_COMPLETE, copy));
    logBatch(copy);
  }
```
注意 Dispatcher线程并没有来一个BitmapHunter就向主线程发送，相反而是打包发送，也就是说每200ms将这段时间回来的Bitmaphunter一起发送给主线程。 这样避免了主线程的Looper不断的loop的造成的时间消耗。

## 3.5 MainThread去处理最后的Bitmap
3.4小节，DispatcherThread把打包好的BitmapHunter通过Message发送给主线程，主线程收到 HUNTER_BATCH_COMPLETE后
```
        case HUNTER_BATCH_COMPLETE: {
          @SuppressWarnings("unchecked") List<BitmapHunter> batch = (List<BitmapHunter>) msg.obj;
          //noinspection ForLoopReplaceableByForEach
          for (int i = 0, n = batch.size(); i < n; i++) {
            BitmapHunter hunter = batch.get(i);
            hunter.picasso.complete(hunter); //处理每一个BitmapHunter
          }
          break;
```

complete函数其实挺简单，就是deliver bitmap到每一个action, 比如ImageViewAction收到后就直接将bitmap设置到ImageView 里。
```
  void complete(BitmapHunter hunter) {
    Action single = hunter.getAction();
    List<Action> joined = hunter.getActions();

    boolean hasMultiple = joined != null && !joined.isEmpty();
    boolean shouldDeliver = single != null || hasMultiple;

    if (!shouldDeliver) {
      return;
    }

    Uri uri = hunter.getData().uri;
    Exception exception = hunter.getException();
    Bitmap result = hunter.getResult();
    LoadedFrom from = hunter.getLoadedFrom();

    if (single != null) {
      deliverAction(result, from, single, exception);
    }

    if (hasMultiple) {
      //noinspection ForLoopReplaceableByForEach
      for (int i = 0, n = joined.size(); i < n; i++) {
        Action join = joined.get(i);
        deliverAction(result, from, join, exception);
      }
    }

    if (listener != null && exception != null) {
      listener.onImageLoadFailed(this, uri, exception);
    }
  }
```

# 四、小结与思考
Picasso框架很好用，这个毋庸置疑，从star数就可以看出来。那它到底好在哪里呢？

1. 一行代码就实现了image的加载与显示.  以及placeholder,  Error image的显示处理的都非常好。
2. 磁盘缓存， 没网时也能正常显示图片
3. bitmap内存LRU 缓存，提高了performance.
4. 代码清晰好懂
5. 可扩展性好，比如可以自己写downloader/LruCache这些
6. 对图片的处理也比较好，支持矩阵运算.
7. ...














