> 最近看了一个Android CTS的问题，最后发现是Google CTS package里的一个case 乱用了一个 Android Non-public attrs而引起的，写下这篇文章记录一下。

转载请标明来处：http://www.jianshu.com/p/2d54c41c0dfa

# 一、环境
OS: Android 7.0
CTS version: android-cts-7.0_r1

重现步骤:
```
run cts -m CtsServicesHostTestCases -t android.server.cts.ActivityManagerPinnedStackTests#testAlwaysFocusablePipActivity
     --logcat-on-failure
```
观察到的现象:

```
Test failing with error 'junit.framework.AssertionFailedError: 
    Pinned stack must be the focused stack. expected:<4> but was:<0>'
```

# 二、testAlwaysFocusablePipActivity

在CTS code里找到 **ActivityManagerPinnedStackTests.java** 里的**testAlwaysFocusablePipActivity** 方法， 这个Case主要是执行adb shell命令，然后 dumpsys 一些信息来检查是否满足预期。 整理如下:

``` 
adb shell am start -n android.server.app/.AlwaysFocusablePipActivity
adb shell am stack move-top-activity-to-pinned-stack 1 0 0 500 500
adb shell dumpsys activity activities
```

测试步骤主要分为三步：

- 启动 AlwaysFocusablePipActivity
- 将 AlwaysFocusablePipActivity 从stack 1 移动到 PINNED stack (stack id 4).
- dump activity的activities信息， 检查 **mFocusedStack** 是否 stack id为4.

最后的期望的 stack id 为4

```
mFocusedStack=ActivityStack{7d928bf stackId=4, 2 tasks}
```

其中 **adb shell am stack move-top-activity-to-pinned-stack 1 0 0 500 500** 这句命令，目的就是将 **AlwaysFocusablePipActivity** 从stack 1 移动到 PINNED stack (stack id 4), 然后将 PINNED Stack设置为 Focused 的stack.

**代码跟踪**

```
moveTopActivityToPinnedStack
    moveTopStackActivityToPinnedStackLocked
        moveActivityToPinnedStackLocked
            moveTaskToStackLocked
                moveTaskToStackUncheckedLocked
                    moveToFrontAndResumeStateIfNeeded
                        moveToFront
                            setFocusStackUnchecked
```
接着看下 setFocusStackUnchecked
```
void setFocusStackUnchecked(String reason, ActivityStack focusCandidate) {
    if (!focusCandidate.isFocusable()) {
        // The focus candidate isn't focusable. Move focus to the top stack that is focusable.
        focusCandidate = focusCandidate.getNextFocusableStackLocked();
    }
}

boolean isFocusable() {
    if (StackId.canReceiveKeys(mStackId)) {
        return true;
    }
    // The stack isn't focusable. See if its top activity is focusable to force focus on the
    // stack.
    final ActivityRecord r = topRunningActivityLocked();
    return r != null && r.isFocusable();
}

public static boolean canReceiveKeys(int stackId) {
    return stackId != PINNED_STACK_ID;
}

boolean isFocusable() {
    return StackId.canReceiveKeys(task.stack.mStackId) || isAlwaysFocusable();
}

boolean isAlwaysFocusable() {
    return (info.flags & FLAG_ALWAYS_FOCUSABLE) != 0;
}
```

前面在移动 Activity Stack 的时候都是 ok 的，但是当要把当前 PINNED stack设置为 Focused stack 的时候却出现问题了,

在设置一个 stack 为 focused stack  的时候，AMS会去检查这个 stack 是否是 可 focusable 的！其中 PINNED STACK 是不能接收Key的，所以只有topRunningActivity 是可 Focusable 的时候，那么 PINNED stack 才能Focusable.

从 **isAlwaysFocusable()** 函数可以看出来，只有当 PINNED STACK 里的 Activity的 flag 设置了FLAG_ALWAYS_FOCUSABLE 才能focuse.

而 FLAG_ALWAYS_FOCUSABLE 又是个什么标志呢？

# 三、FLAG_ALWAYS_FOCUSABLE标志

FLAG_ALWAYS_FOCUSABLE 这个标志是在 PKMS 去解析AndroidManifest.xml里设置的。

```java
if (sa.getBoolean(R.styleable.AndroidManifestActivity_alwaysFocusable, false)) {
    a.info.flags |= FLAG_ALWAYS_FOCUSABLE;
}
```

那么问题就转换成要为 AlwaysFocusablePipActivity 设置 alwaysFocusable 属性了。找到 AlwaysFocusablePipActivity 所在的 AndroidManifest.xml 里的描述，

