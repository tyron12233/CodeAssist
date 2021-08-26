package com.tyron.code;
import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

public class ApplicationLoader extends Application {
    
    public static Context applicationContext;
    public static Handler applicationHandler = new Handler(Looper.getMainLooper());
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        applicationContext = this;
    }
    
    public static void showToast(String message) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG)
                .show();
    }
}
