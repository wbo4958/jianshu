> 本文基于 AOSP android-8.1.0_r31

Android考虑到App可能存在被低杀的可能，所以它提供一些回调给APP，让APP在进入后台时被低杀之前，能保存一些当前APP的状态。这样，当发生低杀且再次重启后，App可以根据之前所保存的状态来对APP进行恢复。

# 一、onSaveInstanceState
[onSaveInstanceState](https://developer.android.com/reference/android/app/Activity.html#onSaveInstanceState(android.os.Bundle))

This method is called before an activity may be killed so that when it comes back some time in the future it can restore its state. For example, if activity B is launched in front of activity A, and at some point activity A is killed to reclaim resources, activity A will have a chance to save the current state of its user interface via this method so that when the user returns to activity A, the state of the user interface can be restored via [onCreate(Bundle)](https://developer.android.com/reference/android/app/Activity.html#onCreate(android.os.Bundle)) or [onRestoreInstanceState(Bundle)](https://developer.android.com/reference/android/app/Activity.html#onRestoreInstanceState(android.os.Bundle)).

尽管API中的介绍是说系统去kill掉一个Activity而不是整个Application去回收内存, 这种情况，暂时还不知道系统是怎么去做的，作为一个问题吧。
另一种情况是按HOME键时，将Activity放到了后台了，经过其它操作后，该进程可能会被低杀。当再次回到该进程时，会再次恢复之前保存的状态。

 API中的介绍是说当一个Activity被Kill之前会被调用来保存一些当前Activity的状态，这样，当用户再次回到该Activity时，之前保存的状态可能会在onCreate或onRestoreInstanceState中恢复。

**在3.0版本之前**，onSaveInstanceState一般发生在performPauseActivity里，
也就是在onPause ->**`onSaveInstanceState`** 之前

**在3.0及以后的版本**，onSaveInstanceState一般发生在performStopActivity里，
也就是onPause -> **`onSaveInstanceState`** -> onStop
也有可能是发生在handleRelaunchActivity中。

这里以performStopActivity为例进行说明，

## 1.1 App端保存状态并往AMS中发送

```
private void handleStopActivity(IBinder token, boolean show, int configChanges, int seq) {
        ...
        StopInfo info = new StopInfo();
        performStopActivityInner(r, info, show, true, "handleStopActivity");
        ...
        info.activity = r;
        info.state = r.state;
        info.persistentState = r.persistentState;
        mH.post(info);
    }
```
handleStopActivity完成两件事，
- **一是调用onSaveInstanceState,**

```
    private void performStopActivityInner(ActivityClientRecord r,
            StopInfo info, boolean keepShown, boolean saveState, String reason) {
        if (r != null) {
            ...
            
            if (!r.activity.mFinished && saveState) {
                if (r.state == null) {
                    callCallActivityOnSaveInstanceState(r);
                }
            }
        ...
    }
```
这里请注意，在调用 callCallActivityOnSaveInstanceState 时，如果一个Activity已经标志了mFinished. 表示该Activity即将会被destroy掉，此时是没有必要去保存当前Activity状态的。比如按BACK键时，此时会Destroy Activity, 在这种情况下，是不会调用 onSaveInstanceState的.

接着看callCallActivityOnSaveInstanceState,  为Activity生成一个Bundle类型的state. 然后Activity时会将一些状态保存到该state中。
```
    private void callCallActivityOnSaveInstanceState(ActivityClientRecord r) {
        r.state = new Bundle();
        r.state.setAllowFds(false);
        if (r.isPersistable()) {
            r.persistentState = new PersistableBundle();
            mInstrumentation.callActivityOnSaveInstanceState(r.activity, r.state,
                    r.persistentState);
        } else {
            mInstrumentation.callActivityOnSaveInstanceState(r.activity, r.state);
        }
    }
```
- **二是往AMS中发送StopInfo**

从上一小节调用onSaveInstanceState可知，信息是保存在Activity里Bundle类型的state中的，那么这个state最终是放在哪里保存，它才能被恢复呢？？？
肯定不会是保存在当前进程，因为如果当前进程被低杀了(low memory killer)，进程里所有的资源，内存都会被回收掉，结果就是下次进来根本找到不之前的Bundle类型的state了。

所以应该是保存在system进程中了，因为所有Activity的启动都是由system进程启动的，所以将state保存到system进程是可行的。事实上就是这么干的，下来来看下是如何实现的。

```
        info.activity = r;
        info.state = r.state;
        info.persistentState = r.persistentState;
        mH.post(info);
```
handleStopActivity会post一个StopInfo, StopInfo里保存了Activity的state值, 它会通过Binder将state发送给AMS， Bundle是一个Parcable, 所以它是可以跨进程传输。
```
private static class StopInfo implements Runnable {
        ActivityClientRecord activity;
        Bundle state;
        ...
        @Override public void run() {
            // Tell activity manager we have been stopped.
            try {
                ActivityManager.getService().activityStopped(
                    activity.token, state, persistentState, description);
            } catch (RemoteException ex) {
                ...
            }
        }
    }
```

## 1.2 AMS保存进程Activity中的状态
AMS在收到App的请求后，会调用activityStopped来试着保存App端Activity的状态。
```
    public final void activityStopped(IBinder token, Bundle icicle,
            PersistableBundle persistentState, CharSequence description) {
        ...
        synchronized (this) {
            final ActivityRecord r = ActivityRecord.isInStackLocked(token); //找到AMS中对应的ActivityRecord
            if (r != null) {
                r.activityStoppedLocked(icicle, persistentState, description);
            }
        }
        ...
    }
```
activityStoppedLocked会将Activity的信息保存到ActivityRecord中的 icicle 中，到此，Activity的状态信息就保存完毕。
```
ActivityRecord.java
    final void activityStoppedLocked(Bundle newIcicle, PersistableBundle newPersistentState,
            CharSequence description) {

        if (newIcicle != null) {
            // If icicle is null, this is happening due to a timeout, so we haven't really saved
            // the state.
            icicle = newIcicle;
            ...
        }
     ...
}
```

# 二、恢复state
既然有保存，那么就应该有恢复。

## 2.1 AMS找到之前已经保存state的ActivityRecord
当用户重新回到Activity时，实际上是触发了startActivity动作。
```
AMS.startActivity -> AMS.startActivityAsUser -> 
    -> ActivityStarter.startActivityMayWait -> ActivityStarter.startActivityLocked 
    -> ActivityStarter.startActivity
       ->  ActivityRecord r = new ActivityRecord //生成一个新的ActivityRecord，注意，此时的ActivityRecord并非之前的被kill掉的Activity。
    -> ActivityStarter.startActivity -> ActivityStarter.startActivityUnchecked
        -> ActivityRecord reusedActivity = getReusableIntentActivity();
```
```
    private ActivityRecord getReusableIntentActivity() {
        boolean putIntoExistingTask = ((mLaunchFlags & FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (mLaunchFlags & FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || mLaunchSingleInstance || mLaunchSingleTask;
        putIntoExistingTask &= mInTask == null && mStartActivity.resultTo == null;
        ActivityRecord intentActivity = null;
        if (mOptions != null && mOptions.getLaunchTaskId() != -1) {
          ...
        } else if (putIntoExistingTask) {
            if (mLaunchSingleInstance) {
              ...
            } else if ((mLaunchFlags & FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0) {
              ...
            } else {
                intentActivity = mSupervisor.findTaskLocked(mStartActivity, mSourceDisplayId);
            }
        }
        return intentActivity;
    }
```
getReusableIntentActivity的目的就是在系统的ActivityStack中查找是否可以复用的Activity. 这些其实就是Android的singleInstance, single task的相关概念。
要复用Activity, putIntoExistingTask得为true, 可以看下它为true的条件是什么
要么是singleInstance, 要么是single task, 要么设置NEW_STASK,但是不能是MULTI_TASK等等，这里并不作太多说明。
接着看 findTaskLocked
```
    ActivityRecord findTaskLocked(ActivityRecord r, int displayId) {
        mTmpFindTaskResult.r = null;
        mTmpFindTaskResult.matchedByRootAffinity = false;
        ActivityRecord affinityMatch = null;
        if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Looking for task of " + r);
        for (int displayNdx = mActivityDisplays.size() - 1; displayNdx >= 0; --displayNdx) {
            final ArrayList<ActivityStack> stacks = mActivityDisplays.valueAt(displayNdx).mStacks;
            for (int stackNdx = stacks.size() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = stacks.get(stackNdx);
                ...
                stack.findTaskLocked(r, mTmpFindTaskResult);
                if (mTmpFindTaskResult.r != null) {
                    if (!mTmpFindTaskResult.matchedByRootAffinity) {
                        return mTmpFindTaskResult.r;
                    } else if (mTmpFindTaskResult.r.getDisplayId() == displayId) {
                        affinityMatch = mTmpFindTaskResult.r;
                    }
                }
            }
        }
        return affinityMatch;
    }
```
findTaskLocked从所有display中所有的stack中，所有的task中依次查找，是否能复用Activity, 找到后放到mTmpFindTaskResult.r 中
```
    void findTaskLocked(ActivityRecord target, FindTaskResult result) {
        ...
        for (int taskNdx = mTaskHistory.size() - 1; taskNdx >= 0; --taskNdx) {
            final TaskRecord task = mTaskHistory.get(taskNdx);

            // Overlays should not be considered as the task's logical top activity.
            final ActivityRecord r = task.getTopActivity(false /* includeOverlays */);
            ...

            if (taskIntent != null && taskIntent.getComponent() != null &&
                    taskIntent.getComponent().compareTo(cls) == 0 &&
                    Objects.equals(documentData, taskDocumentData)) {
                result.r = r;
                result.matchedByRootAffinity = false;
                break;
            } else if (affinityIntent != null && affinityIntent.getComponent() != null &&
                    affinityIntent.getComponent().compareTo(cls) == 0 &&
                    Objects.equals(documentData, taskDocumentData)) {
                result.r = r;
                result.matchedByRootAffinity = false;
                break;
            } else if (!isDocument && !taskIsDocument
                    && result.r == null && task.rootAffinity != null) {
                if (task.rootAffinity.equals(target.taskAffinity)) {
                    result.r = r;
                    result.matchedByRootAffinity = true;
                }
            } else if (DEBUG_TASKS) Slog.d(TAG_TASKS, "Not a match: " + task);
        }
    }
```
简化后可以看出，是从stack里的mTaskHistory中去找出TOP的Activity来比较，是否是需要复用的Activity.

```
    /**
     * The back history of all previous (and possibly still
     * running) activities.  It contains #TaskRecord objects.
     */
    private final ArrayList<TaskRecord> mTaskHistory = new ArrayList<>();
```
**从mTaskHistory定义可以看出来，mTaskHistory是包含了之前所有acitivity. `前提是这个activity本身没有被Destroy`, 如果一个Activity被Destroy了，会调用removeActivityFromHistoryLocked将该Activity从History中移去。**

## 2.2 App端启动一个Activity
启动Activity的流程与正常的流程一样，AMS通过Binder调用scheduleLaunchActivity, 并将ActivityRecord里的icicle传递给App. icicle里的内容就是App上次保存的Bundle的内容。

```
app.thread.scheduleLaunchActivity(... r.icicle ... );
```
```
public final void scheduleLaunchActivity(... Bundle state ...) {
            ...
            r.state = state; //保存到state里
            ...
            sendMessage(H.LAUNCH_ACTIVITY, r);
        }
```
接着会调用performLaunchActivity，开始进入Activity的生命周期。
```
 private Activity performLaunchActivity(ActivityClientRecord r, Intent customIntent) {
            //前面的代码通过反射获得Activity实例
            if (activity != null) {
                //进入onCreate生命周期，注意，此时r.state是上次Activity保存的状态信息。                
                if (r.isPersistable()) {
                    mInstrumentation.callActivityOnCreate(activity, r.state, r.persistentState);
                } else {
                    mInstrumentation.callActivityOnCreate(activity, r.state);
                }
                ...
                //接着调用 onRestoreInstanceState
                if (!r.activity.mFinished) {
                    if (r.isPersistable()) {
                        if (r.state != null || r.persistentState != null) {
                            mInstrumentation.callActivityOnRestoreInstanceState(activity, r.state,
                                    r.persistentState);
                        }
                    } else if (r.state != null) {
                        mInstrumentation.callActivityOnRestoreInstanceState(activity, r.state);
                    }
                }
                //调用onPoseCreate
                if (!r.activity.mFinished) {
                    activity.mCalled = false;
                    if (r.isPersistable()) {
                        mInstrumentation.callActivityOnPostCreate(activity, r.state,
                                r.persistentState);
                    } else {
                        mInstrumentation.callActivityOnPostCreate(activity, r.state);
                    }
                }
            }
         ...
    }
```
从代码可以看出来，上次Activity所保存的Bundle信息会依次传递给
onCreate -> onRestoreInstanceState -> onPostCreate
至于需要在哪个地方进行恢复，那就是开发者的事件了。








