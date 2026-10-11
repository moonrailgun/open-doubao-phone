package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;

/** A package name of "*" matches every package. */
public final class SignedPackage implements Parcelable {
    public SignedPackage(String packageName, byte[] certificateDigest) {}
    @Override public int describeContents() { throw new RuntimeException("Stub"); }
    @Override public void writeToParcel(Parcel dest, int flags) { throw new RuntimeException("Stub"); }
}
