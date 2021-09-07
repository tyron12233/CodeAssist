package com.tyron.code.ui.editor.log;

import androidx.lifecycle.ViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.LiveData;
import java.util.List;
import java.util.ArrayList;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.tyron.code.model.DiagnosticWrapper;

public class LogViewModel extends ViewModel {
    
    private static int totalCount;
    public static final int APP_LOG = totalCount++;
    public static final int BUILD_LOG = totalCount++;
    public static final int DEBUG = totalCount++;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<MutableLiveData<List<DiagnosticWrapper>>> log;
    
    public LiveData<List<DiagnosticWrapper>> getLogs(int id) {
        if (log == null) {
            log = init();
        }
        return log.get(id);
    }
    
    private List<MutableLiveData<List<DiagnosticWrapper>>> init() {
        List<MutableLiveData<List<DiagnosticWrapper>>> list = new ArrayList<>();
        for (int i = 0; i < totalCount; i++) {
            list.add(new MutableLiveData<>(new ArrayList<>()));
        }
        return list;
    }
    
    public void clear(int id) {
        MutableLiveData<List<DiagnosticWrapper>> data = (MutableLiveData<List<DiagnosticWrapper>>) getLogs(id);
        data.setValue(new ArrayList<>());
    }

    public void e(int id, DiagnosticWrapper diagnostic) {
        add(id, diagnostic);
    }

    public void d(int id, DiagnosticWrapper diagnosticWrapper) {
        add(id, diagnosticWrapper);
    }

    public void w(int id, DiagnosticWrapper diagnosticWrapper) {
        add(id, diagnosticWrapper);
    }


    /**
     * Convenience method to add a diagnostic to a ViewModel
     * @param id the log id to set to
     * @param diagnosticWrapper the DiagnosticWrapper to add
     */
    private void add(int id, DiagnosticWrapper diagnosticWrapper) {
        List<DiagnosticWrapper> list = getLogs(id).getValue();
        if (list == null) {
            list = new ArrayList<>();
        }
        list.add(diagnosticWrapper);
        maybePost(id, list);
    }

    /**
     * Checks if the current thread is the main thread and does not post it if so
     * @param id log id to set the value to
     * @param current Value to set to the ViewModel
     */
    private void maybePost(int id, List<DiagnosticWrapper> current) {
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            log.get(id).postValue(current);
        } else {
            log.get(id).setValue(current);
        }
    }
}
