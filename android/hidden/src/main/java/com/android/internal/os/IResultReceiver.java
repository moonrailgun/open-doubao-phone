package com.android.internal.os;

import android.os.Bundle;

public interface IResultReceiver extends android.os.IInterface {
    void send(int resultCode, Bundle resultData) throws android.os.RemoteException;

    abstract class Stub extends android.os.Binder implements IResultReceiver {
        public Stub() {}
        @Override public android.os.IBinder asBinder() { return this; }
    }
}
