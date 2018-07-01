# 1. JVM architecture
这篇文章来源于Youtube Video [JVM ( java virtual machine) architecture - tutorial](https://www.youtube.com/watch?v=ZBJ0u9MaKtM)
Java程序是怎么样运行的？
1. Edit MyApp.java
2. javac MyApp.java   #通过javac编译命令，将MyApp.java编译成MyApp.class
3. java MyApp   #创建JVM实例，并且加载和执行MyApp

![JVM Architecture](http://upload-images.jianshu.io/upload_images/5688445-2ae9baf7549f1a48.JPG?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
ClassLoader负责Load MyApp.class, 同时也会Load JVM built-in的 xxxx.class 文件，比如 `java.lang.String`. 加载好的class文件都是bytecode指令, 而这些bytecode指令在Execution Engine被解释执行, 最后由Execution Engine调用host os的native call翻译成机器指令并执行。

![JVM with Runtime data areas](http://upload-images.jianshu.io/upload_images/5688445-33b5ba2fb10b77d9.JPG?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 2. ClassLoader subsystem
![Class Loading subsystem](http://upload-images.jianshu.io/upload_images/5688445-e875228d6b7ad5f2.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
## 2.1 Load phase
Load Phase将.class文件中的bytecode load进memory

- 1). Bootstrap class loader
主要load java internal classes, 这些class位于 `jdk\jre\lib\rt.jar`里
- 2). Extension class loader
主要是load ext目录下的classes, jre\lib\ext
- 3). Application class loaders
主要根据CLASSPATH设定的位置去load classes.

## 2.2 Link phase
- 1). Verify   - class verification
检查class loader load进来的bytecode, 确保是否与JVM兼容， 如Magic number检查等

- 2). Prepare
**为static变量分配内存，但是只赋给默认的值**, 如
public static boolean isCorrect = true;
此阶段只为isCorrect分配内存，然后初始化为默认值  false

- 3). Resolve
主要是解决引用关系，如引用到constant pool里的变量， 此时就会将引用resolve成真实的值

## 2.3 Initialize
此时类里面的static变量就会被赋给真实的值， 如2.2中的Prepare阶段里的isCorrect将会被赋给**true**
类中的static block也会在该阶段执行。

## 2.4 Class Loader 的 Exception
Class Def Not Found
此异常发生在Resolve阶段，如X引用到Y，在Resolve阶段，如果Y不能被找到，此时就会throw  Class Not Found 异常

# 3. Runtime Data Areas
![Runtime Data Areas](http://upload-images.jianshu.io/upload_images/5688445-c748f314bd2ff462.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![Runtime Data Areas Cont](http://upload-images.jianshu.io/upload_images/5688445-1f1ef4c25084a8c4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 3.1 Method Area
class的metadata/bytecode存放的地方
API, reflection API存放的地方？
静态变量存放的地方
class级别的constant pool

注意: 该区域的大小可以通过 -XX:MaxPermSize 来调节

Java8开始去掉了Method Area, 叫做 metaspace. 主要是将 Method Area或PermGen Space移到了一个单独的native OS 的内存中，这块内存称为meta space, 默认情况下该块区域内存大小没有限制

## 3.2 Heap
各种对象实例存放的地方

## 3.3 PC Registers
Program Counter 程序计数寄存器, 指向下一条指令，PC寄存器是线程相关
## 3.4 Java Stacks
线程相关的，包含的时当前线程的执行的Method
注意：此阶段容易发生 StackOverflowError
## 3.5 Native Method Stacks
如果一个java方法调用native的方法，此时native的方法将会进入native的method stack中

**注意：**
3.1、3.2 是所有线程共享的，是JVM里的
3.3、3.4、3.5是线程相关的，并且是线程安全的，互相不可见

# 4. Execution Engine
![Execution Engine](http://upload-images.jianshu.io/upload_images/5688445-f9c642414c79814e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

## 4.1 Interpreter
Interpreter解释执行bytecode, 它会找到哪些需要执行native operation, 并且执行native 的操作(通过jni, jni具体通过library去执行)
## 4.2 JIT Compiler
Just in time Compiler，比如有一组指令需要重复执行，这些指向并不会被重复的interpret，相反JIT会编译这些指令并且保持目录机器指令准备被执行？
## 4.3 Hotspot Profiler
主要是获得一些统计数据，然后负责优化
## 4.4 GC


