> Android的bootanimation用于控制显示开机的启动动画，但是由于Android源码里只支持一帧一帧的显示图片，从而达到动画的效果, bootanimation本身并不支持播放mp4. 那对于一些对于流畅度要求很高，或者想要做一些非常酷炫的动画效果, bootanimation怕是不行了。

> 那如果把动画效果做成mp4视频，然后在bootanimation里播放，那会不会是一种不错的解决方案呢？
本篇文章主要是对于bootanimation播放mp4的可行性进行分析。

> PS: 笔者已经在7.0上试验成功了

转载请标注出处:  http://www.jianshu.com/p/d3e8f1378846

# 1. Android的启动过程
安卓的启动过程大致可以分为三个阶段

- bootloader
- kernel
- init

bootloader启动时会显示一些文字
kernel启动时会显示一张静态图片
init启动时会在bootanimation里显示动画

老罗的[[Android系统的开机画面显示过程分析](http://blog.csdn.net/luoshengyang/article/details/7691321)](http://blog.csdn.net/luoshengyang/article/details/7691321/)对这三个阶段讲解得非常清楚，大家可以去拜读一下。

# 2. bootanimation是什么

bootanimation是一个可执行进程，位于 /system/bin下
bootanimation的代码位置: `frameworks/base/cmds/bootanimation`

在Android 7.0, bootanimation.rc并不是直接放到了init.rc里面，它放在/etc/init/下，作为一个单独的rc配置文件存在。

那么bootanimation.rc什么时候被解析呢
在 init 进程里, 准确说是在`builtins.cpp`文件里被解析的
```
static void import_late(const std::vector<std::string>& args, size_t start_index, size_t end_index) {
    Parser& parser = Parser::GetInstance();
    if (end_index <= start_index) {
        // Use the default set if no path is given
        //init_directories是保存xxx.rc的地方，
        static const std::vector<std::string> init_directories = {
            "/system/etc/init",
            "/vendor/etc/init",
            "/odm/etc/init"
        };

        for (const auto& dir : init_directories) {
            parser.ParseConfig(dir);
        }
    } else {
        for (size_t i = start_index; i < end_index; ++i) {
            parser.ParseConfig(args[i]);
        }
    }
}
```

# 3. bootanimation启动

bootanimation.rc文件定义如下
```
service bootanim /system/bin/bootanimation
      class core
      user graphics
      group graphics audio
      disabled
      oneshot
```
从定义可以看出, bootanimation被定义成了init进程的一个service.，它属于 core类，只能启动运行一次(oneshot),  是disabled的, 意思是 class_start core时，并不会启动它。

那bootanimation是在什么时候被启动的呢？
在 SurfaceFlinger初始化的最后
```
void SurfaceFlinger::init() {
    // start boot animation
    startBootAnim();
}

void SurfaceFlinger::startBootAnim() {
    // start boot animation
    property_set("service.bootanim.exit", "0");
    property_set("ctl.start", "bootanim");
}
```
`startBootAnim`设置了`ctl.start=bootanim`这个property, 从而会触发init中正在监听property的`handle_property_set_fd`函数

简化后如下
```
static void handle_property_set_fd()
{
    switch(msg.cmd) {
    case PROP_MSG_SETPROP:     
        if (memcmp(msg.name,"ctl.",4) == 0) {
            if (check_control_mac_perms(msg.value, source_ctx, &cr)) {
                handle_control_message((char*) msg.name + 4, (char*) msg.value);
            }
        } 
}
```
```
void handle_control_message(const std::string& msg, const std::string& name) {
    Service* svc = ServiceManager::GetInstance().FindServiceByName(name);
    //找到 bootanim 的service (由bootanim.rc定义), 然后start
    if (msg == "start") {
        svc->Start();
    } else if (msg == "stop") {
        svc->Stop();
    } else if (msg == "restart") {
        svc->Restart();
    }
}
```
这里先通过name也就是**bootanim**去查找service, 然后start这个service, 这里bootanimation就开始运行起来了。

bootanimation的具体代码的执行可以参考老罗的分析，在这里就不重复造轮子了。

# 4. bootanimation播放mp4分析
既然 bootanimation 要播放 mp4 文件，那么肯定得相关服务已经启动了才行，如 MediaPlayer相关. 通过对init的log分析 (adb shell dmesg)，可以发现 `audioserver` `media`相关的服务已经于bootanim先启动起来，这样就可以得到 Mediaplayer在bootanim里能正常的播放多媒体文件。
