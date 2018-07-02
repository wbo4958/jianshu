> 如果带着问题去学习binder又是怎样的呢？

# 1. 第一个binder线程 Binder:PID_1是怎样创建的
在App进程创建的时候
```
nativeZygoteInit
  -> com_android_internal_os_RuntimeInit_nativeZygoteInit
    -> onZygoteInit
      -> ProcessState 
        -> startThreadPool()
          -> spawnPooledThread(true)
```
**isMain=true**
```
void ProcessState::spawnPooledThread(bool isMain)
{
    if (mThreadPoolStarted) {
        String8 name = makeBinderThreadName(); //这里是第一个binder 线程，也就是binder主线程
        sp<Thread> t = new PoolThread(isMain);  //生成一个线程
        t->run(name.string());  //线程运行，不停的调用theadLoop
    }   
}
```
```
virtual bool threadLoop()
{
    IPCThreadState* ptr = IPCThreadState::self();
    if (ptr) {
        ptr->joinThreadPool(mIsMain);
    }
    return false; //threadLoop返回false表示会退出线程，即线程结束了
}
```

```
void IPCThreadState::joinThreadPool(bool isMain)
{
    // mOut是写入内核的数据，如果是Main Binder线程，则发送 BC_ENTER_LOOPER
    mOut.writeInt32(isMain ? BC_ENTER_LOOPER : BC_REGISTER_LOOPER);
       
    status_t result;
    do {
        processPendingDerefs(); //处理引用计数
        result = getAndExecuteCommand(); //获得命令并执行

        //异常退出
        if (result < NO_ERROR && result != TIMED_OUT && result != -ECONNREFUSED && result != -EBADF) {
            abort();
        }
        
        //如果timeout了，但如果不是 Main Binder线程也就是非 Binder:xxx_1线程就直接退出
        if(result == TIMED_OUT && !isMain) {
            break;
        }
    } while (result != -ECONNREFUSED && result != -EBADF);
    //通知binder驱动 线程要退出了
    mOut.writeInt32(BC_EXIT_LOOPER);
    talkWithDriver(false);
}
```
在 `binder_thread_write` 中会有对 BC_ENTER_LOOPER的处理, 如下所示，thread是指binder_thread,也就是当前线程的描述符, 将BINDER_LOOPER_STATE_ENTERED保存到 binder_thread的looper变量里. 

looper变量主要保存的binder线程的一种状态如
- BINDER_LOOPER_STATE_REGISTERED
标记该binder线程是非主binder线程
- BINDER_LOOPER_STATE_ENTERED
标记该binder线程是主binder线程
- BINDER_LOOPER_STATE_EXITED
标记该binder线程马上就要退出了
- BINDER_LOOPER_STATE_INVALID
标记是无效的，但是这个并没有实际用处，一般是 原来是主线程，然后用户态又通过主线程发送了BC_REGISTER_LOOPER，就会标志 INVALID, 同理与REGISTER一样
- BINDER_LOOPER_STATE_WAITING
标记当前binder线程正在等着client的请求
- BINDER_LOOPER_STATE_NEED_RETURN
这个标志，是当前新线程下来在生成binder_thread里置位的，表示该binder线程在处理完transaction后需要返回到用户态。

```
case BC_ENTER_LOOPER:
    if (thread->looper & BINDER_LOOPER_STATE_REGISTERED) {
        thread->looper |= BINDER_LOOPER_STATE_INVALID;
        binder_user_error("%d:%d ERROR: BC_ENTER_LOOPER called after BC_REGISTER_LOOPER\n",
            proc->pid, thread->pid);
    }
    thread->looper |= BINDER_LOOPER_STATE_ENTERED;
    break;
```

从上面可知，startThreadPool就是创建APP进程的binder线程池模型， 它也会创建第一个binder主线程，该线程不会退出(非 critcal错误)，而spawnPooledThread函数用于创建一个普通的binder线程，这些线程都有可能会退出。

# 二、 一个APP里最多能有多少个binder线程呢？ 其它binder线程是怎么创建的，由谁创建的呢？
从第一节可知，只要调用 spawnPooledThread()函数就创建了一个binder线程，因此从代码上来看除了第一个主binder线程由APP用户态主动创建外，其它的都是由Binder驱动主动向APP申请创建，也就是说binder线程的创建是看binder驱动是否很繁忙(这里的繁忙是与本进程相关的)，来决定是否需要向APP进程申请创建，也就是APP进程被动创建。

