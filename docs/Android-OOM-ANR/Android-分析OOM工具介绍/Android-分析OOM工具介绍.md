> OOM问题从来都是一个让开发者闻风丧胆的大问题，它以 "难以定位，难以分析，并且它所导致的问题都很严重" 而闻名。
> 可不管多严重，总要解决这类OOM问题。工欲善其事必先利其器，这篇文章就简单的介绍下Android分析OOM问题的工具。

转载请标明来处: http://www.jianshu.com/p/f41db1f57523
# 1. Android OOM工具介绍

## 1.1 Android monitor

![图1 Android Monitor图](http://upload-images.jianshu.io/upload_images/5688445-55ed9d312b3a281e.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

如图1所示, 步骤 
** 1, 2, 3** 为打开Android Monitor并切换标签到monitor的过程

**4, 5, 6** 对应的图标和文字含义分别是

- 4: 触发GC
- 5: dump Java heap
- 6: 可以看到当前已经 allocated和free的 memory.

## 1.2 MAT (Memory Analyzer Tool)

[MAT](http://www.eclipse.org/mat/) 工具识别，并解析hprof文件,
有两种方式可以获得hprof文件

- a) 将Android studio导出来hprof. **点击图1中步聚5**
- b)命令行

```
adb shell am dumpheap <PID> /data/local/tmp/out.hprof
```

MAT并不能直接打开这两个hprof, 必须通过hprof-conv来转换一次

```
hprof-conv src.prof dest.prof
```

![图2 MAT overview](http://upload-images.jianshu.io/upload_images/5688445-03b5c401f82c4eb8.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![图3 过滤要分析的class name](http://upload-images.jianshu.io/upload_images/5688445-9afb1eae74e7b99a.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

如图3所示，选中(过滤出MainActivity), 然后通过Objects可以看出它有8个实例

接着选中 `com.example.wowo.MainActivity` 然后右键选择
`Merge shortest paths to GC Roots -> exclude week references`

因为弱引用是会被回收的，所以排除掉更加容易发现OOM.

![图4 排除弱引用后的的MAT图](http://upload-images.jianshu.io/upload_images/5688445-c20e555019caa29b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

### 2. Android studio 初步分析OOM

什么是OOM out-of-memory？

Android下的APP运行在VM中(Dalvik or ART), 一个APP需要的内存是有限，这个值在不同的平台, 不同的手机上是不同的，当APP需要的内存超过了内存上限，就会引起OOM.

下面给出一个最基本的Android APP显示HelloWorld的例子.

```Java
public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }
}
```

这时如果不停的旋转屏幕, 这时通过观察Android Monitor里的Free和allocated的memory会发现 allocated 的memory会不断增加，而Free的memory会不断减小

这时通过图1中步聚5 dump java heap, 然后filter到MainActivity, 会发现MainActivity有多个实例

![图5 Not GC OOM图](http://upload-images.jianshu.io/upload_images/5688445-9ce8a623cfe2018c.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

接着再通过MAT来分析， 图4所示

发现有很多FinalizerReference， 应该是与GC有关，由于旋转屏幕会导致MainActivity销毁并重新创建实例，而JVM在创建MainActivity实例时还有free的memory, 所以并没有触发GC，即原来的MainActivity的实例并没有被回收，所以多次旋转后，在free memory还有的情况下就会保存多个MainActivity的实例造成内存泄露的假象。当free memory 不够时，这时会触发GC, 会回收之前销毁的MainActivity的实例。

所以在查看OOM问题时，当allocated内存不断增大时，应该人为先触发GC(点击图1的4)。
如果allocated的内存没有下降，说明当前并没有可回收的实例占据内存了。

而在该例中，如果点击了initiate GC后，allocated的内存立即减少了。
Android Monitor看到MainActivity也就只有一个实例了。
