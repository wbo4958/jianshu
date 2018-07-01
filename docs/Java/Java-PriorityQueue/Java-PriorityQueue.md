Queue的特性是队尾入队，队头出队，先进先出。
PriorityQueue当然是带有优先级的队列

![Queue Overview](http://upload-images.jianshu.io/upload_images/5688445-902f3302772d7910.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
    transient Object[] queue; // non-private to simplify nested class access
    private int size = 0;

    public PriorityQueue() {
        this(DEFAULT_INITIAL_CAPACITY, null);
    }
    public PriorityQueue(int initialCapacity,
                         Comparator<? super E> comparator) {
        // Note: This restriction of at least one is not actually needed,
        // but continues for 1.5 compatibility
        if (initialCapacity < 1)
            throw new IllegalArgumentException();
        this.queue = new Object[initialCapacity];
        this.comparator = comparator;
    }
```
从构造函数可以看出queue的底层实现是数组，队头是queue[0], 队尾是 queue[size-1]
默认的 DEFAULT_INITIAL_CAPACITY = 11. 即默认的数组大小为11. 如果入队的数量超过11，那就会进行扩容
```
    private void grow(int minCapacity) {
        int oldCapacity = queue.length;
        // Double size if small; else grow by 50%
        int newCapacity = oldCapacity + ((oldCapacity < 64) ?
                                         (oldCapacity + 2) :
                                         (oldCapacity >> 1));
        // overflow-conscious code
        if (newCapacity - MAX_ARRAY_SIZE > 0)
            newCapacity = hugeCapacity(minCapacity);
        queue = Arrays.copyOf(queue, newCapacity);
    }
```
如果刚开始时的容量 小于64，那直接扩容到 oldCapacity*2 + 2
如果大于64， 那么直接扩容到 oldCapacity * 3 / 2

**入队**
```
    public boolean add(E e) {
        return offer(e);
    }
    public boolean offer(E e) {
        if (e == null)
            throw new NullPointerException();
        modCount++;
        int i = size;
        if (i >= queue.length)
            grow(i + 1);
        size = i + 1;
        if (i == 0)
            queue[0] = e;
        else
            siftUp(i, e);
        return true;
    }
```
第一个数据直接放到queue[0], 第二个及以上的数据通过 siftUp 入队

```
    private void siftUp(int k, E x) {
        if (comparator != null)
            siftUpUsingComparator(k, x);
        else
            siftUpComparable(k, x); 
    }
    private void siftUpComparable(int k, E x) {
        Comparable<? super E> key = (Comparable<? super E>) x;
        while (k > 0) {
            int parent = (k - 1) >>> 1;
            Object e = queue[parent];
            if (key.compareTo((E) e) >= 0)
                break;
            queue[k] = e;
            k = parent;
        }
        queue[k] = key;
    }
```
如果不使用传入的比较器，就使用默认的siftUpComparable, 从siftUpComparable可以看出，使用堆排序，建立的最小堆。
堆的性质:
- 完全二叉树
整棵树看起来是满的，除了叶子节点没有孩子节点外，其余所有节点都是左右孩子节点的。而我们的完全二叉树要求没这么严格，它并不要求每个非叶子节点都具有左右孩子，但一定要按照从左到右的顺序出现，不能说没有左孩子却有右孩子。以下是几个完全二叉树：
![完全二叉树](http://upload-images.jianshu.io/upload_images/5688445-cc76a0a18c075ada.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- 最小堆
父节点小于两个子节点

- 当前节点的索引用 i
则左右两个子节点分别为  2*i  和 2*i-1

建堆过程, 以如下代码为例
```
		priorityQueue.offer(6);
		priorityQueue.offer(5);
		priorityQueue.offer(3);
		priorityQueue.offer(1);
		priorityQueue.offer(4);
		priorityQueue.offer(8);
		priorityQueue.offer(7);	
```

![建堆过程](http://upload-images.jianshu.io/upload_images/5688445-a14eb2a26ccc2744.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

这里在插入第二个节点 5 时，为什么一定把它放到左节点呢？为什么不放在右节点呢？当时一直想不通，后来在测试第三个节点 3 时，发现第3个节点找的父节点和第二个节点找的父节点相同，所以第二个节点应该是左结点。
还有一点，堆排序是一棵完全二叉树，所以不可能先插入右节点。

**出队**
```
priorityQueue.poll();
```
经过入队操作，此时queue里的数据依次是
`1 3 5 6 4 8 7`
```
    public E poll() {
        if (size == 0)
            return null;
        int s = --size;
        modCount++;
        E result = (E) queue[0];
        E x = (E) queue[s];
        queue[s] = null;
        if (s != 0)
            siftDown(0, x);
        return result;
    }
```
直接返回queue[0], 最小值 1， 即队头的那个数据, 然后通过 siftDown去调整堆结构, 
**注意** 将队列的最后那个数据传入到siftDown里
```
    private void siftDown(int k, E x) {
        if (comparator != null)
            siftDownUsingComparator(k, x);
        else
            siftDownComparable(k, x);
    }

    private void siftDownComparable(int k, E x) {
        Comparable<? super E> key = (Comparable<? super E>)x;
        int half = size >>> 1;        // loop while a non-leaf
        while (k < half) {
            int child = (k << 1) + 1; // assume left child is least
            Object c = queue[child];
            int right = child + 1;
            //获得最小的子节点
            if (right < size &&
                ((Comparable<? super E>) c).compareTo((E) queue[right]) > 0)
                c = queue[child = right];
            //父节点与最小的子节点比较，
            if (key.compareTo((E) c) <= 0)
                break;
            queue[k] = c;
            k = child;
        }
        queue[k] = key;
    }
```
最后的queue的值为
`3 4 5 6 7 8`
![出队](http://upload-images.jianshu.io/upload_images/5688445-71f707ea727bc242.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

[堆结构的优秀实现类----PriorityQueue优先队列](https://www.jianshu.com/p/4c7ad59a0489)
https://www.cnblogs.com/chengxiao/p/6129630.html