## 2.1 Server端
其实也很好理解，binder最终的通信包括client和server双方。server方并不知道什么时候会有client的请求，client的有请求，它就直接丢给binder驱动，然后由binder驱动根据当前server的能力(是否有多余线程去处理)去看下是否需要新的线程来处理client的请求。

那试问一个 Server端 App 最多同时能跑多少个binder线程呢？

这个问题，可以简单的测试一下，写一个Service, 然后实现一个API里死循环sleep, 反正就是不返回, 另一个client在创建多个非UI线程去请求API, 然后再dump出来，就知道最多支持多少个binder线程了。

我们从  spawnPooledThread的调用函数来看就知道能有多少个binder线程了。

```
IPCThreadState::executeCommand(int32_t cmd) {
    case BR_SPAWN_LOOPER:
        mProcess->spawnPooledThread(false); 
        break;
}
```
**executeCommand**函数执行从Binder 驱动传递到 BR_SPAWN_LOOPER,  这时就得继续从binder驱动中去找 BR_SPAWN_LOOPER了。

```
static int binder_thread_read(...)
    ...
    if (proc->requested_threads + proc->ready_threads == 0 &&
        proc->requested_threads_started < proc->max_threads &&
        (thread->looper & (BINDER_LOOPER_STATE_REGISTERED |
         BINDER_LOOPER_STATE_ENTERED)) /* the user-space code fails to */
         /*spawn a new thread if we leave this out */) {
        proc->requested_threads++;
        if (put_user(BR_SPAWN_LOOPER, (uint32_t __user *)buffer))
            return -EFAULT;
        binder_stat_br(proc, thread, BR_SPAWN_LOOPER);
    }
}
```
在binder_thread_read调用的最后，就会有可能去spawn一个线程, 但是也是有条件的, 且要同时满足才行

1. **binder_proc -> ready_threads = 0**
ready_threads表明当前进程中还没有准备好的线程, 换句话说，当前binder线程都有各自的任务在处理, 没空接受新的任务了。

2.  **binder_proc -> requested_threads = 0**
如果 reqeusted_threads >0 表明 binder_driver已经通过其它binder线程请求APP进程去创建binder线程来处理任务的路上了, 所以啊，binder驱动就不会再通知一次了，因为如果再通知一次，那么就会创建两个binder线程，资源浪费嘛。
所以如果 requested_threads = 0，如果其它条件满足，那就由我这个binder线程就请求APP去创建另一个binder线程

3. **proc->requested_threads_started < proc->max_threads**
**max_threads:** 这个是APP进程通知binder驱动我这个APP进程当前最多只能创建max_threads个线程，如果我已经创建这么多了，不要再通知我去创建了。至于别人(其它APP)想要获得我的服务或其它，别急，取号等着，等着我的binder线程不忙了再去处理。

