[HashMap实现原理及源码分析](http://www.cnblogs.com/chengxiao/p/6059914.html)
[Java 集合深入理解（17）：HashMap 在 JDK 1.8 后新增的红黑树结构](http://blog.csdn.net/u011240877/article/details/53358305)

![Map Overview](http://upload-images.jianshu.io/upload_images/5688445-6354d9bb14541f19.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 一、HashMap 

HashMap采用数组+链表+红黑树的方式解决碰撞冲突来存储数据.

## 1.1 resize()扩容
为了很直观的分析，在这里写如下代码进行测试分析
```
HashMap<String, String> hashMap = new HashMap<>(1);
hashMap.put("a", "apple");
```
```
    static final float DEFAULT_LOAD_FACTOR = 0.75f;
    public HashMap(int initialCapacity) {
        this(initialCapacity, DEFAULT_LOAD_FACTOR);
    }
    public HashMap(int initialCapacity, float loadFactor) {
        //initialCapacity和loadFactor条件检查
        this.loadFactor = loadFactor;
        this.threshold = tableSizeFor(initialCapacity);
    }
    static final int tableSizeFor(int cap) {
        int n = cap - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return (n < 0) ? 1 : (n >= MAXIMUM_CAPACITY) ? MAXIMUM_CAPACITY : n + 1;
    }
```
此时loadFactor=0.75,  threshold=1
HashMap的构造函数中，会为HashMap生成一个容量initialCapacity，也就是数组的大小。  HashMap并不是说你传入多少就给你生成那么大的数组大小，而是会根据你的输入大小，动态调用数组大小。
tableSizeFor这个函数就是用来处理上述的调整，注意，tableSizeFor函数返回与initiaCapacity相等或更大的那个**2的指数**， 如下所示，左边是输入的capacity,  右边是输出
```
输入            输出
0                   1
1                   1
2                   2
3                   4
4                   4
5                   8
6                   8
....
```
**put**
下面来接着看一下 put 方法
```
    public V put(K key, V value) {
        return putVal(hash(key), key, value, false, true);
    }
    //算hash值的函数
    static final int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }
```
```
    final V putVal(int hash, K key, V value, boolean onlyIfAbsent, boolean evict) {
        Node<K,V>[] tab; Node<K,V> p; int n, i;
```

- 第一次扩容
```
        if ((tab = table) == null || (n = tab.length) == 0)
            n = (tab = resize()).length; //第一次进来，table为null, 所以会进入resize函数
```
resize函数
```
    final Node<K,V>[] resize() {
        Node<K,V>[] oldTab = table;  //此时都回null
        int oldCap = (oldTab == null) ? 0 : oldTab.length;  //oldCap为0
        int oldThr = threshold;  //threshold当前为1
        int newCap, newThr = 0;
        if (oldCap > 0) {
          ...
        }
        else if (oldThr > 0) // initial capacity was placed in threshold
            newCap = oldThr;  //newCap=1
        else {               // zero initial threshold signifies using defaults
           ...
        }
        if (newThr == 0) {  //进入该分支
            float ft = (float)newCap * loadFactor; //ft此时是1 * 0.75
            newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                      (int)ft : Integer.MAX_VALUE); // newThr 为 (int)0.75 = 0
        }
        threshold = newThr; //threshold = 0
        @SuppressWarnings({"rawtypes","unchecked"})
            Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap]; //此时容量只有1
        table = newTab;
        if (oldTab != null) { //原来的table还是null
        }
        return newTab;
    }
```
resize后， threshold=0, table 数组大小为1，此时还没有具体赋值
![table[0]](http://upload-images.jianshu.io/upload_images/5688445-5a417781e70015c0.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- 生成结点，放入Hash数组中
```
        if ((p = tab[i = (n - 1) & hash]) == null) //第一组数据，肯定不会有冲突
            tab[i] = newNode(hash, key, value, null);
        else {
              //有冲突
        }
```
![Table](http://upload-images.jianshu.io/upload_images/5688445-f115704a6db16c08.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- 再扩容
```
        ++modCount;
        if (++size > threshold) //此时size=0, ++size后size变为1，但此时threhold为0 ，所以此时会再扩容
            resize();
        afterNodeInsertion(evict);
        return null;
    }
```
```
    final Node<K,V>[] resize() {
        Node<K,V>[] oldTab = table; //将table保存到oldTable中
        int oldCap = (oldTab == null) ? 0 : oldTab.length; //此时 oldCap=1
        int oldThr = threshold;  //oldThr = 0
        int newCap, newThr = 0;
        if (oldCap > 0) { //进入该分支
            if (oldCap >= MAXIMUM_CAPACITY) {
                threshold = Integer.MAX_VALUE;
                return oldTab;
            }
            else if ((newCap = oldCap << 1) < MAXIMUM_CAPACITY &&
                     oldCap >= DEFAULT_INITIAL_CAPACITY)  //newCap = oldCap*2 = 2
                newThr = oldThr << 1; // double threshold
        }
  
        if (newThr == 0) { //进入该分支，重新计算threshold
            float ft = (float)newCap * loadFactor;  // 2 * 0.75 = 1.5
            newThr = (newCap < MAXIMUM_CAPACITY && ft < (float)MAXIMUM_CAPACITY ?
                      (int)ft : Integer.MAX_VALUE);  //newThr = 1
        }
        threshold = newThr;  //更新threhold为1
        @SuppressWarnings({"rawtypes","unchecked"})
            Node<K,V>[] newTab = (Node<K,V>[])new Node[newCap];  //生成一个新的数组，此时数组大小为2
        table = newTab;  //table指向新的数组
        if (oldTab != null) {  //旧的数组不为空，且里面有值，此时，将旧的数组更新到新的数组中。
            for (int j = 0; j < oldCap; ++j) {
                Node<K,V> e;
                if ((e = oldTab[j]) != null) {
                    oldTab[j] = null; //清掉旧数组上的值，便于GC
                    if (e.next == null)
                        newTab[e.hash & (newCap - 1)] = e; //将旧的节点更新到新的table中
                    else if (e instanceof TreeNode)  //针对冲突是树型的。此时会split
                        ((TreeNode<K,V>)e).split(this, newTab, j, oldCap);
                    else { // preserve order  针对单链表， 对链表中的每个节点，重新放入到新的table中，保持原来的顺序
                         //将单链表节点放入到loHead中或hiHead, 最后将loHead或hiHead直接插入到新的table中
                        Node<K,V> loHead = null, loTail = null;
                        Node<K,V> hiHead = null, hiTail = null;
                        Node<K,V> next;
                        do {
                            next = e.next;
                            if ((e.hash & oldCap) == 0) {
                                if (loTail == null)
                                    loHead = e;
                                else
                                    loTail.next = e;
                                loTail = e;
                            }
                            else {
                                if (hiTail == null)
                                    hiHead = e;
                                else
                                    hiTail.next = e;
                                hiTail = e;
                            }
                        } while ((e = next) != null);
                        if (loTail != null) {
                            loTail.next = null;
                            newTab[j] = loHead;
                        }
                        if (hiTail != null) {
                            hiTail.next = null;
                            newTab[j + oldCap] = hiHead;
                        }
                    }
                }
            }
        }
        return newTab;
    }
```
在处理单向链表时，为什么会有两个链表，一个loHead, 一个hiHead呢?
假设一个模为4的hash表如下所不，
```
index         Node
0             0 -> 4 ->8
1              ...
2              ...
3              ...
```
那 0 & (4-1)  = 0, 放在index为0中，如果此时又放入一个4  4&(4-1) = 0, 此时4也放入0中，然后和0一起形成单向链表.
而此时扩容了，4扩成了8, 此时模就变成8了, 如下所示, 由于`0&(8-1) =0`此时0还是放在index为0中, `4&(8-1) !=0`, 此时4应该重新放置在index为4中，所以直接0+4的table中，也就是代码中的`newTab[j + oldCap] = hiHead;`  非常巧妙
```
0              0  ->8
1              ...
2              ...
3              ...
4              4
```
再扩容后的table如下所示
此时table数组大小为2，只有一个index有值，threshold=1
![再扩容](http://upload-images.jianshu.io/upload_images/5688445-60f0565c1b1f7802.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

扩容其实很简单，就是当HashMap里的数据个数大于threshold了，就要开始扩容了，
`threshold = 当前容量*LOAD_FACTOR`
默认的LOAD_FACTOR = 0.75, 所以一般threshold等于容量的3/4。
## 1.2 算 Hash 值
HashMap通过hash去获得一个Object的hash值
```
    static final int hash(Object key) {
        int h;
        return (key == null) ? 0 : (h = key.hashCode()) ^ (h >>> 16);
    }
``` 
x ^ (x>>>16), 这个没什么好说的。

## 1.3 红黑树替换单向链表
HashMap的碰撞冲突解决方案是单向链表，但是如果此单向链表足够长， 单向链表的查询的时间复杂度为O(n). 效率还是比较低，所以 HashMap 针对单向链表做了一个优化，将它转换为红黑树，红黑树的查询时间复杂度为O(logn).
继续看putVal函数
```
    final V putVal(int hash, K key, V value, boolean onlyIfAbsent,
                   boolean evict) {
        Node<K,V>[] tab; Node<K,V> p; int n, i;
        if ((tab = table) == null || (n = tab.length) == 0)
            n = (tab = resize()).length;
        if ((p = tab[i = (n - 1) & hash]) == null)
            tab[i] = newNode(hash, key, value, null);
        else {
            Node<K,V> e; K k;
            if (p.hash == hash &&
                ((k = p.key) == key || (key != null && key.equals(k))))
                e = p;
            else if (p instanceof TreeNode)  //如果已经是红黑树的结构了，那么直接将新的数据插入到红黑树即可
                e = ((TreeNode<K,V>)p).putTreeVal(this, tab, hash, key, value);
            else {  //如果还是单向链表的情况
                for (int binCount = 0; ; ++binCount) {
                    if ((e = p.next) == null) {
                        p.next = newNode(hash, key, value, null);  //插入到单向链表中
                        if (binCount >= TREEIFY_THRESHOLD - 1)
                       // 如果单向链表的长度大于TREEIFY_THRESHOLD - 1, 这里默认是8-1=7， 则将该单向链表转换为红黑树
                            treeifyBin(tab, hash);
                        break;
                    }
                    if (e.hash == hash &&
                        ((k = e.key) == key || (key != null && key.equals(k))))
                        break;
                    p = e;
                }
            }
            if (e != null) { // existing mapping for key
                V oldValue = e.value;
                if (!onlyIfAbsent || oldValue == null)
                    e.value = value;
                afterNodeAccess(e);
                return oldValue;
            }
        }
        ++modCount;
        if (++size > threshold)
            resize();
        afterNodeInsertion(evict);
        return null;
    }
```
从上面看出，当单向链表的长度达到默认的8个时，就将该单向链表转换成红黑树结构
```
    final void treeifyBin(Node<K,V>[] tab, int hash) {
        int n, index; Node<K,V> e;
        //但是转换成红黑树的条件之一是，当前table的容量要达到64个，否则就先扩容再说
        if (tab == null || (n = tab.length) < MIN_TREEIFY_CAPACITY)
            resize();
        else if ((e = tab[index = (n - 1) & hash]) != null) {
            TreeNode<K,V> hd = null, tl = null;
            do {
                TreeNode<K,V> p = replacementTreeNode(e, null);
                if (tl == null)
                    hd = p;
                else {
                    p.prev = tl;
                    tl.next = p;
                }
                tl = p;
            //do while 先将Node全部转换成TreeNode
            } while ((e = e.next) != null);
            if ((tab[index] = hd) != null)
                hd.treeify(tab);  //红黑树转换
        }
    }
```
从treeifyBin可以看出，只有当HashMap的容量 >=MIN_TREEIFY_CAPACITY 才进行红黑树转换。否则就扩容。至于为什么是 MIN_TREEIFY_CAPACITY, 可能经验值吧。
do while循环先将Node转换成红黑树需要的TreeNode, 然后再调用treeify进行红黑树转换。
treeIfy是红黑树算法，具体可以参考 [红黑树的优点](http://blog.csdn.net/yxc135/article/details/7939671)
```
        final void treeify(Node<K,V>[] tab) {
            TreeNode<K,V> root = null;
            for (TreeNode<K,V> x = this, next; x != null; x = next) {
                next = (TreeNode<K,V>)x.next;
                x.left = x.right = null;
                if (root == null) {
                    x.parent = null;
                    x.red = false;
                    root = x;
                }
                else {
                    K k = x.key;
                    int h = x.hash;
                    Class<?> kc = null;
                    for (TreeNode<K,V> p = root;;) {
                        int dir, ph;
                        K pk = p.key;
                        if ((ph = p.hash) > h)
                            dir = -1;
                        else if (ph < h)
                            dir = 1;
                        else if ((kc == null &&
                                  (kc = comparableClassFor(k)) == null) ||
                                 (dir = compareComparables(kc, k, pk)) == 0)
                            dir = tieBreakOrder(k, pk);

                        TreeNode<K,V> xp = p;
                        if ((p = (dir <= 0) ? p.left : p.right) == null) {
                            x.parent = xp;
                            if (dir <= 0)
                                xp.left = x;
                            else
                                xp.right = x;
                            root = balanceInsertion(root, x);
                            break;
                        }
                    }
                }
            }
            moveRootToFront(tab, root);
        }
```
# 二、LinkedHashMap
```
public class LinkedHashMap<K,V>
    extends HashMap<K,V>
    implements Map<K,V>
```
LinkedHashMap继承于HashMap,  因此HashMap有的特性，LinkedHashMap也都有。

```
    transient LinkedHashMap.Entry<K,V> head;
    transient LinkedHashMap.Entry<K,V> tail;
    final boolean accessOrder;
```
LinkedHashMap定义了三个成员变量，
head, tail是双向链表的头和尾
accessOrder是访问顺序，这里是指双向链表的访问顺序

由此可以看出LinkedHashMap只是在HashMap的基础上增加了一个双向链表，分别由head, tail的表示头和尾。

HashMap的遍历顺序与加入时的顺序可能不一样，而LinkedHashMap遍历顺序和加入时的顺序是一样的，且具有HashMap的特性。 所以LinkedHashMap 非常用来适合做 LRU 缓存

# 三、HashTable
HashTable是HashMap的线程安全版，实现方式就是在public函数上加上synchronized方法进行同步。

# 四、TreeMap
TreeMap从名字上就可以看出是树型的Map, 实现方式就是将Key作为红黑树的关键字进行插入，排序，平衡，并没有计算hash值，且不是线程安全的。

