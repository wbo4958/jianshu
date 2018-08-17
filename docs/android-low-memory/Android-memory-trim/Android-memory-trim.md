> 本文基于 AOSP android-8.1.0_r31

[Android Low memory killer](https://www.jianshu.com/p/b5a8a1d09712) 已经分析了低杀的情况。低杀意味着缓存的数量过多了，或者内存已经出现了不足的情况。尽管经过了低杀了，系统的内存也可能随时都会出现紧张的情况，那么此时比较好的作法就是通知当前没有被杀掉的进程，让这些进程主动去释放一些内存。否则下次进行低杀的时候，这些进程就可能会被杀掉。这样的话，这些进程为了自保，也会被动的愿意去释放一些不用的内存。这样一来系统内存就充足了，就不会低杀了。
所以一个App开发者是很有必要去实现 onTrimMemory

# Memory Trim发生的时机
[Android Low memory killer](https://www.jianshu.com/p/b5a8a1d09712) 中 `updateOomAdjLocked`在计算每个进程的adj, 以及可能的低杀后，就会试着去通知app trim memory.

```
        // Now determine the memory trimming level of background processes.
        // Unfortunately we need to start at the back of the list to do this
        // properly.  We only do this if the number of background apps we
        // are managing to keep around is less than half the maximum we desire;
        // if we are keeping a good number around, we'll let them use whatever
        // memory they want.
        final int numCachedAndEmpty = numCached + numEmpty;
        int memFactor;
        if (numCached <= mConstants.CUR_TRIM_CACHED_PROCESSES
                && numEmpty <= mConstants.CUR_TRIM_EMPTY_PROCESSES) {
            if (numCachedAndEmpty <= ProcessList.TRIM_CRITICAL_THRESHOLD) {
                memFactor = ProcessStats.ADJ_MEM_FACTOR_CRITICAL;
            } else if (numCachedAndEmpty <= ProcessList.TRIM_LOW_THRESHOLD) {
                memFactor = ProcessStats.ADJ_MEM_FACTOR_LOW;
            } else {
                memFactor = ProcessStats.ADJ_MEM_FACTOR_MODERATE;
            }
        } else {
            memFactor = ProcessStats.ADJ_MEM_FACTOR_NORMAL;
        }
```
计算当前内存因子，也就是当前内存的紧张程度。值越大，内存越紧张。

**这里特别注意**，内存因子是根据cached/empty进程的数量来计算的。
|变量|默认值|
|-|-|
|CUR_TRIM_CACHED_PROCESSES |5|
|CUR_TRIM_EMPTY_PROCESSES |8|
|TRIM_CRITICAL_THRESHOLD|3|
|TRIM_LOW_THRESHOLD|5|

也就是说
|cached和empty总数|系统内存状态|
|-|-|
| 0 ~ 3 |critical|
|4, 5|low|
|6 ~ 13|moderate|
| > 13|normal|

这些数值好像与我们平时理解的刚好相反，比如当cached/empty的进程更多时，那此时系统不应该内存更紧张么？因为这些缓存的进程并没有完全释放完内存呀。
但是此时内存因子确为normal状态， 是不是很奇怪。参考https://www.cnblogs.com/tiger-wang-ms/p/6445213.html

> 为什么能根据后台进程和空进程数量来判断出系统的内存等级呢？因为根据之前的分析可以知道，Android系统在后台进程和空进程不超过数量上限时总是尽可能多的保留后台进程和空进程，这样用户便可再再次启动这些进程时减少启动时间从而提高了用户体验；而lowmemeorykiller的机制又会在系统可用内存不足时杀死这些进程，**所以在后台进程和空进程数量少于一定数量时，便表示了系统以及触发了lowmemrorykiller的机制**，而剩余的后台进程和空进程的数量则正好体现了Lowmemroykiller杀进程的程度，即表示当前系统内存的紧张程度。

那这里有个问题，如果系统刚开机时，用户并没有操作过其它app, 那么此时系统的cached/empty的进程岂不是为0，那这时如果启动一个app, 然后它被缓存后，此时它岂不是要提示critical系统内存？？？
**这个当然不是**，系统在启动时，会启动很多非persistent的系统应用，如email/calendar/dialer等等，而这些此时用户并没有使用过，所以它们大多数是empty的进程，在PIXEL手机测试时，发现有10多个empty进程，所以系统一开机时，并不会提示critical系统内存。

![内存因子](https://upload-images.jianshu.io/upload_images/5688445-270cc7700b04ee1b.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

```
        // We always allow the memory level to go up (better).  We only allow it to go
        // down if we are in a state where that is allowed, *and* the total number of processes
        // has gone down since last time.
        if (memFactor > mLastMemoryLevel) {
            if (!mAllowLowerMemLevel || mLruProcesses.size() >= mLastNumProcesses) {
                memFactor = mLastMemoryLevel;
            }
        }
```
决定是否对内存因子降级
```
        mLastMemoryLevel = memFactor;
        mLastNumProcesses = mLruProcesses.size();
        boolean allChanged = mProcessStats.setMemFactorLocked(memFactor, !isSleepingLocked(), now);
        final int trackerMemFactor = mProcessStats.getMemFactorLocked();
```
保存一些变量。

**下面的是对内存因子为critical, moderate, 以及low的情况下进行memory trim.**

```
        if (memFactor != ProcessStats.ADJ_MEM_FACTOR_NORMAL) {
            if (mLowRamStartTime == 0) {
                mLowRamStartTime = now;
            }
            int step = 0;
            int fgTrimLevel;
            switch (memFactor) {
                case ProcessStats.ADJ_MEM_FACTOR_CRITICAL:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL;
                    break;
                case ProcessStats.ADJ_MEM_FACTOR_LOW:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW;
                    break;
                default:
                    fgTrimLevel = ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE;
                    break;
            }
```
将memFactor转换成ComponentCallbacks2中定义的变量名
```
            int factor = numTrimming/3;
            int minFactor = 2;
            if (mHomeProcess != null) minFactor++;
            if (mPreviousProcess != null) minFactor++;
            if (factor < minFactor) factor = minFactor;
            int curLevel = ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
```
计算factor, 这个是步进的factor, curLevel,从最高等级开始
numTrimming是指那些比PROCESS_STATE_HOME不重要的进程
```
            for (int i=N-1; i>=0; i--) { //从最近最常使用的进程开始。
                ProcessRecord app = mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                //那些重要性低于ActivityManager.PROCESS_STATE_HOME的进程的处理，
               //包括B-Service进程、cachedProcess和emptyProcess
                if (app.curProcState >= ActivityManager.PROCESS_STATE_HOME
                        && !app.killedByAm) {
                    if (app.trimMemoryLevel < curLevel && app.thread != null) {
                        try {
                            app.thread.scheduleTrimMemory(curLevel);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = curLevel;
                    step++;
                    //前面的那个步长，trim等级更高，每到一个步长，trim等级都下降一个level.
                    if (step >= factor) {
                        step = 0;
                        switch (curLevel) {
                            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_MODERATE;
                                break;
                            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                                curLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                                break;
                        }
                    }
                //heavy 的进程
                } else if (app.curProcState == ActivityManager.PROCESS_STATE_HEAVY_WEIGHT) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                            && app.thread != null) {
                        try {
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = ComponentCallbacks2.TRIM_MEMORY_BACKGROUND;
                } else {
                    //important，以及那些短暂的, backup的进程 
                    if ((app.curProcState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                            || app.systemNoUi) && app.pendingUiClean) {
                        final int level = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN;
                        if (app.trimMemoryLevel < level && app.thread != null) {
                            try {
                                app.thread.scheduleTrimMemory(level);
                            } catch (RemoteException e) {
                            }
                        }
                        app.pendingUiClean = false;
                    }
                    //其它的一些进程 
                    if (app.trimMemoryLevel < fgTrimLevel && app.thread != null) {
                            app.thread.scheduleTrimMemory(fgTrimLevel);
                        } catch (RemoteException e) {
                        }
                    }
                    app.trimMemoryLevel = fgTrimLevel;
                }
            }
```
**下面是对系统内存为正常的情况下, 对优先级低于PROCESS_STATE_IMPORTANT_BACKGROUND，最多给予TRIM_MEMORY_UI_HIDDEN的提示**
```
        } else {
            if (mLowRamStartTime != 0) {
                mLowRamTimeSinceLastIdle += now - mLowRamStartTime;
                mLowRamStartTime = 0;
            }
            for (int i=N-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if (allChanged || app.procStateChanged) {
                    setProcessTrackerStateLocked(app, trackerMemFactor, now);
                    app.procStateChanged = false;
                }
                if ((app.curProcState >= ActivityManager.PROCESS_STATE_IMPORTANT_BACKGROUND
                        || app.systemNoUi) && app.pendingUiClean) {
                    if (app.trimMemoryLevel < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
                            && app.thread != null) {
                        try {
                            app.thread.scheduleTrimMemory(
                                    ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
                        } catch (RemoteException e) {
                        }
                    }
                    app.pendingUiClean = false;
                }
                app.trimMemoryLevel = 0;
            }
        }
```
当updateOomAdjLocked在killed超过limit的CACHED/EMPTY进程后，接下来会对剩下的CACHED/EMPTY尝试去做 Memory Trim的动作。也就是触发对应进程的scheduleTrimMemory，试着让进程去释放一些内存。