4. **thread->looper & (BINDER_LOOPER_STATE_REGISTERED |BINDER_LOOPER_STATE_ENTERED**
looper保存着当前线程的一些状态，BINDER_LOOPER_STATE_REGISTERED(非主Binder线程)|BINDER_LOOPER_STATE_ENTERED(主binder线程) 这两个是binder线程的标志. 即: Binder驱动只有通过我的APP创建的（**有效的**）binder线程才有资格向我(APP)提出创建其它binder线程. 

好了，上面4个条件同时满足了，就通知APP创建一个新的 binder线程了。

但还有几个问题

1. **binder_proc->max_threads**最大是多少呢？
在ProcessState.cpp的 **open_driver** 函数中, 会设置一个默认的最大binder线程数 DEFAULT_MAX_BINDER_THREADS = 15;
但是BinderInternal.java也提供了setMaxThreads去修改Binder驱动中的max_threads值. 比如system_server就设置了max_threads为31
注意 : BinderInternal是internal的类，APP不能直接调用。

2. **ready_threads** 这个是什么意思呢？
在 `binder_thread_read` 函数中
```
static int binder_thread_read(..) {
    //wait_for_proc_work表示是否需要去等着proc的任务，当然前提是这个binder线程没有任务可做了，
    //那这个binder线程就可以向进程申请去处理进程的其它任务了
    wait_for_proc_work = thread->transaction_stack == NULL &&
                list_empty(&thread->todo);
    if (wait_for_proc_work)
        proc->ready_threads++;  //表明当前进程又多了一个可用的binder线程去接受任务了

    if (wait_for_proc_work) {
            //等着整个进程的任务
            ret = wait_event_freezable_exclusive(proc->wait, binder_has_proc_work(proc, thread));
    } else {
            //等着binder线程本身的任务
            ret = wait_event_freezable(thread->wait, binder_has_thread_work(thread));
    }
    ...
    //代码运行到这里就表明有任务了，那么我这个binder线程就要去接受任务去做了，
    if (wait_for_proc_work)
        //既然我已经开始去处理任务了，那么整个进程当前就少了我一个可用的线程啦
        proc->ready_threads--;

   ...
done:
    //决定是否再创建一个新的binder线程
    *consumed = ptr - buffer;
    if (proc->requested_threads + proc->ready_threads == 0 &&
        proc->requested_threads_started < proc->max_threads &&
        (thread->looper & (BINDER_LOOPER_STATE_REGISTERED |
         BINDER_LOOPER_STATE_ENTERED)) /* the user-space code fails to */
        proc->requested_threads++; //请求创建线程+1
        if (put_user(BR_SPAWN_LOOPER, (uint32_t __user *)buffer))
            return -EFAULT;
        binder_stat_br(proc, thread, BR_SPAWN_LOOPER);
    }
}
```

3.  requested_threads与requested_threads_started
requested_threads表示要请求创建的线程数，在上面决定创建binder线程后，该值会+1
而requested_threads_started表示当前进程已经**启动吧**这么多个线程, 
APP接收到创建新的binder线程的请求后就会调用 `spawnPooledThread(false)` 去创建一个非Main的binder线程， 而最后又会通过joinThreadPool触发binder驱动的BC_REGISTER_LOOPER
```
static int binder_thread_write(...) {
        case BC_REGISTER_LOOPER:
            if (thread->looper & BINDER_LOOPER_STATE_ENTERED) {
                thread->looper |= BINDER_LOOPER_STATE_INVALID;
            } else if (proc->requested_threads == 0) {
                thread->looper |= BINDER_LOOPER_STATE_INVALID;
            } else {
                //正常情况会进入该分支
                proc->requested_threads--;   //要请求创建的binder线程数减1
                proc->requested_threads_started++; //启动过的线程数+1
            }
            thread->looper |= BINDER_LOOPER_STATE_REGISTERED;
            break;
```

![binder线程创建](http://upload-images.jianshu.io/upload_images/5688445-56a682f2cb9588d7.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 2.2 client
client端作为请求端，是从用户态下发起请求的，所以client一般是自己申请创建线程，然后在线程里去请求 server端的服务，这里不会涉及到binder线程。

那么client就只有一个默认的主binder线程了么？当然不是，从2.1可知，binder驱动会不管是server,还是client, 都会默认有一个空闲线程，所以client的主binder线程在binder_thread_read的最后发现当前没有可ready的线程，这时就通知client端创建一个binder线程。所以一般client有两个binder线程。

# 三、Server的binder线程阻塞在哪里了？

人第二节可知道，Server端的binder线程会一直等着client的请求，即如果没有客户端请求时，binder线程应该是一直阻塞着的，那么Server端等在什么地方呢？ 

来看下binder线程的运行过程便可知, 第一小节已经贴出相关代码了,  再帖一次，

```
void IPCThreadState::joinThreadPool(bool isMain)
{
    // mOut是写入内核的数据，如果是Main Binder线程，则发送 BC_ENTER_LOOPER
    mOut.writeInt32(isMain ? BC_ENTER_LOOPER : BC_REGISTER_LOOPER);
       
    status_t result;
    do {
        processPendingDerefs(); //处理引用计数
        result = getAndExecuteCommand(); //获得命令并执行

        //异常退出
        if (result < NO_ERROR && result != TIMED_OUT && result != -ECONNREFUSED && result != -EBADF) {
            abort();
        }
        
        //如果timeout了，但如果不是 Main Binder线程也就是非 Binder:xxx_1线程就直接退出
        if(result == TIMED_OUT && !isMain) {
            break;
        }
    } while (result != -ECONNREFUSED && result != -EBADF);
    //通知binder驱动 线程要退出了
    mOut.writeInt32(BC_EXIT_LOOPER);
    talkWithDriver(false);
}
```
从joinThreadPool里，有一个getAndExecuteCommand， 来看下这个函数干了些什么吧

```
status_t IPCThreadState::getAndExecuteCommand()
{
    status_t result;
    int32_t cmd;

    result = talkWithDriver();
    if (result >= NO_ERROR) {
        size_t IN = mIn.dataAvail();
        if (IN < sizeof(int32_t)) return result; //异常处理
        cmd = mIn.readInt32(); //获得从Binder驱动返回来的BR_ 号
         ...
        result = executeCommand(cmd); //开始执行相关命令
        ...
    }

    return result;
}
```
该函数很简单，从binder驱动里拿到client要操作的CMD, 然后调用executeCommand执行CMD。

在看 `talkWithDriver` 之前，先来看下 binder_write_read
![binder_write_read](http://upload-images.jianshu.io/upload_images/5688445-a6e5c31432c1283f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

binder_write_read是一个结构体，它主要作用是告诉binder驱动我这次请求的目的是什么，一是read, 另一个就是write, 
- write_size > 0 表明需要往binder驱动写write_buffer的数据
- read_size >0  表明需要往binder驱动读数据放到write_buffer的数据
- write_consumed 表明已经写入了多少字节
- read_consumed 表明读入了多少字节
- write_buffer 指向用户态下的数据地址，其实也就是IPCThreadState里的mOut (Parcel)里的mData地址
- read_buffer 指向用户态下的数据地址，其实也就是IPCThreadState里的mIn (Parcel)里的mData地址

```
status_t IPCThreadState::talkWithDriver(bool doReceive)
{
    binder_write_read bwr; 
    
    //mIn.dataPosition()是当前mIn中mData的指针，
    const bool needRead = mIn.dataPosition() >= mIn.dataSize();
    //doReceive默认为true, needRead为true表明还需要从Binder驱动中读取数据，这时候就不能往binder驱动中写数据
    const size_t outAvail = (!doReceive || needRead) ? mOut.dataSize() : 0; 
    //填充写数据的相关字段
    bwr.write_size = outAvail;
    bwr.write_buffer = (uintptr_t)mOut.data();

    // This is what we'll read.
    if (doReceive && needRead) {
        //填充读数据相关字段
        bwr.read_size = mIn.dataCapacity();
        bwr.read_buffer = (uintptr_t)mIn.data();
    } else {
        bwr.read_size = 0; 
        bwr.read_buffer = 0; 
    }     
    
    // Return immediately if there is nothing to do.
    if ((bwr.write_size == 0) && (bwr.read_size == 0)) return NO_ERROR;

    bwr.write_consumed = 0; 
    bwr.read_consumed = 0; 
    status_t err; 
    do {
#if defined(__ANDROID__)
        //阻塞式的调用 binder_ioctl
        if (ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr) >= 0)
            err = NO_ERROR;
        else
            err = -errno;
#else
        err = INVALID_OPERATION;
#endif
        if (mProcess->mDriverFD <= 0) {
            err = -EBADF;
        }
    } while (err == -EINTR);

    if (err >= NO_ERROR) {
        if (bwr.write_consumed > 0) {
            //根据写了多少数据，来更新mOut的相关数据
            if (bwr.write_consumed < mOut.dataSize())
                mOut.remove(0, bwr.write_consumed);
            else
                mOut.setDataSize(0);
        }
        if (bwr.read_consumed > 0) {
            //根据读了多少数据，来更新mIn的相关数据
            mIn.setDataSize(bwr.read_consumed);
            mIn.setDataPosition(0);
        }
        return NO_ERROR;
    }
        
    return err;
}
```

## 3.1 binder线程第一次调用 binder_ioctl
我们来看下binder线程创建后第一次调用 binder_ioctl 的情况
从joinThreadPool可以看出, 会写入一个 BC_REGISTER_LOOPER
```
mOut.writeInt32(isMain ? BC_ENTER_LOOPER : BC_REGISTER_LOOPER);
```
因此此时binder_write_read的结构如下所示

![binder_write_read](http://upload-images.jianshu.io/upload_images/5688445-b61ff02948294029.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

来看下binder驱动是怎么处理的呢

```
static long binder_ioctl(struct file *filp, unsigned int cmd, unsigned long arg)
{
    // binder_stop_on_user_error默认为0，只有条件为false的时候才会一直block在这，所以这里并不会block
    ret = wait_event_interruptible(binder_user_error_wait, binder_stop_on_user_error < 2);
    binder_lock(__func__);
    //创建一个与binder线程相关的binder_thread,  第一次创建会设置thread->looper |= BINDER_LOOPER_STATE_NEED_RETURN
    thread = binder_get_thread(proc);

    switch (cmd) {
    case BINDER_WRITE_READ: //执行BINDER_WRITE_READ命令
        ret = binder_ioctl_write_read(filp, cmd, arg, thread);
        break;
        ...
    }
    
    ret = 0;
err:
    if (thread) //这里取消BINDER_LOOPER_STATE_NEED_RETURN
        thread->looper &= ~BINDER_LOOPER_STATE_NEED_RETURN;
    binder_unlock(__func__);
    wait_event_interruptible(binder_user_error_wait, binder_stop_on_user_error < 2);
err_unlocked:
    return ret;
}
```
其中`BINDER_LOOPER_STATE_NEED_RETURN`这个非常重要，就是这个标志，决定了binder线程第一次的走向，如果是第一次进来， binder_thread->looper都设置了该标志，而该binder_ioctl返回时，会将该标志取消

```
static int binder_ioctl_write_read(struct file *filp,
                unsigned int cmd, unsigned long arg,
                struct binder_thread *thread)
{
    int ret = 0;
    struct binder_proc *proc = filp->private_data;
    unsigned int size = _IOC_SIZE(cmd);
    void __user *ubuf = (void __user *)arg;
    struct binder_write_read bwr;
    //获得用户态的 binder_write_read
    if (copy_from_user(&bwr, ubuf, sizeof(bwr))) {
        ret = -EFAULT;
        goto out;
    }

    if (bwr.write_size > 0) {
        //有数据要写入，这里就是 BC_REGISTER_LOOPER
        ret = binder_thread_write(proc, thread,  bwr.write_buffer,
                      bwr.write_size, &bwr.write_consumed);
    }
    if (bwr.read_size > 0) {
        //有数据要读取
        ret = binder_thread_read(proc, thread, bwr.read_buffer,
                     bwr.read_size,  &bwr.read_consumed,  filp->f_flags & O_NONBLOCK);
        //如果进程描述结构体中有任务要做时，唤醒等在binder_proc->wait的列表
        if (!list_empty(&proc->todo))
            wake_up_interruptible(&proc->wait);
    }
    //将数据拷贝到用户态
    if (copy_to_user(ubuf, &bwr, sizeof(bwr))) {
    }
out:
    return ret;
}
```
binder线程第一次调用binder_ioctl也就是写入一个BC_REGISTER_LOOPER, 所以 binder_thread_write只是将binder_thread->looper设置一个BINDER_LOOPER_STATE_REGISTERED

现在来看下binder_thread_read
```
static int binder_thread_read(...)
{
    void __user *buffer = (void __user *)(uintptr_t)binder_buffer;
    void __user *ptr = buffer + *consumed;
    void __user *end = buffer + size;

    int ret = 0;
    int wait_for_proc_work;

    //第一次进来 consumed为0，所以进入该分支, 会返回一个 BR_NOOP
    if (*consumed == 0) {
        if (put_user(BR_NOOP, (uint32_t __user *)ptr)) return -EFAULT;
        ptr += sizeof(uint32_t);
    }

retry:
    //binder线程第一次进来，没有transaction任务，也没有其它任务，所以waite_for_proc_work为true
    wait_for_proc_work = thread->transaction_stack == NULL &&
                list_empty(&thread->todo);
    thread->looper |= BINDER_LOOPER_STATE_WAITING;
    if (wait_for_proc_work)
        proc->ready_threads++;  //线程可用

    binder_unlock(__func__);

    if (wait_for_proc_work) { //进入该分支
        if (non_block) {
            if (!binder_has_proc_work(proc, thread))
                ret = -EAGAIN;
        } else
            //如果binder_has_proc_work为false，将会一直等在这里，直到被唤醒
            ret = wait_event_freezable_exclusive(proc->wait, binder_has_proc_work(proc, thread));
    } else {
        if (non_block) {
            if (!binder_has_thread_work(thread))
                ret = -EAGAIN;
        } else
            ret = wait_event_freezable(thread->wait, binder_has_thread_work(thread));
    }

    binder_lock(__func__);

    if (wait_for_proc_work)
        proc->ready_threads--; //线程接收到任务，准备执行了
    thread->looper &= ~BINDER_LOOPER_STATE_WAITING;

    if (ret)
        return ret;

    while (1) {
        uint32_t cmd;
        struct binder_transaction_data tr;
        struct binder_work *w;
        struct binder_transaction *t = NULL;

        if (!list_empty(&thread->todo)) { //无任务
            w = list_first_entry(&thread->todo, struct binder_work, entry);
        } else if (!list_empty(&proc->todo) && wait_for_proc_work) { //无任务
            w = list_first_entry(&proc->todo, struct binder_work, entry);
        } else {
            /* no data added */
            if (ptr - buffer == 4 &&
                !(thread->looper & BINDER_LOOPER_STATE_NEED_RETURN))
                goto retry;
            break; //直接break到
        }

        if (end - ptr < sizeof(tr) + 4)
            break;
        
        switch(...) {}
    }

done:
       
    //决定是否要向用户态申请创建一个新的线程
    *consumed = ptr - buffer;
    if (proc->requested_threads + proc->ready_threads == 0 &&
        proc->requested_threads_started < proc->max_threads &&
        (thread->looper & (BINDER_LOOPER_STATE_REGISTERED |
         BINDER_LOOPER_STATE_ENTERED)) /* the user-space code fails to */
         /*spawn a new thread if we leave this out */) {
        proc->requested_threads++;
        if (put_user(BR_SPAWN_LOOPER, (uint32_t __user *)buffer))
            return -EFAULT;
        binder_stat_br(proc, thread, BR_SPAWN_LOOPER);
    }
    return 0;
}
```
`ret = wait_event_freezable_exclusive(proc->wait, binder_has_proc_work(proc, thread));`
现在来看下wait_event_freezable_exclusive, 该函数的条件如果为true，即表明binder有任务时，就不会阻塞，而是直接执行，binder线程第一次进来的时候(因为binder线程的创建是防止后续有请求到来，所以当前是没有任务的), 当前没有任务的话，binder线程应该一直等着呀，为什么这里没有阻塞式的等呢？
```
static int binder_has_proc_work(struct binder_proc *proc,   struct binder_thread *thread)
{
    return !list_empty(&proc->todo) ||
        (thread->looper & BINDER_LOOPER_STATE_NEED_RETURN);
}
```
哦，原来binder_has_proc_work会判断如果binder线程是第一次进来的话，也就是设置了BINDER_LOOPER_STATE_NEED_RETURN，也就是表明该线程不管你有没有任务，我都需要返回到用户态，所以这里为true, 也就不会等待了。

所以第一次返回给用户态时，只返回一个 BR_NOOP， 表示无操作

## 3.2 binder线程第二次调用 binder_ioctl
3.1 节，mOut已经写完数据了，

![binder_write_buf](http://upload-images.jianshu.io/upload_images/5688445-197589b9e33bf97d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

这里write_size=0, read_size 依然是 256，

只不过第二次进入binder_ioctl后， binder_thread->looper已经没有了`BINDER_LOOPER_STATE_NEED_RETURN`
所以binder_has_proc_work就会返回false, 然后binder线程就这样一直阻塞式的阻塞了。

整个过程如下

![binder线程阻塞](http://upload-images.jianshu.io/upload_images/5688445-0b4415ca7cc0da21.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 四、Server端的binder线程是怎么被唤醒的呢？

由第三节知道 Server端的binder线程一直阻塞在 binder_thread_read 这个函数的中的， 也就是    
```
ret = wait_event_freezable_exclusive(proc->wait, binder_has_proc_work(proc, thread));
```
注意`wait_event_freezable_exclusive`是一个排他的等待队列，当唤醒该等待队列时，只会唤醒第一个。

对应的binder线程要被唤醒，那么就找下哪里  wake up了 proc->wait就知道了。

下面来看几种正常的情况

##４.1 **被其它进程(binder_proc)唤醒**
```
static void binder_transaction(struct binder_proc *proc,
                   struct binder_thread *thread,
                   struct binder_transaction_data *tr, int reply)
{
    ...
    if (target_thread) { //一般这里为false
        e->to_thread = target_thread->pid;
        target_list = &target_thread->todo;
        target_wait = &target_thread->wait;
    } else { //取得的是进程上的todo等待列表
        target_list = &target_proc->todo;
        target_wait = &target_proc->wait;
    }

    ...

    list_add_tail(&t->work.entry, target_list); 
    if (target_wait) //唤醒
        wake_up_interruptible(target_wait);  
    ...
}
```
当client进程需要获得server端的服务时，如调用一个"server端的API函数", 最终binder驱动会找到server的binder_proc，也就是代码中的target_proc,  接着client将要请求server的相关数据加入到server的等待队列中，也就是target_list, 最后通过wake_up_interruptible唤醒server线程来处理client的请求。

![binder线程的唤醒](http://upload-images.jianshu.io/upload_images/5688445-f8bb1321e89a9dac.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

注意，这里只会唤醒一个binder线程(因为睡眠时是wait_event_freezable_exclusive)

## 4.2 被自己唤醒

4.1节会唤醒一个binder线程去执行客户端的请求，来看下server端客户线程会唤醒后做了什么操作

```
static int binder_ioctl_write_read(struct file *filp,
                unsigned int cmd, unsigned long arg, struct binder_thread *thread)
{
    ...
    if (bwr.write_size > 0) {
        ...
    }
    if (bwr.read_size > 0) {
        ret = binder_thread_read(proc, thread, bwr.read_buffer,
                     bwr.read_size,&bwr.read_consumed,filp->f_flags & O_NONBLOCK);
        if (!list_empty(&proc->todo)) 
            wake_up_interruptible(&proc->wait);
    }
    ...
}
```
当server有一个binder线程被唤醒了，这时binder线程就会返回到用户态去执行相关操作，在返回之前, binder线程又会检查是否现在binder驱动比较繁忙，也就是是否还有其它的客户端请求，如果还有，那么当前binder线程触发唤醒server进程上的等待的其它binder线程。
也就是说，当有binder线程被唤醒时，binder线程就可能会唤醒其它的binder线程尽量将当前等待着的请求执行完。

## 4.3 binder死亡通知
如linkToDeath, 这里不讨论这种情况。

# 五、client是怎么找到server端在binder驱动中的binder_proc的呢？

在4.1节binder_transaction，client通过找到target_proc，然后就唤醒了server端的binder线程，那么这个target_proc是怎么来的呢？
```
static void binder_transaction(struct binder_proc *proc,
                   struct binder_thread *thread,
                   struct binder_transaction_data *tr, int reply)
{
    struct binder_proc *target_proc;

    if (reply) { //reply在 BC_TRANACTION时为false
    } else { //进入else分支
        if (tr->target.handle) { //如果handle不为0，则进入下面的分支
            struct binder_ref *ref;
            ref = binder_get_ref(proc, tr->target.handle, true);
            //通过handle在当前binder_proc中获得target的binder_ref, 也就是binder_node的引用
            target_node = ref->node; //通过引用获得target binder node
        } else { //如果handle为0的话进入else分支
            target_node = binder_context_mgr_node; //这个是固定的ServiceManager
        }
        target_proc = target_node->proc;
    }
    if (target_thread) {
    } else {
        target_list = &target_proc->todo;
        target_wait = &target_proc->wait;
    }
    ...
}
```
哦，原来是通过 handle 这个整形数来获得的target binder proc.  那这个handle又是什么呢? 从上面看出是binder_transaction_data里面的handle,

![binder_transaction_data](http://upload-images.jianshu.io/upload_images/5688445-de9d504704cff621.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

而整个transact传递流程如下

![BpBinder transact](http://upload-images.jianshu.io/upload_images/5688445-fb393eb781c5f374.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

从代码上来看
```
status_t BpBinder::transact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags)
{
    // Once a binder has died, it will never come back to life.
    if (mAlive) {
        status_t status = IPCThreadState::self()->transact(
            mHandle, code, data, reply, flags);
        if (status == DEAD_OBJECT) mAlive = 0;
        return status;
    }   
    return DEAD_OBJECT;
}
```
这个handle是BpBinder里的mHandle,

那么问题来了，BpBinder里的mHandle又是怎么初始化的呢
