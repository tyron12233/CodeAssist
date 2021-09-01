package com.tyron.code.ui.editor.log;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.LiveData;
import java.util.List;
import java.util.ArrayList;

import android.os.Handler;
import android.os.Looper;

public class LogViewModel extends ViewModel {
    
    private static int totalCount;
    public static final int APP_LOG = totalCount++;
    public static final int BUILD_LOG = totalCount++;
    public static final int DEBUG = totalCount++;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<MutableLiveData<String>> log;
    
    public LiveData<String> getLogs(int id) {
        if (log == null) {
            log = init();
        }
        return log.get(id);
    }
    
    private List<MutableLiveData<String>> init() {
        List<MutableLiveData<String>> list = new ArrayList<>();
        for (int i = 0; i < totalCount; i++) {
            list.add(new MutableLiveData<>(""));
        }
        return list;
    }
    
    public void d(int id, String message) {
        String current = getLogs(id).getValue();
        if (current.isEmpty()) {
            current = message;
        } else {
            current = current + '\n' + "<$$debug>" + message + "</$$debug>";
        }
        
        if (Looper.myLooper() != Looper.getMainLooper()) {
            String finalCurrent = current;
            mainHandler.post(() -> log.get(id).setValue(finalCurrent));
        } else {
           log.get(id).setValue(current);
        }
    }
    
    public void clear(int id) {
        MutableLiveData<String> data = (MutableLiveData<String>) getLogs(id);
        data.setValue("");
    }

    public void w(int id, String message) {
        String current = getLogs(id).getValue();
        if (current.isEmpty()) {
            current = message;
        } else {
            current = current + '\n' + "<$$warning>" + message + "</$$warning>";
        }

        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            log.get(id).postValue(current);
        } else {
            log.get(id).setValue(current);
        }
    }

    public void e(int id, String message) {
        String current = getLogs(id).getValue();
        if (current.isEmpty()) {
            current = message;
        } else {
            current = current + '\n' + "<$$error>" + message + "</$$error>";
        }

        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            log.get(id).postValue(current);
        } else {
            log.get(id).setValue(current);
        }
    }
}
