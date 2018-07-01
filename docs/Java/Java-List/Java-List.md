![List Overview](http://upload-images.jianshu.io/upload_images/5688445-a90d5157f5330c88.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
Java 的List接口本身继承于Collection类，而Collection类又继承于Iterable，也就是说List是一种集合的接口,可以使用Collections这个工具类去操作List，并且List可以Iterable.

实现List接口或间接实现List的类有很多，比较常用的如ArrayList, LinkedList, Vector, Stack等，下面分别来分析这些类。

# 一、ArrayList
ArrayList实现了**RandomAccess**, 意思是ArrayList支持随机访问，进一步来看ArrayList的成员变量

```
transient Object[] elementData;
private int size;
```
*elementData*是一个Object的数组，它主要用于存储对象实例，由于数组可以通过下标如 *elementData[2]* 来快速访问，这也就是能随机访问的根本原因了。
由于 elementData 是默认的protected, 所以在其它java文件里是不能直接访问elementData, 而是通过 get(int index)来访问
```
    public E get(int index) {
        rangeCheck(index);
        return elementData(index);
    }
    E elementData(int index) {
        return (E) elementData[index];  //通过数组下标
    }
```
**RandomAccess**并没有提供任何的接口方法，是一个空的接口，它其实是一个标记，,表明通过下标访问该Collection是常数时间，是最快的访问方式。 其实可以直接使用注解来表示，历史原因吧!参考[arraylist实现了randomAccess](https://www.zhihu.com/question/50909512?sort=created)

## 1.1 ArrayList的一些函数
**构造函数**
```
    public ArrayList()  //默认生成一个空的数给给elementData, 里面没有数据
    public ArrayList(int initialCapacity) //创建initialCapacity大小的数组
    public ArrayList(Collection<? extends E> c)  //将Collection里的数据 copy 到elementData中
```
**ArrayList大小动态增长**
```
    public boolean add(E e) {
        ensureCapacityInternal(size + 1);  // Increments modCount!!
        elementData[size++] = e;
        return true;
    }
    private void ensureCapacityInternal(int minCapacity) {
        ensureExplicitCapacity(calculateCapacity(elementData, minCapacity));
    }
    private static int calculateCapacity(Object[] elementData, int minCapacity) {
       //如果当前是空的ArrayList, 这里至少要创建DEFAULT_CAPACITY, 也就是数组大小为10
        if (elementData == DEFAULTCAPACITY_EMPTY_ELEMENTDATA) {
            return Math.max(DEFAULT_CAPACITY, minCapacity);
        }
        return minCapacity;
   }
    private void ensureExplicitCapacity(int minCapacity) {
        modCount++;

        // overflow-conscious code
        if (minCapacity - elementData.length > 0)
            grow(minCapacity);
    }
    private void grow(int minCapacity) {
        // overflow-conscious code
        int oldCapacity = elementData.length;
        int newCapacity = oldCapacity + (oldCapacity >> 1);  // 最后等于 x+ x/2
        if (newCapacity - minCapacity < 0)
            newCapacity = minCapacity;
        if (newCapacity - MAX_ARRAY_SIZE > 0)
            newCapacity = hugeCapacity(minCapacity);
        // minCapacity is usually close to size, so this is a win:
        elementData = Arrays.copyOf(elementData, newCapacity);
    }
```
增长的算法就是，如是要是空的，那默认先创建10个，如果10个用完了，这里再按照 
`max(x+x/2, minCapacity)` 去创建
**indexOf**
```
   public int indexOf(Object o) {
        if (o == null) {
            for (int i = 0; i < size; i++)
                if (elementData[i]==null)
                    return i;
        } else {
            for (int i = 0; i < size; i++)
                if (o.equals(elementData[i]))
                    return i;
        }
        return -1;
    }
```
可以看出来 indexOf 的时间复杂度是 O(n)

## 1.2 ArrayList的遍历
- for 
```
int size = list.size();
for (int i = 0; i < size; i++) {
    list.get(i);
}
```
- for each
```
for (Integer i : list) {
}
```
- iterator
```
for (Iterator<Integer> iterator = list.iterator(); iterator.hasNext();) {
    iterator.next();
}
```
看下ArrayList的Iterator的遍历
```
        public E next() {
            checkForComodification();
            int i = cursor;
            if (i >= size)
                throw new NoSuchElementException();
            Object[] elementData = ArrayList.this.elementData;
            if (i >= elementData.length)
                throw new ConcurrentModificationException();
            cursor = i + 1;
            return (E) elementData[lastRet = i];
        }
```
最终都是通过下标去访问数组数据，相比于直接 for 循环，多了几条判断指令，可想而知，当list size比较小时，它们两者几乎没有什么区别，但是当size比较大时，他们之间耗时还是有很大区别的。可以参考
- http://www.trinea.cn/android/arraylist-linkedlist-loop-performance/
- https://www.cnblogs.com/aoguren/p/4771589.html

for each是java在1.5引入的语法， 那它到底是怎么样实现的呢？我们通过反编译可以看出一点端倪。
```
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
public class Test {
    public static void main(String[] args) {
        List<Integer> list = new ArrayList<>();

        for (Iterator<Integer> itr = list.iterator(); itr.hasNext(); ) { 
            itr.next(); 
      }   

        for (Integer i : list) {
        }   
    }   
}
```
通过反汇编出来的代码指令如下
![for each](http://upload-images.jianshu.io/upload_images/5688445-9ee3aa007896e987.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
可以看出来for each和 Iterator的实现是一样的，也就是for each其实是iterator的简写方式。

# 二、LinkedList
LinkedList是一个链表式的List, 它的实现原理就是双向链表. LinkedList定义了两个域
```
    transient Node<E> first;
    transient Node<E> last;
```
分别表示头尾节点, Node是LinkedList里的一个静态内部类, prev指向前一个Node,  next指向后一个Node
```
    private static class Node<E> {t
        E item;
        Node<E> next;
        Node<E> prev;

        Node(Node<E> prev, E element, Node<E> next) {
            this.item = element;
            this.next = next;
            this.prev = prev;
        }
    }
```
**add函数**
```
    public boolean add(E e) {
        linkLast(e);
        return true;
    }
    void linkLast(E e) {
        final Node<E> l = last;
        final Node<E> newNode = new Node<>(l, e, null);
        last = newNode;
        if (l == null)
            first = newNode;
        else
            l.next = newNode;
        size++;
        modCount++;
    }
```
add就是很简单的向链表尾增加一个Node.

**remove**函数
```
    public boolean remove(Object o) {
        if (o == null) {
            for (Node<E> x = first; x != null; x = x.next) {
                if (x.item == null) {
                    unlink(x);
                    return true;
                }
            }
        } else {
            for (Node<E> x = first; x != null; x = x.next) {
                if (o.equals(x.item)) {
                    unlink(x);
                    return true;
                }
            }
        }
        return false;
    }
```
remove函数从first遍历，找到当中那个需要被删除的对象，然后调用unlink函数，删除该节点。

# 三、Vector和Stack
Vector也实现了 RandomAccess， 说明它底部实现应该也是一维数组
```
    protected Object[] elementData;
```
其实Vector和ArrayList差不多，只不过Vector大多数对外的接口都加了synchronized来作同步处理，因此它可以用于跨线程，也就是说它是线程安全的。

Stack继承于Vector, 说明Stack也是线程安全的，而且也是支持RandomAccess的，Stack只不过是用一维数组来实现了栈的操作。栈的基本操作是**先进后出，后进先出**.

# 四、小结
ArrayList的底部实现原理是一维数组，而LinkedList的底部实现是链表，所以ArrayList与LinkedList的根本区别在于一维数组和链表的区别。

一维数组本身是固定长度的，但是ArrayList是可扩容的，代价是生成一个更的空间，然后把当前的一维数组全部拷贝到新的大的空间中。
ArrayList支持随机访问，也就是下标访问，速度很快, 但是当删除数据或增加数据时，开销会比较大。

LinkedList中的链表可以很快速的增加和删除，不会涉及到新的空间的生成，以及拷贝动作。
链表本身是不支持随机访问的，也就是下标访问，但是LinkedList也实现了随机访问 get(int index)函数。
```
    public E get(int index) {
        checkElementIndex(index);
        return node(index).item;
    }
    Node<E> node(int index) {
        // assert isElementIndex(index);
        if (index < (size >> 1)) {
            Node<E> x = first;
            for (int i = 0; i < index; i++)
                x = x.next;
            return x;
        } else {
            Node<E> x = last;
            for (int i = size - 1; i > index; i--)
                x = x.prev;
            return x;
        }
    }
```
可以看出，get每次都会从头开始遍历到index, 每次遍历，都会移动指针，最后再取出对应的数据， 时间复杂度达 O(n)

至于ArrayList和Vector, ArrayList没有synchronized修饰，非线程安全的，而Vector是线程安全的。

Stack的继承于Vector, 底部实现是一维数组。且Stack也是线程安全的。

参考
https://www.cnblogs.com/janneystory/p/5758958.html 
