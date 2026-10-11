package android.os.allowlist;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public abstract class AllowlistProviderService extends Service {
    public AllowlistProviderService() {}
    @Override public final IBinder onBind(Intent intent) { throw new RuntimeException("Stub"); }
    public abstract AllowlistResponse onQueryAllowlist(AllowlistRequest request);
}