```xml
<activity android:name=".AlwaysFocusablePipActivity"
          android:theme="@style/Theme.Transparent"
          android:resizeableActivity="true"
          android:supportsPictureInPicture="true"
          androidprv:alwaysFocusable="true"
          android:exported="true"
          android:taskAffinity="nobody.but.AlwaysFocusablePipActivity"/>
```

很明显  **alwaysFocusable** 已经被设置为 true (这里的 androidprv只是一个命名空间，无实际用处), 但是PKMS 竟然没有把它给解析出来。 那这肯定就是 PKMS 的问题了？ 不然，请继续查看

# 四、PKMS解析 alwaysFocusable 属性

明明已经设置了 alwaysFocusable 属性了，为什么 PKMS 解析不出来呢？？？？ 这么奇怪的问题。 
从上一节中PKMS 获得该属性的方法得知，无非就是从  TypedArray 里去获得属性值，这还能有错？莫非是AssetManager没有把该值解析出来，赶紧 debug AssetManager.

```java
TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestActivity);
```

> R.styleable.AndroidManifestActivity 定义在 framework-res里的attrs_manifest.xml中

```
<declare-styleable name="AndroidManifestActivity" parent="AndroidManifestApplication">
    <!-- Required name of the class implementing the activity, deriving from
        {@link android.app.Activity}.  This is a fully
        qualified class name (for example, com.mycompany.myapp.MyActivity); as a
        short-hand if the first character of the class
        is a period then it is appended to your package name. -->
    <attr name="name" />
    <attr name="theme" />
    <attr name="label" />
    <attr name="description" />
    <attr name="icon" />
    <attr name="banner" />
    ...
    <attr name="alwaysFocusable" format="boolean" />
    <attr name="enableVrMode" />
</declare-styleable>
```

R.styleable.AndroidManifestActivity 是一组int型数组， 数组元素是一个一个属性Id 值，而该值是通过 aapt编译出来的ID, 具体可以从 public_resources.xml里查看，该xml文件里面包含了所有的整个系统的resource id.

```
out/debug/target/common/obj/APPS/framework-res_intermediates/public_resources.xml
```

- 本地编译的属性值

```xml
<public type="attr" name="theme" id="0x01010000" />
<public type="attr" name="label" id="0x01010001" />
<public type="attr" name="icon" id="0x01010002" />
<public type="attr" name="name" id="0x01010003" />

<!-- Declared at frameworks/base/core/res/res/values/attrs_manifest.xml:1882 -->
<public type="^attr-private" name="systemUserOnly" id="0x011600c4" />
<!-- Declared at frameworks/base/core/res/res/values/attrs_manifest.xml:1899 -->
<public type="^attr-private" name="alwaysFocusable" id="0x011600c5" />
```

obtainAttributes 最终会调用 frameworks/base/core/jni/android_util_AssetManager.cpp
的 android_content_AssetManager_retrieveAttributes 函数， 而该函数获得对应属性值就是通过该属性的 id 值去编译出来的 xml文件获得。

而且很奇怪的一点，用本地编出来的CTS package去测试，结果就PASS了，而AOSP的CTS package 确是失败的。 这时通过加log调试, 发现 AOSP 编出来的CTS 里的 alwaysFocusable 的属性值与本地编出来的属性是不一样的。

- AOSP 属性值

```xml
<!-- Declared at frameworks/base/core/res/res/values/attrs_manifest.xml:1899 -->
<public type="^attr-private" name="alwaysFocusable" id="0x011600c3" />
```

从这里基本就可以知道为什么 alwaysFocusable 不能被解析出来了。

# 五、结论

为什么本地与AOSP编译出来的 **alwaysFocusable** 的 ID 属性值是不一样的呢？

从 type="^attr-private 与 type="attr" 大概可以猜出来,alwaysFocusable 应该是 **私有的** 属性，非public的属性。

定义成 public 的属性不管是在什么平台下编出来，其 ID 值应该是一样的。而非public 的属性就不一定了，因为vendor可能自己会定义一些属性，这样 **aapt** 在解析这些属性, 并给他们赋值时, 可能就会不一样。

而public的属性，作为 AOSP 提供给上层 app 开发者，它们的值必须一样，否则就会导致app 在不同的厂商不兼容的情况。

public的属性值可以从 **frameworks/base/core/res/res/values/public.xml** 里查看

因此，从这里可以看出来这个是 Google 的问题， 因为Google 用了非public 的属性值编译出来的CTS package 来测试不同的 vendor, 这样做是不正确的。
