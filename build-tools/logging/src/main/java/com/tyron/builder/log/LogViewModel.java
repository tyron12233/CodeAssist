package com.tyron.builder.log;

import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.tyron.builder.model.DiagnosticWrapper;

import javax.tools.Diagnostic;

import java.util.ArrayList;
import java.util.List;

public class LogViewModel extends ViewModel {

    private static int totalCount;
    public static final int APP_LOG = totalCount++;
    public static final int BUILD_LOG = totalCount++;
    public static final int DEBUG = totalCount++;
    public static final int IDE = totalCount++;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<MutableLiveData<List<DiagnosticWrapper>>> log;

    public LiveData<List<DiagnosticWrapper>> getLogs(int id) {
        if (log == null) {
            log = init();
        }
        return log.get(id);
    }

    public void updateLogs(int id, List<DiagnosticWrapper> diagnostics) {
        if (log == null) {
            log = init();
        }
        MutableLiveData<List<DiagnosticWrapper>> logData = this.log.get(id);
        logData.setValue(diagnostics);
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

    public void d(int id, String message) {
        d(id, wrap(message, Diagnostic.Kind.OTHER));
    }

    public void d(int id, DiagnosticWrapper diagnosticWrapper) {
        add(id, diagnosticWrapper);
    }

    public void w(int id, DiagnosticWrapper diagnosticWrapper) {
        add(id, diagnosticWrapper);
    }

    public void w(int id, String message) {
        add(id, wrap(message, Diagnostic.Kind.WARNING));
    }

    public void e(int id, String message) {
        add(id, wrap(message, Diagnostic.Kind.ERROR));
    }

    private DiagnosticWrapper wrap(String message, Diagnostic.Kind kind) {
        DiagnosticWrapper wrapper = new DiagnosticWrapper();
        wrapper.setMessage(message);
        wrapper.setKind(kind);
        return wrapper;
    }

    /**
     * Convenience method to add a diagnostic to a ViewModel
     *
     * @param id                the log id to set to
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
     *
     * @param id      log id to set the value to
     * @param current Value to set to the ViewModel
     */
    private void maybePost(int id, List<DiagnosticWrapper> current) {
        if (Thread.currentThread() != Looper.getMainLooper().getThread()) {
            // Using postValue will ignore all values except the last one, we don't want that
            mainHandler.post(() -> log.get(id).setValue(current));
        } else {
            log.get(id).setValue(current);
        }
    }
}
