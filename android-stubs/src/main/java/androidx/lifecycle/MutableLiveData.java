package androidx.lifecycle;

import java.util.ArrayList;

public class MutableLiveData<T> extends LiveData<T> {

    public MutableLiveData(T objects) {
        super();
    }

    public void setValue(T value) {
        throw new RuntimeException("Stub!");
    }
}
