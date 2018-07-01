> Sepolicy在调试阶段真心很烦，有时因为一个sepolicy就导致结果与预期不一样。

# 1. Sepolicy规则

**Sepolicy 规则**

```
allow sourcecontext targetcontext:class {}
```

- **sourcecontext**: scontext
- **targetcontext**: tcontext
- **class**: tclass

**Sepolicy 文件存放的位置**

- **AOSP 7.1** `system/sepolicy/`
- OEM产商一般会有overlay, 如Nvidia

    `device/nvidia/common/sepolicy_n/`


# 2. 临时disable

```
adb shell setenforce 0
# 设置SELinux 成为permissive模式(SELinux开启，但对违反selinux规则的行为只记录，不会阻止)
```

```
adb shell setenforce 1
#设置SELinux 成为enforcing模式 (SELinux开启)
```


可以通过 getenforce 来获得当前系统 selinux 的状态

如果是

- **Enforcing**   enabled
- **Permissive**  disabled

缺点: adb reboot后系统恢复成原来的状态

# 3. 在代码中永久关闭 selinux

代码文件 `system/core/init/init.cpp`

找到函数 **selinux_initialize**

```
static void selinux_initialize(bool in_kernel_domain) {                                                                                                               
    bool is_enforcing = selinux_is_enforcing();
}

```

将 `bool is_enforcing = selinux_is_enforcing();` 改为

`bool is_enforcing = false; //selinux_is_enforcing();`  即可，

这样即使系统重启也会被永久disabled掉了

注意: 编译出来的 init可执行文件是在设备的根目录下，而不是/system/bin/下

且init 会被打包进 boot.img， 而非 system.img.

**或者**
`/system/core/rootdir/init.rc ` 在init阶段加上`setenforce 0`
```
on init
    # disable Security Enhanced Linux
    setenforce 0
```


# 4. Selinux规则

## 4.1 binder_call

```
 05-16 12:41:23.390 W/BootAnimation(  114): type=1400 audit(0.0:6): avc: denied { call } for
 scontext=u:r:bootanim:s0 tcontext=u:r:mediaserver:s0 tclass=binder permissive=0
```
需要在`bootanim.te`里加入

`binder_call(bootanim, mediaserver)`

## 4.2 allow

```
avc:  denied  { find } for service=media.audio_flinger pid=665 uid=1003
scontext=u:r:bootanim:s0 tcontext=u:object_r:audioserver_service:s0 tclass=service_manager permissive=0
```
需要在`bootanim.te`中加入

`allow bootanim audioserver_service:service_manager find`

# 5. 验证修改成功

通过编译后可以在 `out/debug/target/product/xxx/root`目录下找到file_contexts.bin

最后通过工具 sefcontext将file_contexts.bin 转换成 file_context

```
sefcontext file_contexts.bin -o file_contexts
```

也可通过 sefcontext_compile来打包

```
sefcontext_compile file_contexts
```

sefcontext可执行文件请查阅 [file_contexts.bin和file_contexts转换工具](https://blog.cofface.com/archives/2255.html)

具体百度网盘：链接：http://pan.baidu.com/s/1pLgyRur 密码：khat

# 6. 参考

- [快速解决Android中的selinux权限问题](http://blog.csdn.net/mike8825/article/details/49428417)
- [漫谈android系统（3） SELinux报错修改篇](http://blog.csdn.net/u013983194/article/details/50462694)
- [file_contexts.bin和file_contexts转换工具](https://blog.cofface.com/archives/2255.html)
