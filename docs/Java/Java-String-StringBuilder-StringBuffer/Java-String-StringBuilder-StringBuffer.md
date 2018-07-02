# 一、String 类
String类是一个final类，不能有子类，
```
/** The value is used for character storage. */
private final char value[];

/** Cache the hash code for the string */
private int hash; // Default to 0
```
String类有两个私有域，一个是final的char数组，一个是hash值

- value
value被声明为final类型，意味着在String的构造函数中就要对value进行赋值，一旦value被赋值后，它将不能被修改，这也就是文档上说的  immutable 的意思。 类中任何需要修改到 value值的，都是生成的一个新的 String.

```
public final class String
    implements java.io.Serializable, Comparable<String>, CharSequence {
```
![String类](http://upload-images.jianshu.io/upload_images/5688445-b6ccd60392ab1b14.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

String类实现了三个接口，分别是
- Comparable
```
    //比较两个字符串的大小
    public int compareTo(String anotherString) {
        int len1 = value.length;
        int len2 = anotherString.value.length;
        int lim = Math.min(len1, len2);
        char v1[] = value;
        char v2[] = anotherString.value;

        int k = 0;
        while (k < lim) {
            char c1 = v1[k];
            char c2 = v2[k];
            if (c1 != c2) {
                return c1 - c2;
            }
            k++;
        }
        return len1 - len2;
    }
```
compareTo的意思就是比较value相同位置的大小， 可以直接使用

```
String[] animals = {"monkey", "horse", "dog", "bird"};
Arrays.sort(animals)  // sort will invode compareTo
```
- Serializable
序列化接口
- CharSequence 
字符接口

**valueOf**  获得对应参数的 String 表达式
```
    public static String valueOf(int i) {
        return Integer.toString(i);
    }
    public static String toString(int i) {
        if (i == Integer.MIN_VALUE)
            return "-2147483648";
        int size = (i < 0) ? stringSize(-i) + 1 : stringSize(i);
        char[] buf = new char[size];
        getChars(i, size, buf);
        return new String(buf, true);
    }
```
**replace**
```
    public String replace(char oldChar, char newChar) {
        if (oldChar != newChar) {
            int len = value.length;
            int i = -1;
            char[] val = value; /* avoid getfield opcode */

            while (++i < len) {
                if (val[i] == oldChar) {
                    break;
                }
            }
            if (i < len) {
                char buf[] = new char[len];
                for (int j = 0; j < i; j++) {
                    buf[j] = val[j];
                }
                while (i < len) {
                    char c = val[i];
                    buf[i] = (c == oldChar) ? newChar : c;
                    i++;
                }
                return new String(buf, true);  //最后都是创建一个新的String
            }
        }
        return this;
    }
```

**hashCode**
```
    public int hashCode() {
        int h = hash;
        if (h == 0 && value.length > 0) {
            char val[] = value;

            for (int i = 0; i < value.length; i++) {
                h = 31 * h + val[i];
            }
            hash = h;
        }
        return h;
    }
```
算法 `s[0]*31^(n-1) + s[1]*31^(n-2) + ... + s[n-1]`
也就是说最多有2^32= 4294967296个整数，将任意一个字符串，经过hashCode计算之后，得到的整数应该在这4294967296数之中。那么，有大于 4294967297个不同的字符串作hashCode之后，肯定有两个结果是一样的。
> hashCode可以保证相同的字符串的hash值肯定相同，但是hash值相同并不一定是value值就相同。

**equals**
```
    public boolean equals(Object anObject) {
        if (this == anObject) {
            return true;
        }
        if (anObject instanceof String) {
            String anotherString = (String)anObject;
            int n = value.length;
            if (n == anotherString.value.length) {
                char v1[] = value;
                char v2[] = anotherString.value;
                int i = 0;
                while (n-- != 0) {
                    if (v1[i] != v2[i])
                        return false;
                    i++;
                }
                return true;
            }
        }
        return false;
    }
```
equals的比较挺简单的，如果他们是同样的地址，他们是相同的，如果不同的地址，但是他们的字符串值是相同的，则它们相同, http://blog.csdn.net/dmk877/article/details/49420141 
```
		String aaa = "abc";
		String eee = "abc";
		String bbb = new String("abc");
		char[] ccc = {'a', 'b', 'c'};
		String ddd = new String(ccc);
```
aaa和eee指向同一个堆地址, 他们是相同的
aaa,bbb, 和ddd指向不同的堆地址，但是他们的value的值是相同的，所以equals返回true

Java 1.8新特性
**join**
```
支持不定参数的join
public static String join(CharSequence delimiter, CharSequence... elements) {
String.join("-", "hello", "world")   //输出  hello-world

也支持Iterable类型的集合
public static String join(CharSequence delimiter,
            Iterable<? extends CharSequence> elements)

List<String> strings = new LinkedList<>();
strings.add("Java");strings.add("is");
strings.add("cool");
String message = String.join(" ", strings);
 //message returned is: "Java is cool"
```
join的实现是通过 StringJoiner来实现的，而StringJoiner的实现最终是通过StringBuilder来实现的

## String的操作符 "+" 重载
```
public class Test {
    public static void main(String[] args) {
        String str = "hello";
        String dest = str + " world";
    }   
}
```
通过 `javap -c Test.class` 得到的指令如下
![image.png](http://upload-images.jianshu.io/upload_images/5688445-fcec8e62e9cf476f.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

可以看到 `str + " world"` 是通过StringBuilder来做的, 等同于如下
```
new StringBuilder().append("hello").append(" world").toString();
```

# 二、StringBuilder类
![StringBuilder类](http://upload-images.jianshu.io/upload_images/5688445-18abce975db95afb.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
    public StringBuilder() {
        super(16);
    }
    AbstractStringBuilder(int capacity) {
        value = new char[capacity];
    }
```
StringBuilder 在初始化时，会默认生成一个char数组，大小为16

**append**
```
    public StringBuilder append(String str) {
        super.append(str);
        return this;
    }
    public AbstractStringBuilder append(String str) {
        if (str == null)
            return appendNull();
        int len = str.length();
        ensureCapacityInternal(count + len);
        str.getChars(0, len, value, count);
        count += len;
        return this;
    }
    private void ensureCapacityInternal(int minimumCapacity) {
        // overflow-conscious code
        if (minimumCapacity - value.length > 0) {
            value = Arrays.copyOf(value,
                    newCapacity(minimumCapacity));  //将value copy到一个新生成的char数组中
        }
    }
    private int newCapacity(int minCapacity) {
        // overflow-conscious code
        int newCapacity = (value.length << 1) + 2;  //当前 value.lengh 扩大2倍+2
        if (newCapacity - minCapacity < 0) {
            newCapacity = minCapacity;  // 取最大值
        }
        return (newCapacity <= 0 || MAX_ARRAY_SIZE - newCapacity < 0)
            ? hugeCapacity(minCapacity)
            : newCapacity;
    }
```
ensureCapacityInternal函数是确保当前的value能够保存下你需要 append 的数据

# StringBuffer类
![StringBuffer类](http://upload-images.jianshu.io/upload_images/5688445-7dc97c5368165671.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
StringBuffer与StringBuilder类似，不过他比StringBuilder多有一个toStringCache域，它定义为transient, 不会被序列化,  它只用在 toString 函数
```
    private transient char[] toStringCache;
```
```
    public StringBuffer() {
        super(16);
    }
```

StringBuffer的构造函数与StringBuilder的一样，只不过它的一些public方法却不一样
```
public synchronized StringBuffer append(String str)
public synchronized void setLength(int newLength)
public synchronized char charAt(int index)
public synchronized String substring
```
都用synchronized来修饰，可以用在多线程，相比StringBuilder, StringBuffer是线程安全的，线程安全原理就是对方法进行 synchronized

# 参考
[Java 深究字符串String类(1)之运算符"+"重载](http://blog.csdn.net/Dextrad_ihacker/article/details/53055709)
[# [深入理解Java常用类----String](http://www.cnblogs.com/yangming1996/p/6850441.html)
](http://www.cnblogs.com/yangming1996/p/6850441.html)


