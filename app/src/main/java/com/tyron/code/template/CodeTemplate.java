package com.tyron.code.template;

import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;

/**
 * Class for creating different templates for classes such as an interface,
 * abstract or regular classes
 */
public class CodeTemplate implements Parcelable {

    public static final Parcelable.Creator<CodeTemplate> CREATOR = new Parcelable.Creator<CodeTemplate>() {
        @Override
        public CodeTemplate createFromParcel(Parcel parcel) {
            return new CodeTemplate(parcel);
        }

        @Override
        public CodeTemplate[] newArray(int i) {
            return new CodeTemplate[i];
        }
    };

    /**
     * Used to replace the template package name with the app's package name
     */
    public static final String PACKAGE_NAME = "${packageName}";

    public static final String CLASS_NAME = "${className}";

    protected String mContents;

    public CodeTemplate() {

    }

    public CodeTemplate(Parcel in) {
        mContents = in.readString();
    }

    public final String get() {
        setup();
        return mContents;
    }

    public final void setContents(String contents) {
        mContents = contents;
    }

    /**
     * Subclasses must call setContents();
     */
    public void setup() {

    }

    public String getName() {
        throw new IllegalStateException("getName() is not subclassed");
    }

    public String getExtension() {
        throw new IllegalStateException("getExtension() is not subclassed");
    }



    @NonNull
    @Override
    public String toString() {
        return getName();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(mContents);
    }
}
