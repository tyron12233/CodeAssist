package com.tyron.code.ui.file;

import android.os.Parcel;
import android.os.Parcelable;

import com.tyron.code.ui.file.dialog.CreateClassDialogFragment;

/**
 * Class that holds a Regex String and a reason if the regex doesn't match.
 *
 * This is used by {@link CreateClassDialogFragment} to check if the class name
 * is a valid class name, certain providers may have different name requirements
 * such as an xml file, in android xml files can only have lowercase letters and
 * an underscore.
 */
public class RegexReason implements Parcelable {

    private String regexString;

    private String reason;

    public RegexReason(String regexString, String reason) {
        this.regexString = regexString;
        this.reason = reason;
    }

    public String getRegexString() {
        return regexString;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(this.regexString);
        dest.writeString(this.reason);
    }

    public void readFromParcel(Parcel source) {
        this.regexString = source.readString();
        this.reason = source.readString();
    }

    protected RegexReason(Parcel in) {
        this.regexString = in.readString();
        this.reason = in.readString();
    }

    public static final Parcelable.Creator<RegexReason> CREATOR = new Parcelable.Creator<RegexReason>() {
        @Override
        public RegexReason createFromParcel(Parcel source) {
            return new RegexReason(source);
        }

        @Override
        public RegexReason[] newArray(int size) {
            return new RegexReason[size];
        }
    };
}
