> 2018/07/31 AOSP android-8.1.0_r31

Android尽可能多的缓存进程，当用户下次再去使用该进程时，就会直接使用缓存的进程，从而避免了开启进程这样的消耗，提高响应速度。

但是随着Android缓存的进程越来越多，系统内存就会越来越少。所以Android又会去杀掉一些缓存的进程来释放一些内存, 而这就是low memory killer.

可以看出，Android缓存的进程数量，与触发Low memory killer是相关的，
当缓存/empty的进程数量，就会触发android framework层面的低杀
或使用的内存超过一个阈值时，就会触发lmkd或kernel里的low memory killer.

本文主要分析怎样计算一个进程的oom adj, 然后android framework对缓存的进程进行低杀，接着会去lmkd以及kernel里继续分析low memory killer.

# 一 计算 oom adj
Android Framework计算一个进程的 oom adj 是在computeOomAdjLocked函数中进行的，由于该函数很庞大，所以就不贴代码了。大体把流程图画出来了，

![computeOomAdjLocked](https://upload-images.jianshu.io/upload_images/5688445-c0b48f12d3c76352.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![service_promotion](https://upload-images.jianshu.io/upload_images/5688445-9f3712de483ee686.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![provider_promotion](https://upload-images.jianshu.io/upload_images/5688445-312942e54e065f65.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![system_adj](https://upload-images.jianshu.io/upload_images/5688445-15d0055a39d8cdd7.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

上图是系统定义的adj level, 其中

- **system和persistent进程**
这类进程的 adj < 0, computeOomAdjLocked并不会调整这类进程的adj, 也就意味着，这些进程很重要，不到万不得已的情况下是不会kill这些进程的

- **前端进程**
这类进程包括TOP APP, 也包括那些正在接收广播，正在进行service callback的进程，以及在run instrumentation的进程.

- **Visible 进程**
visible进程并不一定是前端进程, 它是指app中有些activity是可见的，比如被TOP app覆盖后，依然可见的那些进程

- **用户可感知的进程**
这些进程包括那些 activity 状态为 PAUSING/PAUSED/STOPPING的进程, 以及有 overlay、前端service的进程. 
以及**被设置成forcingToImportant的进程，这类进程主要正在显示Toast的进程**。

![Toast.png](https://upload-images.jianshu.io/upload_images/5688445-0ce84d2754e89154.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- **previous进程**
这里特别注意，**previous进程把HOME进程排除了**，也就是如果从launcher启动一个APP, 那么照理说previous进程应该是HOME进程才对，但是AMS在更新previous进程时，将HOME进程排除在外了，所以HOME进程永远不会是previous进程
```
void updatePreviousProcessLocked(ActivityRecord r) {
        // ...
        // Now set this one as the previous process, only if that really
        // makes sense to. 排除了home进程
        if (r.app != null && fgApp != null && r.app != fgApp
                && r.lastVisibleTime > mService.mPreviousProcessVisibleTime
                && r.app != mService.mHomeProcess) {
            mService.mPreviousProcess = r.app;
            mService.mPreviousProcessVisibleTime = r.lastVisibleTime;
        }
    }
```

其它的进程就参见上面的表格吧！

另外，一个进程里的 services 或 provider是有可能会提升该进程的oom adj
比如一个oomadj更小的进程(也就是更重要的进程)正绑定了oomadj更大的进程，且在绑定的时候允许对host service的adj进行promotion, 此时，hosting service的进程就有可能会被promote到与client进程相同的oomadj, 也就是说，如果host service进程的oomadj太大了，那它可能会被kill掉，而此时更重要的client进程还绑定在该service上，所以这时就有可能出现混乱。对于provider同理。

# 二 native与kernel oomadj

![applyOOMadj](https://upload-images.jianshu.io/upload_images/5688445-a64bd7ad41bff99d.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

![lmkd](https://upload-images.jianshu.io/upload_images/5688445-c3739d80ba5d7b05.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

当算出了一个进程的 oomadj 后，就会往lkmd里去更新该oomadj, 前提是最新的与上一次的oomadj不一样的时候。
最终是将oomadj写入到  /proc/%d/oom_score_adj 中.

Android中进行low memory killer根据一些配置有可能发生在lmkd中，也有可能发生在kernel里
具体是

```
#define INKERNEL_MINFREE_PATH "/sys/module/lowmemorykiller/parameters/minfree"
has_inkernel_module = !access(INKERNEL_MINFREE_PATH, W_OK);
use_inkernel_interface = has_inkernel_module && !is_go_device;
```
如果不能访问 "/sys/module/lowmemorykiller/parameters/minfree", 且不是 go device.在这种情况下，使用kernel的 low memory killer, 否则使用lmkd的 find_and_kill_process

## 2.1 lmkd的find_and_kill_process
如果使用的是lmkd的查杀功能，那么它进行如下的步骤进行操作
- 监听pressure_level
将/dev/memcg/memory.pressure_level的fd以及它所监听的level水平，如critical或medium写入到/dev/memcg/cgroup.event_control, 当有事件发生了，便会触发mp_event或mp_event_critical, 最终都会调用mp_event_common

- 计算内存使用情况
/dev/memcg/memory.usage_in_bytes
/dev/memcg/memory.memsw.usage_in_bytes
获得系统内存以及swap内存的使用情况，决定是否触发find_and_kill_process去查找进程并kill

- find_and_kill_process
find_and_kill_process就比较简单了，从oomadj最高往下依次查找，如果是critical，则查找到ro.lmk.critical 默认是0; 如果是medium，则查找到ro.lmk.medium, 默认是800;
找到一个就kill掉，然后就不往下查找了。测试find_and_kill_process只会杀一个进程，如果在kill掉一个进程后，内存还是不满足，则kernel会触发下一次事件... 这样不断的轮询

## 2.2 kernel的low memory killer
`drivers/staging/android/lowmemorykiller.c`
这里面通过lowmem_scan遍历所有的进程task_struct，然后挑选出oomadj最大值, 如果两个oomadj值相同，则比较两个进程的内存使用，选择占用内存大的进程进行kill
```
    //找oomadj最大的进程
    for_each_process(tsk) {
        struct task_struct *p; 
        short oom_score_adj;

        if (tsk->flags & PF_KTHREAD)
            continue;

        p = find_lock_task_mm(tsk);
        if (!p)
            continue;

        if (task_lmk_waiting(p) &&
            time_before_eq(jiffies, lowmem_deathpending_timeout)) {
            task_unlock(p);
            rcu_read_unlock();
            return 0;
        }   
        oom_score_adj = p->signal->oom_score_adj;
        if (oom_score_adj < min_score_adj) {
            task_unlock(p);
            continue;
        }   
        tasksize = get_mm_rss(p->mm);
        task_unlock(p);
        if (tasksize <= 0)
            continue;
        if (selected) {
            if (oom_score_adj < selected_oom_score_adj)
                continue;
            if (oom_score_adj == selected_oom_score_adj &&
                tasksize <= selected_tasksize)
                continue;
        }   
        selected = p;
        selected_tasksize = tasksize;
        selected_oom_score_adj = oom_score_adj;
        lowmem_print(2, "select '%s' (%d), adj %hd, size %d, to kill\n",
                 p->comm, p->pid, oom_score_adj, tasksize);
    }
    //如果已经找到，则向进程发送 SIGKILL 信号
    if (selected) {
        long cache_size = other_file * (long)(PAGE_SIZE / 1024);
        long cache_limit = minfree * (long)(PAGE_SIZE / 1024);
        long free = other_free * (long)(PAGE_SIZE / 1024);

        task_lock(selected);
        send_sig(SIGKILL, selected, 0);
        if (selected->mm)
            task_set_lmk_waiting(selected);
        task_unlock(selected);
        trace_lowmemory_kill(selected, cache_size, cache_limit, free);
        lowmem_deathpending_timeout = jiffies + HZ;
        rem += selected_tasksize;
    }
```

在PIXEL手机中测试其实是没有mp_event/mp_event_critical，也就是kill process这个动作由kernel去完成，而不是lmkd去完成。

# 三 Android Framework kill掉cached empty进程
lmkd或kernel都可能会去kill掉进程，除此之外android framework也会试着去kill cached/empty进程
```
    final void updateOomAdjLocked() {
        //计算出 empty和cached的进程的大小限制
        final int emptyProcessLimit = mConstants.CUR_MAX_EMPTY_PROCESSES;
        final int cachedProcessLimit = mConstants.CUR_MAX_CACHED_PROCESSES - emptyProcessLimit;   
        ...
        //注意，这里是遍历LRU缓存的进程，从最新使用的进程开始
        for (int i=N-1; i>=0; i--) {
            ProcessRecord app = mLruProcesses.get(i);
            if (!app.killedByAm && app.thread != null) {
                app.procStateChanged = false;
                
                //计算该进程的 oomadj 和 curProcState
                computeOomAdjLocked(app, ProcessList.UNKNOWN_ADJ, TOP_APP, true, now);
                ...
                
                //将 oomadj 更新到 lmkd中
                applyOomAdjLocked(app, true, now, nowElapsed);

                
                // Count the number of process types.
                switch (app.curProcState) {
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY:
                    case ActivityManager.PROCESS_STATE_CACHED_ACTIVITY_CLIENT:
                        //如果进入该分支，说明是cached的进程，更新numCached
                        mNumCachedHiddenProcs++;
                        numCached++;
                        //如果cached的进程超过限制了，调用 app.kill 该进程
                        if (numCached > cachedProcessLimit) {
                            app.kill("cached #" + numCached, true);
                        }
                        break;
                    case ActivityManager.PROCESS_STATE_CACHED_EMPTY:
                        //进入该分支是表明是 EMPTY 进程, 如果超过限制，也直接kill掉
                      
                        if (numEmpty > mConstants.CUR_TRIM_EMPTY_PROCESSES
                                && app.lastActivityTime < oldTime) {
                            //这里超过了CUR_TRIM_EMPTY_PROCESSES这个限制
                            //且距离上一次使用的时间超过30分钟，就kill掉，否则进入else，
                            app.kill("empty for "
                                    + ((oldTime + ProcessList.MAX_EMPTY_TIME - app.lastActivityTime)
                                    / 1000) + "s", true);
                        } else {
                            numEmpty++;
                            //增加numEmpty, 如果缓存的大小大于 emptyProcessLimit也直接kill掉
                            if (numEmpty > emptyProcessLimit) {
                                app.kill("empty #" + numEmpty, true);
                            }
                        }
                        break;
                    default:
                        mNumNonCachedProcs++;
                        break;
                }

                //如果app已经被置为 isolated了，且没有services
                if (app.isolated && app.services.size() <= 0) {
                    app.kill("isolated not needed", true);
                } else {
                    ...
                }

                //计算出可以进入 memory trim的进程数量，前提是
                // curProcState 要高于 HOME 的那些进程
                if (app.curProcState >= ActivityManager.PROCESS_STATE_HOME
                        && !app.killedByAm) {
                    numTrimming++;
                }
            }
        }
    ...
```
updateOomAdjLocked函数会对LRU里的进程依次计算各个进程的oomadj, 以及进程的curProcState, 在设置对应进程的oomadj后，会根据进程的curProcState的状态，来决定是否kill一些进程，如上面代码所示，从LRU里最近最常使用的进程开始遍历，如果curProcState是CACHED_ACTIVITY, 且缓存的进程数量超过了 cachedProcessLimit, 就会触发 Process.kill 将该进程kill掉。 同理对于 CACHED_EMPTY进程一样，只不过他们的limit不一样而已。
```
        //计算出 empty和cached的进程的大小限制
        final int emptyProcessLimit = mConstants.CUR_MAX_EMPTY_PROCESSES;
        final int cachedProcessLimit = mConstants.CUR_MAX_CACHED_PROCESSES - emptyProcessLimit;  
```

![default_cached_empty_limit](https://upload-images.jianshu.io/upload_images/5688445-61600f6d0339fe6c.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)
上图是AOSP默认的limit.

# 四 如何调试lmkd
在 Developer Options -> Apps -> Background process limit

|Options|Values|
|:-|:-|
|Standard limit                     |-1|
|No background processes |0|
|At most 1 process              |1|
|At most 2 process              |2|
|At most 3 process              |3|
|At most 4 process              |4|

选择一项，最终会触发 setProcessLimit
```
    public void setProcessLimit(int max) {
        synchronized (this) {
            mConstants.setOverrideMaxCachedProcesses(max);
        }
        trimApplications();
    }
    public void setOverrideMaxCachedProcesses(int value) {
        mOverrideMaxCachedProcesses = value;
        updateMaxCachedProcesses();
    }
    private void updateMaxCachedProcesses() {
        CUR_MAX_CACHED_PROCESSES = mOverrideMaxCachedProcesses < 0
                ? MAX_CACHED_PROCESSES : mOverrideMaxCachedProcesses;
        CUR_MAX_EMPTY_PROCESSES = computeEmptyProcessLimit(CUR_MAX_CACHED_PROCESSES);
        final int rawMaxEmptyProcesses = computeEmptyProcessLimit(MAX_CACHED_PROCESSES);
        CUR_TRIM_EMPTY_PROCESSES = rawMaxEmptyProcesses/2;
        CUR_TRIM_CACHED_PROCESSES = (MAX_CACHED_PROCESSES-rawMaxEmptyProcesses)/3;
    }
```
如果选择 `No background processes`， 则第三小节中 `updateOomAdjLocked`  中 `cachedProcessLimit` 和 `emptyProcessLimit` 将会为0，
则表明如果一个进程被计算出来是CACHED或者EMPTY的进程, 就会被直接kill掉。

# 五 参考 
https://www.cnblogs.com/tiger-wang-ms/p/6445213.html
