BlockingQueue是一种阻塞式的队列，什么叫阻塞式？ 如果当前一个线程准备从BlockingQueue中获得数据，而此时BlockingQueue中还没有数据，此时，线程就会一直等着BlockingQueue里有数据，拿到数据为止，否则就一直等待。

BlockingQueue见的实现有很多，比较常见的是LinkedBlockingQueue, PriorityBlockingQueue, ArrayBlockingQueue, SynchronousQueue

![BlockingQueue](http://upload-images.jianshu.io/upload_images/5688445-0e4aaab23c4a59cc.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 一、LinkedBlockingQueue
```
    private final int capacity;
    private final AtomicInteger count = new AtomicInteger();
    transient Node<E> head;
    private transient Node<E> last;
```
其实一看到Linked开头，就应该猜想得到该BlockingQueue的底层是用链表实现的, LinkedBlockingQueue是用单向链表实现，头尾节点分别由 head 和 last 指出。

```
    public LinkedBlockingQueue() {
        this(Integer.MAX_VALUE);
    }
    public LinkedBlockingQueue(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException();
        this.capacity = capacity;
        last = head = new Node<E>(null);
    }
```
LinkedBlockingQueue默认的容量是Integer.MAX_VALUE, 非常大的值，也可以传入容量大小值。

## 1.1 入队
入队有两个函数可以做到, put 和 poll
- **put**
```
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        int c = -1;
        Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;
        final AtomicInteger count = this.count;
        putLock.lockInterruptibly(); //加锁
        try {
            while (count.get() == capacity) {  //如果当前Queue的容量已经达到最大的容量时，就一直等在 notFull 那
                notFull.await();
            }
            enqueue(node);  //加入队尾
            c = count.getAndIncrement(); // count+1
            if (c + 1 < capacity)
                notFull.signal();  //通知 notFull
        } finally {
            putLock.unlock();  //释放锁
        }
        if (c == 0)
            signalNotEmpty();
    }
```
- **offer**
```
    public boolean offer(E e) {
        if (e == null) throw new NullPointerException();
        final AtomicInteger count = this.count;
        if (count.get() == capacity)  //如果已经满了，直接返回
            return false;
        int c = -1;
        Node<E> node = new Node<E>(e);
        final ReentrantLock putLock = this.putLock;
        putLock.lock(); //锁
        try {
            if (count.get() < capacity) { //只有在数据还没有达到最大容量时才入队列
                enqueue(node);
                c = count.getAndIncrement();
                if (c + 1 < capacity)
                    notFull.signal(); //通知 notFull
            }
        } finally {
            putLock.unlock(); //释放锁
        }
        if (c == 0)
            signalNotEmpty();
        return c >= 0;
    }
```

从put和offer两个函数的实现来看，LinkedBlockingQueue是不能扩容的，也就是初始化时的容量， 如果当前容量已满，put和offer两个函数的处理方式不一样， put会一直等着容量可用，而offer直接返回。当然put和offer使用了putLock锁，但是他们锁的方式也不一样，这个到时会单独写一篇锁

## 1.2 出队
出队可以通过 poll, 和 take来实现 
- poll
```
    public E poll() {
        final AtomicInteger count = this.count;
        if (count.get() == 0) //如果当前queue里没有数据，直接返回
            return null;
        E x = null;
        int c = -1;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lock(); //take 锁
        try {
            if (count.get() > 0) { //只有当前queue里有数据才操作，否则直接退出
                x = dequeue();
                c = count.getAndDecrement();
                if (c > 1)
                    notEmpty.signal();
            }
        } finally {
            takeLock.unlock();  //unlock
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }
```
- take
```
    public E take() throws InterruptedException {
        E x;
        int c = -1;
        final AtomicInteger count = this.count;
        final ReentrantLock takeLock = this.takeLock;
        takeLock.lockInterruptibly();  //锁
        try {
            while (count.get() == 0) { //如果当前queue里没有数据，一直等在notEmpty里
                notEmpty.await();
            }
            x = dequeue(); //有数据了，
            c = count.getAndDecrement(); //数量-1
            if (c > 1)
                notEmpty.signal();
        } finally {
            takeLock.unlock(); //unlock
        }
        if (c == capacity)
            signalNotFull();
        return x;
    }
```
poll和take的区别在于对当前queue没有数据时处理不同
poll, 在没有数据时，直接返回。
take, 在没有数据时，会一直等着数据。
当然它们的锁的方式也不一样，

可以看出 offer和poll一对儿， put和take又是一对儿

像其它的 contains, peek, remove,都差不多，只不过都加了锁，因为BlockingQueue主要是用在多线程当中。

# 二、PriorityBlockingQueue
## 2.1 入队
实现方式 put 和 add
```
    public boolean add(E e) {
        return offer(e);
    }
    public boolean offer(E e) {
        if (e == null)
            throw new NullPointerException();
        final ReentrantLock lock = this.lock;
        lock.lock(); //锁
        int n, cap;
        Object[] array;
        while ((n = size) >= (cap = (array = queue).length))
            tryGrow(array, cap); //扩容, 扩容里面会unlock, 最后再lock, 所以后面的代码都是在lock里的
        try {
            Comparator<? super E> cmp = comparator;
            if (cmp == null)
                siftUpComparable(n, e, array); //堆排序，与PriorityQueue一样
            else
                siftUpUsingComparator(n, e, array, cmp);
            size = n + 1;
            notEmpty.signal();
        } finally {
            lock.unlock(); //unlock
        }
        return true;
    }
```
add最后也是使用的offer函数，offer函数首先检查是否需要扩容，然后再使用堆排序。

## 2.1 出队
```
    public E poll() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            return dequeue();
        } finally {
            lock.unlock();
        }
    }
```
poll是直接获得队头，如果是空的队列直接返回为null
```
    public E take() throws InterruptedException {
        final ReentrantLock lock = this.lock;
        lock.lockInterruptibly();
        E result;
        try {
            while ( (result = dequeue()) == null)
                notEmpty.await();
        } finally {
            lock.unlock();
        }
        return result;
    }
```
而take在队列为空的情况下会一直等着数据， dequeue()的实现就是从堆里去获得队头，然后再使用堆排序去调整堆结构。

# 三、SynchonousQueue
SynchonousQueue是一种同步队列, 同样以一个例子来说
```
public class ThreadTest {

	public static void main(String[] args) {
		SynchronousQueue<Integer> synchronousQueue = new SynchronousQueue<>();

		new Thread(() -> {
			try {
				System.out.println("take1 +");
				synchronousQueue.take();
				System.out.println("take1 -");
			} catch (InterruptedException e) {
			}
		}, "thead 1").start();
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
		}
		try {
			synchronousQueue.put(1);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
}
```
这个测试用例里生成了一个synchronousQueue， 新开了一个线程，利用SynchronousQueue.take()去做同步操作。然后main线程往Synchronous去put一个值。

SynchronousQueue的底层实现有两种形式，一种是Queue去实现，另一种以Stack去实现. 默认Stack的方式 也就是TransferStack，Queue与Stack的区别在于，Queue是先进先出，Stack是先进后出，什么意思呢?

如果两个线程1， 线程2先后调用 Synchronous.take， 那么当有其它线程去Synchronous.put时，如果是用Queue实现的话，那 线程1会被先唤醒。如果是用stack实现的话，那线程2会先唤醒。

## 3.1 Synchronous take操作
```
    public E take() throws InterruptedException {
        E e = transferer.transfer(null, false, 0);
        if (e != null)
            return e;
        Thread.interrupted();
        throw new InterruptedException();
    }
```

```
        E transfer(E e, boolean timed, long nanos) {
            SNode s = null; // constructed/reused as needed
            int mode = (e == null) ? REQUEST : DATA;  
            //根据e的值，来判断当前的是take还是put, 有数据时，即为DATA， 否则为REQUEST.

            for (;;) {
                SNode h = head;
                //take第一次进来，
                if (h == null || h.mode == mode) {  // empty or same-mode
                    if (timed && nanos <= 0) {      // take传过来的参数不会走这个if分支，
                       ...
                    } else if (casHead(h, s = snode(s, e, h, mode))) {// 先生成现代战争SNode,然后通过cas将该SNode赋值给head
                        SNode m = awaitFulfill(s, timed, nanos); //然后开始wait.这里会先spin 512次, 如果都没有put操作，则线程开始阻塞
                        if (m == s) {               //线程唤醒，但是此时canceled了
                            clean(s);
                            return null;
                        }
                        //有数据了，此时返回具体的数据
                        if ((h = head) != null && h.next == s)
                            casHead(h, s.next);     // 更新下一个wait到head
                        return (E) ((mode == REQUEST) ? m.item : s.item);
                    }
                } ....
            }
        }
```
![](http://upload-images.jianshu.io/upload_images/5688445-7cd1c133330620c8.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 3.2 Synchronous put操作
```
    public void put(E e) throws InterruptedException {
        if (e == null) throw new NullPointerException();
        if (transferer.transfer(e, false, 0) == null) {
            Thread.interrupted();
            throw new InterruptedException();
        }
    }
```
```
        E transfer(E e, boolean timed, long nanos) {

            SNode s = null; // constructed/reused as needed
            int mode = (e == null) ? REQUEST : DATA; //此时put的是数据，所以此时返回 DATA

            for (;;) {
                SNode h = head;
                if (h == null || h.mode == mode) {  // head,已经不为空了， 因为take过来, h.mode当前是REQUEST.
                    ...
                } else if (!isFulfilling(h.mode)) { // 进入该分支, h.mode = REQUEST
                    if (h.isCancelled())            // already cancelled
                        casHead(h, h.next);         // pop and retry
                    else if (casHead(h, s=snode(s, e, h, FULFILLING|mode))) {
                        for (;;) { // loop until matched or waiters disappear
                            SNode m = s.next;       // m 此时就是 REQUEST 那个线程的节点, S是put的那个线程的节点
                            if (m == null) {        // all waiters are gone
                                ...
                            }
                            SNode mn = m.next; // REQUEST后已经没有节点了，此时 mn为空
                            if (m.tryMatch(s)) { //已经match了，进入该分支，返回s.item. 也就是put的值
                                casHead(s, mn);     // request 的 data 节点都直接出栈
                                return (E) ((mode == REQUEST) ? m.item : s.item);
                            } else                  // lost match
                                s.casNext(m, mn);   // help unlink
                        }
                    }
                } else {                            // help a fulfiller
                            ...
                }
            }
        }
```
```
            boolean tryMatch(SNode s) {
                if (match == null &&
                    UNSAFE.compareAndSwapObject(this, matchOffset, null, s)) {
                   //将put的那个节点赋值给request的那个节点的  match
                    Thread w = waiter;  //然后唤醒request节点里的线程。
                    if (w != null) {    // waiters need at most one unpark
                        waiter = null;
                        LockSupport.unpark(w);
                    }
                    return true;
                }
                return match == s;
            }
```
![](http://upload-images.jianshu.io/upload_images/5688445-825a32adb0b9f622.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
