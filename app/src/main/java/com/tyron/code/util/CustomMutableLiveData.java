package com.tyron.code.util;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import java.lang.reflect.Field;

/**
 * A {@link LiveData} class which supports updating values but not notifying them.
 * @param <T> The object this live data holds
 */
public class CustomMutableLiveData<T> extends MutableLiveData<T> {

    public CustomMutableLiveData() {
        super();
    }

    public CustomMutableLiveData(T value) {
        super(value);
    }

    @Override
    public void setValue(T value) {
        super.setValue(value);
    }

    public void setValue(T value, boolean notify) {
        if (notify) {
            setValue(value);
        } else {
            setValueInternal(value);
        }
    }

    private void setValueInternal(T value) {
        try {
            Field mData = LiveData.class.getDeclaredField("mData");
            mData.setAccessible(true);
            mData.set(this, value);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private void incrementVersion() {
        try {
            Field mVersion = LiveData.class.getDeclaredField("mVersion");
            mVersion.setAccessible(true);
            Integer o = (Integer) mVersion.get(this);
            if (o == null) {
                o = 0;
            }
            mVersion.set(this, o + 1);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }
}
