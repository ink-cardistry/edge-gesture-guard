package android.app;

/**
 * 编译桩（compileOnly，不进 APK）。
 *
 * android.app.TaskStackListener 是 @hide 类，android.jar 里没有；
 * 设备上真实存在。这里只声明我们需要的形状：继承 Binder（真实类继承的是一个 AIDL Stub，
 * 同样继承 Binder），并有一个可覆写的 onTaskStackChanged()。
 * 运行时解析到真类，我们覆写的方法会被系统回调。
 */
public abstract class TaskStackListener extends android.os.Binder {

    public TaskStackListener() {
    }

    public void onTaskStackChanged() throws android.os.RemoteException {
    }
}
