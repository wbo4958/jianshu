[非阻塞同步算法与CAS(Compare and Swap)无锁算法](http://www.cnblogs.com/Mainz/p/3546347.html) 
[轻松学习java可重入锁(ReentrantLock)的实现原理](http://blog.csdn.net/yanyan19880509/article/details/52345422)

> ReentrantLock 可重入乐观锁
所谓可重入就是说当线程获得锁后，可以再继续获得该锁，也就是递归锁。
而所谓的乐观锁就是每次不加锁，而是假设没有冲突去完成某项操作，如果有冲突就重试，直到成功为止。
以下面的代码来看下ReentrantLock的实现吧
```
class LockTest {
	private ReentrantLock mLock;
	
	public LockTest() {
		mLock = new ReentrantLock();
	}
	
	void test() {
		mLock.lock();
		try {
			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		} finally {
			mLock.unlock();
		}
	}
}
public class JavaTest {
	public static void main(String[] args) {
		LockTest mLockTest = new LockTest();
		new Thread(()-> mLockTest.test(), "thread1").start();
		new Thread(()-> mLockTest.test(), "thread2").start();
	}
}
```
**ReentrantLock构造函数**
```
    public ReentrantLock() {
        sync = new NonfairSync();
    }

    public ReentrantLock(boolean fair) {
        sync = fair ? new FairSync() : new NonfairSync();
    }
```

![ReentrantLock](http://upload-images.jianshu.io/upload_images/5688445-cd0b7302c18f869f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

ReentrantLock更像是一个Wrapper, 内部由NonFairSync和FairSync分别实现非公平锁和公平锁，默认使用的是非公平锁。

# 一、thread1去获得lock
由代码例子可知, 正常情况下thread1先去尝试获得`mLock.lock()`

NonfairSync里实现的lock函数
```
        final void lock() {
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                acquire(1);
        }
```
```
    protected final boolean compareAndSetState(int expect, int update) {
        return unsafe.compareAndSwapInt(this, stateOffset, expect, update);
    }

    private volatile int state;
    private static final long stateOffset;
    static {
            stateOffset = unsafe.objectFieldOffset
                (AbstractQueuedSynchronizer.class.getDeclaredField("state"));
    }
```
先看下stateOffset这个是变量state在该类中的偏移量，知道偏移量后，再知道当前对象的地址，就可以知道state的真实地址是多少。就可以通过CAS指令去操作该地址。
注意，这里state被定义成volatile, 也就是state值每次都会从内存里去拿，不会从寄存器或是缓存中去取。

而 compareAndSetState 就是通过cpu的指令cas去比较 state 中的值是否是 expect, 如果是expect的，那将它更新到update的值。换句话说，如果当前state中的值是expect的话，则说明没有其它线程持有该锁，这时，就去尝试更新该state的值, 如果更新成功，compareAndSetState就会返回true, 表示获得该锁，反之返回false，表示尝试失败。

如例子所示， thread1在compareAndSetState会返回true, 此时state的值被设置为1了。

thread1在获得lock后通过setExclusiveOwnerThread将当前thread1设置为该锁的独占线程。
```
    protected final void setExclusiveOwnerThread(Thread thread) {
        exclusiveOwnerThread = thread;
    }
````

thread1在代码中获得锁后并没有做其它事情，此时，它就直接睡下去了。

# 二、thread2尝试获得锁
thread2执行的代码与thread1的一样，也会先尝试获得lock

```
        final void lock() {
            if (compareAndSetState(0, 1))
                setExclusiveOwnerThread(Thread.currentThread());
            else
                acquire(1);
        }
```
由于thread1已经获得该锁, 已经将 state 置为1了，所以此时 compareAndSetState 返回  false, 表示没有获得该锁。
接下来thead2调用acquire(1)
```
    public final void acquire(int arg) {
        if (!tryAcquire(arg) &&
            acquireQueued(addWaiter(Node.EXCLUSIVE), arg))
            selfInterrupt();
    }
```
## 2.1 tryAcquire
acquire会回调NonFairSync里的tryAcquire函数
```
        protected final boolean tryAcquire(int acquires) {
            return nonfairTryAcquire(acquires);
        }

        final boolean nonfairTryAcquire(int acquires) {
            final Thread current = Thread.currentThread(); //获得当前线程，也就是thread2
            int c = getState(); //获得state的值，此时，state还是被thread1设置为了1, 所以c = 1;
            if (c == 0) { //如果此时thread1释放锁了，此时c=0, 表明此时没有任何线程持有该锁
                if (compareAndSetState(0, acquires)) { //通过cas去获得锁
                    setExclusiveOwnerThread(current);
                    return true; //如果已经获得成功了，直接返回去
                }
            }
               //该分支是因为锁已经被持有了，getExclusiveOwnerThread获得持有该锁的线程
               //在该线程中，current是thread2, getExclusiveOwnerThread是thread1
            else if (current == getExclusiveOwnerThread()) {
                //而如果进入该分支，说明持有锁的线程再一次去获得该锁，结果就是将state加上acquires, 直到state为0时，才表示该锁被释放完
                int nextc = c + acquires;
                if (nextc < 0) // overflow
                    throw new Error("Maximum lock count exceeded");
                setState(nextc); //设置state的值，这里是在同一个线程中去state进行设置
                return true;
            }
            return false;
        }
```
tryAcquire其实针对两种情况，
- 1. thread1线程已经获得了锁。
     thread2再去尝试一下，如果thread1在thread2尝试的时候把锁释放了呢，这时thread2不就获得锁了么。
- 2. thread1线程已经获得了锁，再尝试去获得一次，
    这种情况下，thread1就会将锁的计数, 此时state就是锁加上一个值，本例中为1

## 2.2 将thread2放到等待队列中
thread2在经过tryAcquire还是没有获得锁，因为thread2并不知道thread1会在什么时候释放锁，所以如果还是不停的try, 是很不明智的，是严重浪费cpu的。

`acquireQueued(addWaiter(Node.EXCLUSIVE), arg)`

### addWaiter
```
    private Node addWaiter(Node mode) {
        Node node = new Node(Thread.currentThread(), mode);//创建一个以当前线程作为key的节点
        Node pred = tail; //获得队尾节点
        if (pred != null) {
            node.prev = pred;
            if (compareAndSetTail(pred, node)) { //通过CAS去判断当前是否有其它线程更新了tail,
                //如果tail并没有被更新的话，此时就将node放到队尾即可
                pred.next = node;
                return node;
            }
        }
        //OMG的，队列已经被修改过了，或者tail=null，调用 enq 函数入队列
        enq(node);
        return node;
    }
```
```
    private Node enq(final Node node) {
        for (;;) {
            Node t = tail;
            if (t == null) { // Must initialize  //初始化
                if (compareAndSetHead(new Node())) //创建一个新的节点作为head节点
                    tail = head;
            } else {
                node.prev = t;
                if (compareAndSetTail(t, node)) { //一直将node加载到队尾
                    t.next = node;
                    return t;
                }
            }
        }
    }
```
enq函数是主要是将Node加入到队列中，它通过for死循环不断的通过CAS去将node加入到队尾，成功了，就返回。 相当的精秒。

### acquireQueued
通过将addWaiter已经将thread2加入到了队列中了。此时thread2就要等着去获得锁了
```
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor(); //找到node的上一个节点
//如果上一个节点是head,也就是说当前节点是队列中的第一个节点，那么，在挂断之前再次尝试该锁
                if (p == head && tryAcquire(arg)) { 
                    //进入该分支，是因为其它线程已经释放了锁，队列中第一个等着的节点获得了锁，该返回去了。
                    setHead(node); //重新更新 head
                    p.next = null; // help GC
                    failed = false;
                    return interrupted;
                }
                //如果第一个节点没有获得锁，或者说不是第一个节点，则下面就决定是否要挂起线程等待锁发生
                if (shouldParkAfterFailedAcquire(p, node) &&  parkAndCheckInterrupt()) {
                    interrupted = true; 
                    //如果挂起的线程被唤醒了，且线程还是interrupted.这时进入该分支
                }
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }
```
acquireQueued其实很简单，针对队列中的第一个节点，或非第一个节点，
- 如果是第一个节点
这时，**如果没有其它线程再跟我抢锁的话**， 我就应该是第一个获得该锁的。所以会tryAcquire(), 如果抢失败了，则可能再次被阻塞，但是依然是第一个节点

- 如果是非第一个节点
没办法，即使锁被释放了，也轮不到你，第一个节点还没获得锁呢。直接休眠

接着看下 shouldParkAfterFailedAcquire
```
    private static boolean shouldParkAfterFailedAcquire(Node pred, Node node) {
        int ws = pred.waitStatus; //节点的等待状态，默认为0
        if (ws == Node.SIGNAL) //如果等待状态为SIGNAL, 此时该节点就可以放心的睡下去了，等着release去唤醒了
            /*
             * This node has already set status asking a release
             * to signal it, so it can safely park.
             */
            return true;
        if (ws > 0) { //cancel的状态，跳过该节点
            /*
             * Predecessor was cancelled. Skip over predecessors and
             * indicate retry.
             */
            do {
                node.prev = pred = pred.prev;
            } while (pred.waitStatus > 0);  //跳过所有取消的节点
            pred.next = node;
        } else {
            /*
             * waitStatus must be 0 or PROPAGATE.  Indicate that we
             * need a signal, but don't park yet.  Caller will need to
             * retry to make sure it cannot acquire before parking.
             */
            //设置为SINGAL的等待状态，下一次就可以正常睡眠了
            compareAndSetWaitStatus(pred, ws, Node.SIGNAL);
        }
        return false;
    }
```
调用 parkAndCheckInterrupt 后，线程就睡下去了，此时thread2就睡下去了
```
    private final boolean parkAndCheckInterrupt() {
        LockSupport.park(this);
        return Thread.interrupted();
    }
```

# 三、thread1释放锁

从代码中可知，thread1在睡眠10s后就开始继续执行，最后在finally里通过 `mLock.unlock()`去释放锁。
```
    public void unlock() {
        sync.release(1);
    }
    public final boolean release(int arg) {
        if (tryRelease(arg)) { //调用tryRelease去释放锁, 如果该线程对该锁的计数为0后，就去unparkSuccessor
            Node h = head;
            if (h != null && h.waitStatus != 0) //不为0，表示现在有节点在等着SIGNAL
                unparkSuccessor(h);
            return true;
        }
        return false;
    }
        protected final boolean tryRelease(int releases) {
            int c = getState() - releases; //check state的值
            if (Thread.currentThread() != getExclusiveOwnerThread()) //异常
                throw new IllegalMonitorStateException();
            boolean free = false;
            if (c == 0) {
                free = true; // 在减去releases后为0，表示锁已经释放完成
                setExclusiveOwnerThread(null);
            }
            setState(c); //更新state值
            return free;
        }
```
```
    private void unparkSuccessor(Node node) {
        /*
         * If status is negative (i.e., possibly needing signal) try
         * to clear in anticipation of signalling.  It is OK if this
         * fails or if status is changed by waiting thread.
         */
        int ws = node.waitStatus; //获得等待状态
        if (ws < 0)  //如果小于0表示该队列上有线程在等着该锁
            compareAndSetWaitStatus(node, ws, 0); //将ws设置为默认的0

        /*
         * Thread to unpark is held in successor, which is normally
         * just the next node.  But if cancelled or apparently null,
         * traverse backwards from tail to find the actual
         * non-cancelled successor.
         */
        Node s = node.next;
        if (s == null || s.waitStatus > 0) { //找到第一个没有被cancel的节点
            s = null;
            for (Node t = tail; t != null && t != node; t = t.prev) //从后往前
                if (t.waitStatus <= 0) 
                    s = t;
        }
        if (s != null) //unpark等待的线程，此时thread2开始执行。
            LockSupport.unpark(s.thread);
    }
```
# 四、thread2获得锁
```
    final boolean acquireQueued(final Node node, int arg) {
        boolean failed = true;
        try {
            boolean interrupted = false;
            for (;;) {
                final Node p = node.predecessor();
                if (p == head && tryAcquire(arg)) {
                    setHead(node);  //更新node, 返回 interrupted.
                    p.next = null; // help GC
                    failed = false;  
                    return interrupted;
                }
                //thread2睡在这里，当thread2被唤醒后，再次执行for循环
                if (shouldParkAfterFailedAcquire(p, node) &&
                    parkAndCheckInterrupt()) {
                    interrupted = true;
               }
            }
        } finally {
            if (failed)
                cancelAcquire(node);
        }
    }
```
至此，thread2获得锁并继续执行后面的代码。

五、公平与非公平锁的区别。

非公平锁：当thread1在获得lock后，thread2也去尝试获得该lock, 此时thread2会被挂起，放到等待队列中。当thread1释放lock后，这时会唤醒thread2再次去尝试该锁，但如果此时thread1再次去获得lock，并且竞争成功获得该锁。此时thread2会再次被挂起，这种情况就是不公平的。

那公平锁的情况是，如果thread1发现等待队列中有其它的线程比如thread2在等待该锁，那thread1乖乖的加入队尾排队。此时thread2自然就获得锁并执行。代码就不解释了，挺简单的。

