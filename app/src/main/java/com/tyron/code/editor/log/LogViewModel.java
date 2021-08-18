package com.tyron.code.editor.log;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.LiveData;
import java.util.List;
import java.util.ArrayList;
import android.os.Looper;

public class LogViewModel extends ViewModel {
    
    private static int totalCount;
    public static final int APP_LOG = totalCount++;
    public static final int BUILD_LOG = totalCount++;
    
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
            current = current + '\n' + message;
        }
        
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
           log.get(id).postValue(current);
        } else {
           log.get(id).setValue(current);
        }
    }
    
    public void clear(int id) {
        MutableLiveData<String> data = (MutableLiveData<String>) getLogs(id);
        data.setValue("");
    }
}
