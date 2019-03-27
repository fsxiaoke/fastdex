package fastdex.runtime;

import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import fastdex.runtime.loader.MultiDex;

/**
 * Created by tong on 17/3/11.
 */
public class FastdexApplication extends Application {
    public static final String LOG_TAG = "Fastdex";

    private Application realApplication;

    protected void attachBaseContext(Context context) {
        super.attachBaseContext(context);
        MultiDex.install(context);
        fixGoogleMultiDex();

        createRealApplication(this);

        Fastdex.get().initialize(this,this); //xiongtj 提前加载补丁

        invokeAttachBaseContext(context);
    }

    @Override
    public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
    }


    private void invokeAttachBaseContext(Context context) {
        if (this.realApplication != null) {
            try {
                Method attachBaseContext = ContextWrapper.class
                        .getDeclaredMethod("attachBaseContext", new Class[]{Context.class});

                attachBaseContext.setAccessible(true);
                attachBaseContext.invoke(this.realApplication, new Object[]{context});
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    public void onCreate() {
        super.onCreate();


        if (this.realApplication != null) {
            this.realApplication.onCreate();
        }
    }

    private void fixGoogleMultiDex() {
        try {
            Class clazz = getClassLoader().loadClass("android.support.multidex.MultiDex");
            Field field = clazz.getDeclaredField("installedApk");
            field.setAccessible(true);
            Set<String> installedApk = (Set<String>) field.get(null);

            installedApk.addAll(MultiDex.installedApk);
        } catch (Throwable e) {

        }
    }

    private void createRealApplication(Context context) {
       String applicationClass = getOriginApplicationName(context);
        if (applicationClass != null) {
            Log.d(LOG_TAG, new StringBuilder().append("About to create real application of class name = ").append(applicationClass).toString());

            try {
                Class realClass = Class.forName(applicationClass);
                Constructor constructor = realClass.getConstructor(Application.class);
                this.realApplication = ((Application) constructor.newInstance(this));
                Log.v(LOG_TAG, new StringBuilder().append("Created real app instance successfully :").append(this.realApplication).toString());

            } catch (Exception e) {
                try {
                    Class realClass = Class.forName(applicationClass);
                    Constructor constructor = realClass.getConstructor(new Class[0]);
                    this.realApplication = ((Application) constructor.newInstance(new Object[0]));
                    Log.v(LOG_TAG,new StringBuilder().append("Created real app instance successfully2 :").append(this.realApplication).toString());
                } catch (Exception ee) {
                    throw new IllegalStateException(ee);
                }

            }
        } else {
            this.realApplication = new Application();
        }
    }

    private String getOriginApplicationName(Context context) {
        ApplicationInfo appInfo = null;
        try {
            appInfo = context.getPackageManager().getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        return appInfo.metaData.getString("FASTDEX_ORIGIN_APPLICATION_CLASSNAME");
    }



}

