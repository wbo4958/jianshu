转载请标注出处：http://www.jianshu.com/p/95e61dcaa1fe

![Binder内核驱动](http://upload-images.jianshu.io/upload_images/5688445-e577a51228ea6b32.gif?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

这幅图很好的描述了binder驱动的功能。本文将围绕这幅图来学习binder驱动。

由于网上关于binder的相关文章都是和ServiceManager相关了，很少有不通过ServiceManager来讲binder的，所以基于此，本文就不再涉及ServiceManager了，而是直接Client通过bindService去绑定Server端的Service, 然后Client获得Server端的一个远程代理，client再通过这个远程代理去获得Server端的版本号这个流程来说，具体流程如下：

![code sample](http://upload-images.jianshu.io/upload_images/5688445-f963a8aee5843e2d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

其中
- Client 
客户端进程

- Server
Server端进程

- IDemo
一个AIDL文件，它会自动生成 IDemo.Stub, IDemo.Stub.Proxy, 前者用于Server端提供服务，后者用于Client获得Server端IDemo.Stub的代理

- IDemoServer
是Server端继续了IDemo.Stub, 提供具体的服务

可以看出，如果直接通过 AIDL 进程跨进程通信，这里也会涉及到第三个进程(SystemServer),  也就是Server端并不会**"直接"**将**"IDemoServer"**传递给Client, 而是先传递给SystemServer, 再由SystemServer传递给Client, 

所以这里只需要说明其中一种情况即可。

本文前面两节简单描述了binder驱动中一些很重要的数据结构，然后第3节讲述了Server端在Binder驱动中创建binder_node以及对应的binder_ref, 接着在第4节讲述了Server将IDemoServer的引用先传递给AMS，然后再由AMS传递到Client进程，最好第5小节简单了说了下Client通过getVersion去获得一个服务。

# 一. 进程/线程在Binder驱动的中结构体

## 1.1 进程相关的信息 binder_proc

每个进程在打开binder时会创建一个 binder_proc
```
static int binder_open(struct inode *nodp, struct file *filp)
{
    struct binder_proc *proc;
    proc = kzalloc(sizeof(*proc), GFP_KERNEL); //生成一个binder_proc内核空间，用于表示该进程的相关信息
    if (proc == NULL)
        return -ENOMEM;
    get_task_struct(current); //增加当前进程描述符引用计数，因为binder将会有当前进程描述符的引用
    proc->tsk = current;  //引用到当前进程的进程描述符
    INIT_LIST_HEAD(&proc->todo);  //初始化todo list
    init_waitqueue_head(&proc->wait);  //初始化等待队列
    ...
    hlist_add_head(&proc->proc_node, &binder_procs); //将binder_proc加入到全局的 binder_procs列表中
    proc->pid = current->group_leader->pid; //current其实对应用户态的一个线程，这里找到它所在组的pid
    filp->private_data = proc; //将binder_proc保存到文件指针中
    ...
    return 0;
}
```

**flip->private_date = proc** 是将当前进程在Binder驱动中对应的binder_proc保存在文件指针中，那这个文件指针是谁呢？

在`ProcessState`初始化会open "/dev/binder" 设备，这时会得到一个文件句柄fd，保存到 ProcessState->mDriverFD中，open函数最终会调用到 binder_open, 所以这个file指针是与mDriverFD对应起来的。

在后面的 binder_ioctl/binder_mmap里 就是通过该文件指针找到当前进程对应的binder_proc的。

## 1.2 线程相关的结构体 - binder_thread

我们知道一个进程是多线程的，客户端去请求服务端是由一个进程的线程发起的，所以binder里应该有表示线程的结构体，也就是 binder_thread. 

**注意：线程相关的结构由binder_proc中的threads红黑树保存**

在 binder_ioctl中，会通过 binder_get_thread 从进程结构体中(binder_proc)去获得线程相关的binder_thread.

**注意：**在Linux kernel中其实并没有线程这么一说，用户态的线程在内核中都对应着一个task_struct, 看起来就像一个普通的进程，也就是说有自己的pid

```
static struct binder_thread *binder_get_thread(struct binder_proc *proc)
{
    struct binder_thread *thread = NULL;
    struct rb_node *parent = NULL;
    struct rb_node **p = &proc->threads.rb_node; //当前进程中所有的线程，找到树头

    while (*p) { //查找线程是否已经存在了
        parent = *p;
        thread = rb_entry(parent, struct binder_thread, rb_node);
        // current->pid 是指当前线程(用户态下的线程)的pid
        if (current->pid < thread->pid)
            p = &(*p)->rb_left;
        else if (current->pid > thread->pid)
            p = &(*p)->rb_right;
        else
            break;
    }
    //如果线程结构体不存在，则创建，并加入到binder_proc的threads里
    if (*p == NULL) {
        thread = kzalloc(sizeof(*thread), GFP_KERNEL);
        thread->proc = proc; //引用到进程的结构体
        thread->pid = current->pid;  //设置 pid
        init_waitqueue_head(&thread->wait);  //初始化线程的等待队列
        INIT_LIST_HEAD(&thread->todo); //线程的todo list
        rb_link_node(&thread->rb_node, parent, p);
        rb_insert_color(&thread->rb_node, &proc->threads);  //插入到binder_proc的threads中的红黑树中
        thread->looper |= BINDER_LOOPER_STATE_NEED_RETURN;
        thread->return_error = BR_OK; //设置返回错误码
        thread->return_error2 = BR_OK;
    }
    return thread;
}
```

# 二. Binder驱动中BBinder和BpBinder对应的结构体

我们知道在 Android Native上都对应着BBinder与BpBinder, 那么它们在Binder驱动是怎么表示的呢？

## 2.1 Binder驱动中BBinder实体 - Server端的实体

由 binder_node 表示, 具体是在 `binder_new_node` 中创建,

```
static struct binder_node *binder_new_node(struct binder_proc *proc,
                       binder_uintptr_t ptr,
                       binder_uintptr_t cookie)
{
    struct rb_node **p = &proc->nodes.rb_node; 
    struct rb_node *parent = NULL;
    struct binder_node *node;

    //在binder_proc中的nodes红黑树中查找，看是否已经有BBinder对象了
    while (*p) {
        parent = *p;
        node = rb_entry(parent, struct binder_node, rb_node);
        if (ptr < node->ptr)
            p = &(*p)->rb_left;
        else if (ptr > node->ptr)
            p = &(*p)->rb_right;
        else
            return NULL;
    }
    //如果没有找到，那么在内核空间中生成一个新的binder_node
    node = kzalloc(sizeof(*node), GFP_KERNEL);

    rb_link_node(&node->rb_node, parent, p); 
    rb_insert_color(&node->rb_node, &proc->nodes);//插入到binder_proc的nodes中
    node->proc = proc;  //引用到进程结构体binder_proc
    node->ptr = ptr; //保存用户态下的BBinder的地址
    node->cookie = cookie;
    node->work.type = BINDER_WORK_NODE;
    return node;
}
```

## 2.2 Binder驱动中BpBinder实体 - BBinder的引用也就是代理

```
static struct binder_ref *binder_get_ref_for_node(struct binder_proc *proc,
                          struct binder_node *node)
{
    struct rb_node *n;
    struct rb_node **p = &proc->refs_by_node.rb_node;
    struct rb_node *parent = NULL;
    struct binder_ref *ref, *new_ref;
    //查找是否binder_node已经创建了它的引用，
    while (*p) {
        parent = *p;
        ref = rb_entry(parent, struct binder_ref, rb_node_node);

        if (node < ref->node)
            p = &(*p)->rb_left;
        else if (node > ref->node)
            p = &(*p)->rb_right;
        else
            return ref;
    }
    //还没有创建binder_node的引用，则新生成一个binder_ref，
    new_ref = kzalloc(sizeof(*ref), GFP_KERNEL);
    new_ref->proc = proc; //指向当前进程的binder_proc
    new_ref->node = node;  //指向引用到的也就是代理的  binder_node
    rb_link_node(&new_ref->rb_node_node, parent, p);
    rb_insert_color(&new_ref->rb_node_node, &proc->refs_by_node); //插入到binder_proc的refs_by_node中

    new_ref->desc = (node == binder_context_mgr_node) ? 0 : 1; //指明是否是SeviceManager的引用
    for (n = rb_first(&proc->refs_by_desc); n != NULL; n = rb_next(n)) {
        ref = rb_entry(n, struct binder_ref, rb_node_desc);
        if (ref->desc > new_ref->desc)
            break;
        new_ref->desc = ref->desc + 1; //代理的desc是在当前的已有的代理上+1
    }

    p = &proc->refs_by_desc.rb_node;
    while (*p) {
        parent = *p;
        ref = rb_entry(parent, struct binder_ref, rb_node_desc);

        if (new_ref->desc < ref->desc)
            p = &(*p)->rb_left;
        else if (new_ref->desc > ref->desc)
            p = &(*p)->rb_right;
        else
            BUG();
    }
    rb_link_node(&new_ref->rb_node_desc, parent, p);
    rb_insert_color(&new_ref->rb_node_desc, &proc->refs_by_desc); //同时将binder_ref插入到 binder_proc里的refs_by_desc中
    if (node) {
        hlist_add_head(&new_ref->node_entry, &node->refs); //加入到refs列表中
    } else {
    }
    return new_ref;
}
```

# 三、Server端生成IDemoServer在驱动中的binder_node与binder_ref

先来看下Server端publishService的过程, 当AMS通知Server端handleBindService后，Server就会将Service publish给AMS
```
private void handleBindService(BindServiceData data) {
    ...
    IBinder binder = s.onBind(data.intent);
    ActivityManagerNative.getDefault().publishService(data.token, data.intent, binder);
    ...
}
```
```
    public void publishService(IBinder token,
            Intent intent, IBinder service) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        data.writeInterfaceToken(IActivityManager.descriptor);
        data.writeStrongBinder(token);
        intent.writeToParcel(data, 0);
        data.writeStrongBinder(service);
        mRemote.transact(PUBLISH_SERVICE_TRANSACTION, data, reply, 0);
        reply.readException();
        data.recycle();
        reply.recycle();
    }
```
**publishService就是转换成图2所示的数据流**

![图2 binder数据流](http://upload-images.jianshu.io/upload_images/5688445-6e225d74037a5393.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

从第二节已经得知 binder_node与binder_ref分别是在 binder_new_node 与 binder_get_ref_for_node中创建中，那这两个函数创建的时机是什么呢？换句话说这两个函数是在代码中哪里被调用到的呢？

```
binder_ioctl
    -> case BINDER_WRITE_READ
        binder_ioctl_write_read
          -> binder_ioctl_write_read
                -> binder_thread_write
```

其实 `binder_thread_write` 也就是对图2进行解包的过程
```
static int binder_ioctl_write_read(struct file *filp,  unsigned int cmd, unsigned long arg,  struct binder_thread *thread)
{
    int ret = 0;
    struct binder_proc *proc = filp->private_data; //这里获得对应进程结构体 binder_proc
    unsigned int size = _IOC_SIZE(cmd); //此时 cmd也就是 BC_TRANSACTION
    void __user *ubuf = (void __user *)arg; //用户态数据地址
    struct binder_write_read bwr;

    if (copy_from_user(&bwr, ubuf, sizeof(bwr))) { //将用户态数据拷贝到内核态下，也就是获得图2中的binder_write_read
        ...
    }
    if (bwr.write_size > 0) { //如果write_size >0 , 表示write_buffer有数据，这里需要进行写操作
        ret = binder_thread_write(proc, thread,
                      bwr.write_buffer,
                      bwr.write_size,
                      &bwr.write_consumed);
        ...
    }
    if (bwr.read_size > 0) { //如果read_size >0 , 表示需要读数据，
        ret = binder_thread_read(proc, thread, bwr.read_buffer,
                     bwr.read_size,
                     &bwr.read_consumed,
                     filp->f_flags & O_NONBLOCK);
        ...
    }

    if (copy_to_user(ubuf, &bwr, sizeof(bwr))) { //将处理完的数据binder_write_read再拷贝到用户空间
       ...
    }
out:
    return ret;
}
```
## 3.1 binder_thread_write

```
static int binder_thread_write(struct binder_proc *proc, struct binder_thread *thread,
            binder_uintptr_t binder_buffer, size_t size, binder_size_t *consumed)
{
    uint32_t cmd;
   //binder_buffer也就是binder_write_read.write_buffer, 其实也就是 IPCProcessState里的Parcel.mData
    void __user *buffer = (void __user *)(uintptr_t)binder_buffer;
    void __user *ptr = buffer + *consumed; //此时 consumed还为0， ptr指向Parcel.mData
    void __user *end = buffer + size;
```

![图3](http://upload-images.jianshu.io/upload_images/5688445-8fc60a08d2db6df8.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
    while (ptr < end && thread->return_error == BR_OK) {
        if (get_user(cmd, (uint32_t __user *)ptr))  //这里得到的cmd也就是BC_TRANSACTION
            return -EFAULT;
        ptr += sizeof(uint32_t);  //ptr往后移4个字节，也就是图3的偏移4处，这时指向 binder_transaction_data
        switch (cmd) {
        ...
        case BC_TRANSACTION:  //进入该分支
        case BC_REPLY: {
            struct binder_transaction_data tr;

            if (copy_from_user(&tr, ptr, sizeof(tr))) //将用户态下的binder_transaction_data拷贝到内核态下
            ptr += sizeof(tr);  //继续移动ptr, 此时ptr指向了end
            binder_transaction(proc, thread, &tr, cmd == BC_REPLY);
            break;
        }
        *consumed = ptr - buffer;  //这里修改 binder_write_read里 write_consumed 值表示消费了这么多字节 
    }
    return 0;
}
```
继续来看binder_transaction, 此时传入的 binder_transaction_data为图4所示，另外，**reply = cmd == BC_REPLY**, 这里为false.

![图4 binder_transaction_data](http://upload-images.jianshu.io/upload_images/5688445-de9d504704cff621.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
static void binder_transaction(struct binder_proc *proc, struct binder_thread *thread,
                   struct binder_transaction_data *tr, int reply)
{ 
    if (reply) { //reply为false
      ...
    } else {
        if (tr->target.handle) { 
         //如图4所示， 因为此时是通过 IActivityManager 将IDemoServer的publishSerive,
        // 所以这里的target.handle是指 IActivityManager的代理, 且不为空
            struct binder_ref *ref;
            //获得IActivityManager代理对应的binder_ref, 见第3.1小节
            ref = binder_get_ref(proc, tr->target.handle, true);
            //通过IActivityManager代理的binder_ref找到binder_node,也就是"activity" serivice对应的BBinder实体
            target_node = ref->node;
        } else {  //这里是与serivce manager相关的
            target_node = binder_context_mgr_node;
        }
      
        target_proc = target_node->proc; //找到"activity" service所在的进程结构体 binder_proc
        ...
    }
    if (target_thread) { //此时target_thread为空
    } else {
        target_list = &target_proc->todo;  //从"activity" service的binder_proc获得target_list
        target_wait = &target_proc->wait; //从"activity" service的binder_proc中获得systemservice进程的等待队列
    }
    //分配一个binder_transaction结构体
    t = kzalloc(sizeof(*t), GFP_KERNEL);
    //分配一个binder_work结构体
    tcomplete = kzalloc(sizeof(*tcomplete), GFP_KERNEL);

    //下面开始组装 binder_transaction结构体
    t->sender_euid = task_euid(proc->tsk);  //获得euid
    t->to_proc = target_proc;  //将binder_transaction发送给哪个进程的binder_proc
    t->to_thread = target_thread;//将binder_transaction发送给哪个进程的线程 target_thread
    t->code = tr->code; //这里是PUBLISH_SERVICE_TRANSACTION
    t->flags = tr->flags; 
    t->priority = task_nice(current);
    // binder_alloc_buf从target_proc，也就是"activity" service所在的进程 systemserver进程中分配binder_buffer，
    //主要是为了后面保存用户态的数据
    t->buffer = binder_alloc_buf(target_proc, tr->data_size,
        tr->offsets_size, !reply && (t->flags & TF_ONE_WAY));
    t->buffer->allow_user_free = 0;
    t->buffer->debug_id = t->debug_id;
    t->buffer->transaction = t;
    t->buffer->target_node = target_node;

    ...
    //offp用来获得flatten_binder_object, 找到offp的
    offp = (binder_size_t *)(t->buffer->data + ALIGN(tr->data_size, sizeof(void *)));

    //将 binder_transaction_data中的 data.ptr.buffer也就是Parcel中的mData拷贝到binder_transaction的buffer->data中
    if (copy_from_user(t->buffer->data, (const void __user *)(uintptr_t)
               tr->data.ptr.buffer, tr->data_size)) {
    }
    // offp是拷贝完Parcel的mData后面。这里把 Parcel的mObjects拷贝到 offp 处，
    //注意: mObjects是保存flatten_binder_object结构体的位置的
    if (copy_from_user(offp, (const void __user *)(uintptr_t)
               tr->data.ptr.offsets, tr->offsets_size)) {
    }
    for (; offp < off_end; offp++) {
        struct flat_binder_object *fp;
        fp = (struct flat_binder_object *)(t->buffer->data + *offp);
        off_min = *offp + sizeof(struct flat_binder_object);
        switch (fp->type) { //这里只看 IDemoServer相关的，不看token相关的
        case BINDER_TYPE_BINDER:
        case BINDER_TYPE_WEAK_BINDER: { //由图2可知，这里进入该分支
            struct binder_ref *ref;
            struct binder_node *node = binder_get_node(proc, fp->binder);
            if (node == NULL) {
                //哦，原来在这里将 IDemoServer对应的 binder_node创建出来了
                node = binder_new_node(proc, fp->binder, fp->cookie);
            }
            //获得binder_node对应的引用
            ref = binder_get_ref_for_node(target_proc, node);
            //由于IDemoServer是本地binder_node, 这里要将binder_transaction传递给systemserver,
            //所以将它改为  BINDER_TYPE_HANDLE, 即传binder_ref到systemserver
            if (fp->type == BINDER_TYPE_BINDER)
                fp->type = BINDER_TYPE_HANDLE;
            else
                fp->type = BINDER_TYPE_WEAK_HANDLE;
            fp->binder = 0;
            fp->handle = ref->desc;  //获得binder_ref的handle值，这个在生成BpBinder时会传入
            fp->cookie = 0;
        } break;
    }
    if (reply) {
        //这里target_thread为空
        binder_pop_transaction(target_thread, in_reply_to);
    } else if (!(t->flags & TF_ONE_WAY)) {
        ...
    } else {
        ...
    }
    t->work.type = BINDER_WORK_TRANSACTION;
    list_add_tail(&t->work.entry, target_list); //将binder_transaction插入到systemserver进程的todo列表里面
    tcomplete->type = BINDER_WORK_TRANSACTION_COMPLETE; //这个是返回给当前进程用户态的
    list_add_tail(&tcomplete->entry, &thread->todo);  //将tcomplete加入到当前线程的todo list里
    if (target_wait) //如果systemserver正有等待队列的话，唤醒systemserver进程
        wake_up_interruptible(target_wait);
    return;
}
```

由 `binder_transaction`可知，binder_transaction进一步将数据组装成 struct binder_transaction, 

![图5 binder_transaction结构体](http://upload-images.jianshu.io/upload_images/5688445-e8a502f7731ba0e4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![图6 binder_transaction函数将binder转换成binder引用传到target进程中](http://upload-images.jianshu.io/upload_images/5688445-200d214d5b4dfd2a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

binder_transaction函数的主要作用

1.  **生成binder_transaction结构体**
2. **生成IDemoServer对应的binder_node与binder_ref**
3. **修改binder_transaction里的binder_buffer, 将IDemoServer的引用号也就是binder_ref里的desc(handle)传递给其它进程块(这里是system_server)**
4. **将binder_transaction传入到目标进程块(这里是system_server)的binder_proc的todo列表里**
5. **唤醒system_server来处理PUBLISH_SERVICE_TRANSACTION服务**

**同理, System_server将相同的数据传递给IDemoClient进程，这样，IDemoClient也就是是持有的是IDemoServer的在binder驱动中的引用的handle值。见第四节分析**

## 3.2 binder_thread_read
Server进程在完成binder_thread_write后，接着就会执行binder_thread_read, 参见binder_ioctl_write_read.

```
static int binder_thread_read(struct binder_proc *proc,  struct binder_thread *thread,
                  binder_uintptr_t binder_buffer, size_t size,  binder_size_t *consumed, int non_block)
{
    void __user *buffer = (void __user *)(uintptr_t)binder_buffer;
    void __user *ptr = buffer + *consumed;
    void __user *end = buffer + size;

    int ret = 0;
    int wait_for_proc_work;

    if (*consumed == 0) { //这里第一次进来是0
        if (put_user(BR_NOOP, (uint32_t __user *)ptr))  //将BR_NOOP return码返回给当前线程
        ptr += sizeof(uint32_t); //ptr移位4个字节
    }
retry:
    //由3.2小节可知 当前线程里有todo list, 即有个 tcomplete, 所以wait_for_proc_work这里为false
    wait_for_proc_work = thread->transaction_stack == NULL && list_empty(&thread->todo);
    if (wait_for_proc_work) { //wait_for_proc_work为false
            ...
    } else {
            //由于此时 thread->todo 列表里不为空，所以这里并不会一直阻塞的等待这里,
            ret = wait_event_freezable(thread->wait, binder_has_thread_work(thread));
    }

    while (1) {
        uint32_t cmd;
        struct binder_transaction_data tr;
        struct binder_work *w;
        struct binder_transaction *t = NULL;

        if (!list_empty(&thread->todo)) {
            //获得第一个binder_work, 也就是在3.1小节中的 tcomplete
            w = list_first_entry(&thread->todo, struct binder_work,  entry);
        } else if (!list_empty(&proc->todo) && wait_for_proc_work) {
            ...
        } else {
        }

        if (end - ptr < sizeof(tr) + 4)
            break;

        switch (w->type) { //这个w->type 为BINDER_WORK_TRANSACTION_COMPLETE
        case BINDER_WORK_TRANSACTION: {
              ...
        } break;
        case BINDER_WORK_TRANSACTION_COMPLETE: {
            cmd = BR_TRANSACTION_COMPLETE;
            if (put_user(cmd, (uint32_t __user *)ptr)) //再向用户态写入 BR_TRANSACTION_COMPLETE cmd
            ptr += sizeof(uint32_t); //ptr再移动4个字节

            list_del(&w->entry);  //从 thread 的 todo 列表里移除 binder_work
            kfree(w);
        } break;
    }
done:
    *consumed = ptr - buffer;  //读到了多少字节
    return 0;
}
```

![server publishService](http://upload-images.jianshu.io/upload_images/5688445-9ee124ef41eeabe2.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 四、Client进程获得IDemoServer的引用
## 4.1 AMS先得到IDemoServer的引用
Server端将Service publish到AMS，看来AMS是怎么接收的


由3.1小节可知，binder驱动在生成IDemoServer的binder_node后，**并将要传递给AMS的 IDemoServer" 改为传递给AMS为 IDemoServer在 Binder驱动对应的binder_ref引用号，且修改type为BINDER_TYPE_HANDLE. 然后唤醒AMS的binder线程来接收binder transaction. 代码如下
```
    t->work.type = BINDER_WORK_TRANSACTION;
    list_add_tail(&t->work.entry, target_list);
    if (target_wait)
        wake_up_interruptible(target_wait);

```

有了这些作铺垫后，来看下AMS是怎么接收的, 具体是 `binder_thread_read`

```
static int binder_thread_read(...) {
    ...
    //binder线程一直阻塞等待这里, 
    ret = wait_event_freezable_exclusive(proc->wait, binder_has_proc_work(proc, thread));
    ...
    //被唤醒后
      w = list_first_entry(&proc->todo, struct binder_work, entry); //从binder_proc的todo列表里取出一个binder_work
        switch (w->type) {
        case BINDER_WORK_TRANSACTION: { //进入该分支，见上面
            t = container_of(w, struct binder_transaction, work);  
            //通过container_of获得binder_transaction,自行百度 container_of
        } break;
        //接下来的代码就是将binder_transaction封装成binder_transaction_data, 传递给用户态来解析
        tr.data_size = t->buffer->data_size;
        tr.offsets_size = t->buffer->offsets_size;
        tr.data.ptr.buffer = (binder_uintptr_t)((uintptr_t)t->buffer->data +proc->user_buffer_offset);
        tr.data.ptr.offsets = tr.data.ptr.buffer +ALIGN(t->buffer->data_size,sizeof(void *));
        if (put_user(cmd, (uint32_t __user *)ptr)) //这里cmd是BR_TRANSACTION
        ptr += sizeof(uint32_t);
        if (copy_to_user(ptr, &tr, sizeof(tr))) //拷贝到用户态
}
```
`binder_thread_read`最主要的作用就是获得binder_transaction, 然后将binder_transaction里的数据封装到binder_transaction_data，然后再将binder_transaction_data保存到binder_write_read里也就是**mIn Parcel的mData中**，最后由内核态回到用户态

```
status_t IPCThreadState::talkWithDriver() {
  if (ioctl(mProcess->mDriverFD, BINDER_WRITE_READ, &bwr) >= 0) //陷入内核态
    //返回内核态
        if (bwr.read_consumed > 0) {
            mIn.setDataSize(bwr.read_consumed);  //设置 data 大小
            mIn.setDataPosition(0); //设置data偏移
        }
}
```
整个流程为
```
getAndExecuteCommand -> talkWithDriver -> executeCommand ->
```
用户态获得binder_transaction_date数据
```
executeCommand () {
case BR_TRANSACTION:
    binder_transaction_data tr; //
    result = mIn.read(&tr, sizeof(tr));   
    //传给ActivityManagerNative的native BBinder的transact
    error = reinterpret_cast<BBinder*>(tr.cookie)->transact(tr.code, buffer,  &reply, tr.flags);
}
```
接着传递到ActivityManagerNative
```
BBinder::transact -> JavaBBinder::onTransact  (Native层)
-> Binder::execTransact -> ActivityManagerNative::onTransact (Java层)
```
```
ActivityManagerNative.java

  onTransaction() {
        case PUBLISH_SERVICE_TRANSACTION: {
            data.enforceInterface(IActivityManager.descriptor);
            IBinder token = data.readStrongBinder();
            Intent intent = Intent.CREATOR.createFromParcel(data);
            IBinder service = data.readStrongBinder();
            publishService(token, intent, service);
            reply.writeNoException();
            return true;
        }
```

我们现在只关心
```
IBinder service = data.readStrongBinder();
```
来看下 readStrongBinder 的流程 
```
readStrongBinder -> nativeReadStrongBinder -> android_os_Parcel_readStrongBinder
-> javaObjectForIBinder(env, parcel->readStrongBinder())
```
```
status_t Parcel::readStrongBinder(sp<IBinder>* val) const
{
    return unflatten_binder(ProcessState::self(), *this, val); 
}
status_t unflatten_binder(const sp<ProcessState>& proc,
    const Parcel& in, sp<IBinder>* out)
{
    const flat_binder_object* flat = in.readObject(false);

    if (flat) {
        switch (flat->type) {
            case BINDER_TYPE_BINDER:
                *out = reinterpret_cast<IBinder*>(flat->cookie);
                return finish_unflatten_binder(NULL, *flat, in);
            case BINDER_TYPE_HANDLE:
                *out = proc->getStrongProxyForHandle(flat->handle);
                return finish_unflatten_binder(
                    static_cast<BpBinder*>(out->get()), *flat, in);
        }
    }
    return BAD_TYPE;
}
```
相当于就是解析图6
![图6 binder_transaction函数将binder转换成binder引用传到target进程中](http://upload-images.jianshu.io/upload_images/5688445-200d214d5b4dfd2a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

由图可知，最终进入 BINDER_TYPE_HANDLE

```
sp<IBinder> ProcessState::getStrongProxyForHandle(int32_t handle)
{
    sp<IBinder> result;
    handle_entry* e = lookupHandleLocked(handle); 
  //获得一个handle_entry, 进程中所有的远端代理都由handle_entry表示，并由mHandleToObject保存
    if (e != NULL) {
        IBinder* b = e->binder;
        if (b == NULL || !e->refs->attemptIncWeak(this)) {
            if (handle == 0) { //这里是ServiceManager
                Parcel data;
                status_t status = IPCThreadState::self()->transact(
                        0, IBinder::PING_TRANSACTION, data, NULL, 0);
                if (status == DEAD_OBJECT)
                   return NULL;
            }

            b = new BpBinder(handle);  //获得BpBinder
            e->binder = b;
            if (b) e->refs = b->getWeakRefs();
            result = b;
        } else {
            result.force_set(b);
            e->refs->decWeak(this);
        }
    }
    return result;
}
```

从 getStrongProxyForHandle 可知，AMS根据IDemoServer在Binder驱动中的binder_node的binder_ref引用号生成一个native的BpBinder. 由此可知这个handle号是识别一个远程代理的标识.

接着javaObjectForIBinder生成一个BinderProxy, 用来保存BpBinder的地址，最终在Java层传递给AMS是BinderProxy. 

## 4.2 Client获得IDemoServer引用

AMS拿到IDemoServer的引用，在Java层也就是BinderProxy后来看下它做了些什么操作呢？

接着ActivityManagerNative.java 的 onTransaction() 来看publishService
```
  onTransaction() {
        case PUBLISH_SERVICE_TRANSACTION: {
            data.enforceInterface(IActivityManager.descriptor);
            IBinder token = data.readStrongBinder();
            Intent intent = Intent.CREATOR.createFromParcel(data);
            IBinder service = data.readStrongBinder();
            publishService(token, intent, service);
            reply.writeNoException();
            return true;
        }
```

publishService最终会调用到 publishSericeLocked, 
```
 void publishServiceLocked(ServiceRecord r, Intent intent, IBinder service) {
      ...
            c.conn.connected(r.name, service);
      ...
}
```
而service也就是IDemoServer的远程代理也就是4.1节分析到最后的BinderProxy, 最后会调用到 Client中的ServiceConnection::connected(), 参数也就是这个BinderProxy, 可以看到AMS仅是作为一个中转的作用，它不会干预Server和Client相关的通信。
ServiceConnection也是一个Binder通信，只不过现在的Server端Client中的ServiceConnection, 此时AMS就变成了Client了.

接下来看下IServiceConnection中的connected函数

![图8 SystemServer通知Client Service Connected](http://upload-images.jianshu.io/upload_images/5688445-b83eac6405068f4f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
public void connected(android.content.ComponentName name, android.os.IBinder service) throws android.os.RemoteException
{
    android.os.Parcel _data = android.os.Parcel.obtain();
    try {
        _data.writeInterfaceToken(DESCRIPTOR);
        if ((name!=null)) {
            _data.writeInt(1);
            name.writeToParcel(_data, 0);
        }
        else {
            _data.writeInt(0);
        }
        _data.writeStrongBinder(service); 
        mRemote.transact(Stub.TRANSACTION_connected, _data, null, android.os.IBinder.FLAG_ONEWAY);
    }
    finally {
        _data.recycle();
    }
}
```
connected中,AMS将service也就是BinderProxy通过writeStrongBinder写入到Parcel中,

```
status_t Parcel::writeStrongBinder(const sp<IBinder>& val)
{
    return flatten_binder(ProcessState::self(), val, this);
}
status_t flatten_binder(const sp<ProcessState>& /*proc*/,
    const sp<IBinder>& binder, Parcel* out)
{
    flat_binder_object obj;

    obj.flags = 0x7f | FLAT_BINDER_FLAG_ACCEPTS_FDS;
    if (binder != NULL) {
        IBinder *local = binder->localBinder(); //这里binder是个远端代理 ,所以localBinder()为空
        if (!local) { //进入该分支
            BpBinder *proxy = binder->remoteBinder();
            const int32_t handle = proxy ? proxy->handle() : 0;
            obj.type = BINDER_TYPE_HANDLE;
            obj.binder = 0; /* Don't pass uninitialized stack data to a remote process */
            obj.handle = handle;
            obj.cookie = 0;
        } else {
        }
    } else {
    }
    ...
}
```
传递的flat_binder_object如下
![](http://upload-images.jianshu.io/upload_images/5688445-e948f4cf09877c9e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

最后在Binder驱动中的  binder_transaction函数中根据 BINDER_TYPE_HANDLE取出binder_ref
```
binder_transaction(...) {
...
        case BINDER_TYPE_HANDLE:
        case BINDER_TYPE_WEAK_HANDLE: {
            struct binder_ref *ref = binder_get_ref(proc, fp->handle,
                        fp->type == BINDER_TYPE_HANDLE);

            if (ref->node->proc == target_proc) {
                //这里的binder_ref的的binder_node的binder_proc是Server进程，而target_proc是Client进程，
                //所以不会走这里，不知道这里是不是给同一个进程使用binder时走的？未调研
            } else {
                struct binder_ref *new_ref;
                new_ref = binder_get_ref_for_node(target_proc, ref->node); //client第一次获得，所以创建一个新的binder_ref
                fp->binder = 0;
                fp->handle = new_ref->desc;  //设置handle号
                fp->cookie = 0;
                binder_inc_ref(new_ref, fp->type == BINDER_TYPE_HANDLE, NULL);
        } break;
...
}
```

最后Client走的流程就和AMS获得IDemoServer引用的流程一致了，

![](http://upload-images.jianshu.io/upload_images/5688445-a413f814ba412843.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


![图9 Client 获得 IDemoServe的引用](http://upload-images.jianshu.io/upload_images/5688445-b2fa2628b82c4822.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 五、Client进程获得Server进程的服务

Client要去获得Server的一个API服务，这里 Client通过IDemoServer的BinderProxy的handle值能够轻松找到 binder_ref, 
然后再通过 binder_ref的node可以找到 binder_node, 而这个binder_node就是Server端进程中的IDemoServer的binder_node, 
接着通过binder_node又可以很轻松的找到Server进程 binder_proc, 
最后将transaction插入到Server进程binder_proc中，然后binder驱动唤醒Server进程，


![Client获得Server服务](http://upload-images.jianshu.io/upload_images/5688445-e25e32048355333b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 六、酝酿中的binder问题

至此binder通信的整个流程都撸了一遍了。但是不是还有些东西感觉没有说啊，比如binder线程是怎么来的，Server进程阻塞在哪了，Client进程又是怎么被唤醒的这些啊。。。

所以打算再撸一篇带着问题来看binder. 待续
