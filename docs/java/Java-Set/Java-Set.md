![Set](http://upload-images.jianshu.io/upload_images/5688445-36803fb8e6944341.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

Java Set的特性是不会出现重复的数据。我们知道Map中的Key也是独一无二的，对的，你猜的没错， Set就是用Map实现的，相当于是 Map的 Wrapper

# HashSet
```
    // Dummy value to associate with an Object in the backing Map
    private static final Object PRESENT = new Object();
    public HashSet() {
        map = new HashMap<>();
    }
    public boolean add(E e) {
        return map.put(e, PRESENT)==null;
    }
    public boolean contains(Object o) {
        return map.containsKey(o);
    }
```
HashSet的public的构造函数里，都是使用的HashMap作为Base类， HashSet的add函数，将数据低为key, 加入一个Dummy的Value. 没有什么好说的

# LinkedHashSet
```
public class LinkedHashSet<E>
    extends HashSet<E>
    implements Set<E>, Cloneable, java.io.Serializable {

    public LinkedHashSet() {
        super(16, .75f, true);
    }
```
```
    HashSet(int initialCapacity, float loadFactor, boolean dummy) {
        map = new LinkedHashMap<>(initialCapacity, loadFactor);
    }
```
可以看出LinkedHashSet继承于HashSet,  LinkedHashSet的构造函数中调用了HashSet的构造函数，使用LinkedHashMap作为Base. 所以可知 LinkedHashSet是带有双向链表的HashSet.

# TreeSet
```
    TreeSet(NavigableMap<E,Object> m) {
        this.m = m;
    }
```
```
    public TreeSet() {
        this(new TreeMap<E,Object>());
    }
```
TreeSet允许自定义的NavigableMap, 否则使用默认的TreeMap, 而TreeMap的实现是红黑树。

如果前面了解过HashMap， 那么Set就非常简单了。

