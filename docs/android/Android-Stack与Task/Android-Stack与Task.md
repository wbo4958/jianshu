> Android ActivityManagerService里两个很重要的概念就是Stack与Task,
> Stack里管理着Task, Task里管理着Activity, AMS通过Stack和Task调度着Activity.

转载请标明来处:  http://www.jianshu.com/p/82f3af2135a8

# 1. Stack与Task概念

Google在Guide里已经给出来Stack与Task的概念，具体可以参见[tasks-and-back-stack](https://developer.android.com/guide/components/tasks-and-back-stack.html)

以下简介摘自 [Application、Activity Stack 和 Task的区别](http://www.cnblogs.com/hnrainll/archive/2012/12/18/2823064.html)

Activity承担了大量的显示和交互工作，从某种角度上将，我们看见的应用程序就是许多个Activity的组合。
为了让这许多Activity协同工作而不至于产生混乱，Android平台设计了ActivityStack机制用于管理Activity，其遵循先进后出的原则，系统总是显示位于栈顶的Activity，从逻辑上将，位于栈顶的Activity也就是最后打开的Activity, 这也是符合逻辑的。

在操作应用程序时，每次启动新的Activity，都会将此压入Activity Stack，当用户执行返回操作时，移除Activity Stack顶上的Activity，这样就实现了返回上一个Activty的功能。直到用户一直返回到Home Screen，这时候可以理解为移除了Activity Stack所有的Activity，这个Activity Stack不再存在，应用程序也结束了运行.

Task是指将相关的Activity组合到一起，以Activity Stack的方式进行管理。从用户体验上讲，一个“应用程序”就是一个Task，但是从根本上讲，一个Task是可以有一个或多个Android Application组成的.
例如：你想在发送短信时，拍一张照并作为彩信发出去，这时你首先停留在短信应用程序的的Acitivity上，然后跳转到 Camera应用程序的Activity上，当完成拍照功能后，再返回到短信应用程序的Activity. 这实际上是两个Android Application协同合作后完成的工作，但为了更好的用户体验，Android平台加入了Task这么一种机制，让用户没有感觉到应用的中断，让用 户感觉在一“应用程序”里就完成了想完成的工作。

# 2. Stack的分类

ActivityStack 分为以下五类

- 0 HOME_STACK_ID
 Home应用以及recents app所在的栈
- 1 FULLSCREEN_WORKSPACE_STACK_ID  
一般应用所在的栈
- 2 FREEFORM_WORKSPACE_STACK_ID   
类似桌面操作系统
- 3 DOCKED_STACK_ID 
- 4 PINNED_STACK_ID
画中画栈

这五种栈称为静态栈

那么如何判断一个栈是不是静态栈呢

```
public static boolean isStaticStack(int stackId) {
    return stackId >= FIRST_STATIC_STACK_ID && stackId <= LAST_STATIC_STACK_ID;
}
```

其中
FIRST_STATIC_STACK_ID = HOME_STACK_ID
LAST_STATIC_STACK_ID = PINNED_STACK_ID

可知如果 stack id >= 5的栈就是动态生成的栈了

# 3. ActivityStack 的创建

ActivityStack是由getStack创建的

```
ActivityStack getStack(int stackId, boolean createStaticStackIfNeeded, boolean createOnTop) {
    ActivityContainer activityContainer = mActivityContainers.get(stackId);
    if (activityContainer != null) {
        return activityContainer.mStack;
    }
    if (!createStaticStackIfNeeded || !StackId.isStaticStack(stackId)) {
        return null;
    }
    return createStackOnDisplay(stackId, Display.DEFAULT_DISPLAY, createOnTop);
}
```

根据createStaticStackIfNeeded值不同，

- false

判断stackId的ActivityStack是否存在，若存在，则返回该ActivityStack, 反之返回null。

- true

如果stackId的ActivityStack不存在，那么创建一个新的 ActivityStack, 并默认attach到主屏上

### 4. 选择合适的stack与task

AMS在启动一个Activity时，怎么选择合适的Stack/Task呢？

在startActivityUnchecked中有三个步聚

- a. 获得 reuseable Activity

``` java
private ActivityRecord getReusableIntentActivity() {
    boolean putIntoExistingTask = ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0 &&
            (mLaunchFlags & FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
            || mLaunchSingleInstance || mLaunchSingleTask;
    // putIntoExistingTask 这个标志位就排除掉了 standard 和 singleTop (以及与之等同的flag) 两种启动模式
    putIntoExistingTask &= mInTask == null && mStartActivity.resultTo == null;
    ActivityRecord intentActivity = null;

    if (mOptions != null && mOptions.getLaunchTaskId() != -1) {
        //这个分支是知道将这个activity启动到哪个task里，一般很少用到
        final TaskRecord task = mSupervisor.anyTaskForIdLocked(mOptions.getLaunchTaskId());
        intentActivity = task != null ? task.getTopActivity() : null;
    } else if (putIntoExistingTask) {  // 一般会fall back到这个分支
        if (mLaunchSingleInstance) {
            //针对 single instance,  findActivityLocked会在所有的stack,所有的task里查找
            //对应的activity, 然后返回。
            //从这里也可以看出single instance在整个系统中只能存在一个单独且唯一的task中
           intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info, false);
        } else if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
            //这个用在多窗口的分屏中，指定显示activity挨着打开这个activity的旁边
            intentActivity = mSupervisor.findActivityLocked(mIntent, mStartActivity.info,
                    !mLaunchSingleTask);
        } else {
            //遍历每个stack里的每个task, 找到top的activity 并与即将要启动的activity比较
            //得出是否可以重用找到的activity. 涉及到document/ affinity相关
            intentActivity = mSupervisor.findTaskLocked(mStartActivity);
        }
    }
    return intentActivity;
}
```

- b. 对找到的reuseable 的activity计算stack和task

``` java
if (mReusedActivity != null) {

    if (mStartActivity.task == null) {
        //设置为reuseable的activity task
        mStartActivity.task = mReusedActivity.task;
    }
    if (mReusedActivity.task.intent == null) {
        //如果找到的task没有base的intent,那么将即将要启动的activity设置为task的base的intent
        mReusedActivity.task.setIntent(mStartActivity);
    }

    if ((mLaunchFlags & FLAG_ACTIVITY_CLEAR_TOP) != 0
            || mLaunchSingleInstance || mLaunchSingleTask) {
        //对 single task/single instance,  clear掉task中该activity之上的所有activity
        final ActivityRecord top = mReusedActivity.task.performClearTaskForReuseLocked(
                mStartActivity, mLaunchFlags);
    }

    // 设置 target stack/ reuse task 等
    mReusedActivity = setTargetStackAndMoveToFrontIfNeeded(mReusedActivity);
    setTaskFromIntentActivity(mReusedActivity);

    if (!mAddingToTask && mReuseTask == null) {
        //这个会是在 single instance/single task等下进入分支。
        //最简单的案例，single instance/single task的activity已经启动过且处于top
        //那么这时就进入这个分支
        resumeTargetStackIfNeeded();
        return START_TASK_TO_FRONT;
    }
}
```

- c. 过滤

``` java
//如果要启动的activity已经被启动过且处于top, 这时再看下是否还需要重新启动一次
 final ActivityStack topStack = mSupervisor.mFocusedStack;
 final ActivityRecord top = topStack.topRunningNonDelayedActivityLocked(mNotTop);
 final boolean dontStart = top != null && mStartActivity.resultTo == null
         && top.realActivity.equals(mStartActivity.realActivity)
         && top.userId == mStartActivity.userId
         && top.app != null && top.app.thread != null
         && ((mLaunchFlags & FLAG_ACTIVITY_SINGLE_TOP) != 0
         || mLaunchSingleTop || mLaunchSingleTask);
//可以看出来，这里主要是single Top案例,即top是single top的activity, 再一次启动，将会
//deliver到newIntent()里
 if (dontStart) {
     top.deliverNewIntentLocked(
             mCallingUid, mStartActivity.intent, mStartActivity.launchedFromPackage);
     return START_DELIVERED_TO_TOP;
 }
```

- d. 设置真正的stack/task

``` java
// Should this be considered a new task?
if (mStartActivity.resultTo == null && mInTask == null && !mAddingToTask
        && (mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0) {
    newTask = true;
    setTaskFromReuseOrCreateNewTask(taskToAffiliate);
    //找不到复用的activity，这时计算使用哪个stack, 在计算出来的stack中创建新的task
} else if (mSourceRecord != null) {
    //将task设置为打开该activity的source activity一样
    final int result = setTaskFromSourceRecord();
    if (result != START_SUCCESS) {
        return result;
    }
} else if (mInTask != null) {
    //设置为in task
    final int result = setTaskFromInTask();
} else {
    //never happen
    setTaskToCurrentTopOrCreateNewTask();
}
```

接着看下computeStackFocus

``` java
private ActivityStack computeStackFocus(ActivityRecord r, boolean newTask, Rect bounds,
        int launchFlags, ActivityOptions aOptions) {
    final TaskRecord task = r.task;

    //如果这个Activity是Home应用，或者它是Home的Task，那么就直接使用 Home Stack
    if (!(r.isApplicationActivity() || (task != null && task.isApplicationTask()))) {
        return mSupervisor.mHomeStack;
    }

    //这个主要是分屏时获得的 stack, 后续再说这个[multi-window]
    //参见https://developer.android.com/guide/topics/ui/multi-window.html
    ActivityStack stack = getLaunchStack(r, launchFlags, task, aOptions);
    if (stack != null) {
        return stack;
    }

    // 如果 activity 已经在一个task里了，那么就直接使用它的task所在的stack即可，
    // 如果是启动的一个新的应用，是先选择或创建 ActivityStack, 然后在stack中创建 task
    if (task != null && task.stack != null) {
        stack = task.stack;
        return stack;
    }

    // container是 startActivity传入的，暂时还不知道有什么用 ????
    final ActivityStackSupervisor.ActivityContainer container = r.mInitialActivityContainer;
    if (container != null) {
        // The first time put it on the desired stack, after this put on task stack.
        r.mInitialActivityContainer = null;
        return container.mStack;
    }

    // The fullscreen stack can contain any task regardless of if the task is resizeable
    // or not. So, we let the task go in the fullscreen task if it is the focus stack.
    // If the freeform or docked stack has focus, and the activity to be launched is resizeable,
    // we can also put it in the focused stack.
    // 如果当前focused的stack满足以下条件，那么就可以使用focused 的stack
    // 注意，如果当前focused stack 为Home stack, 那么它并不满足以下判断条件
    final int focusedStackId = mSupervisor.mFocusedStack.mStackId;
    final boolean canUseFocusedStack = focusedStackId == FULLSCREEN_WORKSPACE_STACK_ID
            || (focusedStackId == DOCKED_STACK_ID && r.canGoInDockedStack())
            || (focusedStackId == FREEFORM_WORKSPACE_STACK_ID && r.isResizeableOrForced());
    if (canUseFocusedStack && (!newTask
            || mSupervisor.mFocusedStack.mActivityContainer.isEligibleForNewTasks())) {
        return mSupervisor.mFocusedStack;
    }

    // 开始首先尝试动态的stack, 即 stackId >= 5的栈
    // We first try to put the task in the first dynamic stack.
    final ArrayList<ActivityStack> homeDisplayStacks = mSupervisor.mHomeStack.mStacks;
    for (int stackNdx = homeDisplayStacks.size() - 1; stackNdx >= 0; --stackNdx) {
        stack = homeDisplayStacks.get(stackNdx);
        if (!ActivityManager.StackId.isStaticStack(stack.mStackId)) {
            if (DEBUG_FOCUS || DEBUG_STACK) Slog.d(TAG_FOCUS,
                    "computeStackFocus: Setting focused stack=" + stack);
            return stack;
        }
    }

    // 如果当前没有动态的stack, 那就生成一个新的stack,
    // 从下面的判断条件，新生成的栈要么是 FULLSCREEN_WORKSPACE_STACK_ID 要么是 FREEFORM_WORKSPACE_STACK_ID
    // If there is no suitable dynamic stack then we figure out which static stack to use.
    final int stackId = task != null ? task.getLaunchStackId() :
            bounds != null ? FREEFORM_WORKSPACE_STACK_ID :
                    FULLSCREEN_WORKSPACE_STACK_ID;
    stack = mSupervisor.getStack(stackId, CREATE_IF_NEEDED, ON_TOP);
    return stack;
}
```

# 5. Activity的launchMode

- 开机后第一个stack, Home stack

![图 1 Home Stack](http://upload-images.jianshu.io/upload_images/5688445-50f6316f98ee25c9.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- Launcher->AAA->BBB

AAA与BBB都是standard的launchMode


![图 2 Launcher_AAA_BBB](http://upload-images.jianshu.io/upload_images/5688445-b4bd193055c567a4.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

**注意**

从Launcher打开的app都会自动加上FLAG_ACTIVITY_NEW_TASK, 因此可知每个app默认启动在不同的task里, 从task的定义也可以看出来.

`A task is a collection of activities that users interact with when performing a certain job`

```
private boolean startActivity(View v, Intent intent, Object tag) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    ...
}
```

- Single task

![图 3 single task](http://upload-images.jianshu.io/upload_images/5688445-2d1287331b741728.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

- Single instance

![图 4 Single instance](http://upload-images.jianshu.io/upload_images/5688445-b2eeb570023fc991.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)


- Single top

![图 5 Single top](http://upload-images.jianshu.io/upload_images/5688445-40302e3891b81f37.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)

# 6. 参考
- [Understand Android Activity's launchMode: standard, singleTop, singleTask and singleInstance](https://inthecheesefactory.com/blog/understand-android-activity-launchmode/en)
- [tasks-and-back-stack](https://developer.android.com/guide/components/tasks-and-back-stack.html)
- [Application、Activity Stack 和 Task的区别](http://www.cnblogs.com/hnrainll/archive/2012/12/18/2823064.html)
