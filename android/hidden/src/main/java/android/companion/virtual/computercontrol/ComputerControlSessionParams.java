package android.companion.virtual.computercontrol;

import android.app.AppInteractionAttribution;
import android.app.Notification;
import android.app.PendingIntent;
import java.util.List;

public final class ComputerControlSessionParams {
    private ComputerControlSessionParams() {}

    public static final class Builder {
        public Builder() {}
        public Builder setName(String name) { throw new RuntimeException("Stub"); }
        public Builder setTargetPackageNames(List<String> packageNames) { throw new RuntimeException("Stub"); }
        public Builder setTargetComputerControlVersion(int version) { throw new RuntimeException("Stub"); }
        public Builder setPreviewIntent(PendingIntent intent) { throw new RuntimeException("Stub"); }
        public Builder setAppInteractionAttribution(AppInteractionAttribution attribution) { throw new RuntimeException("Stub"); }
        public Builder setNotificationParams(NotificationParams params) { throw new RuntimeException("Stub"); }
        public ComputerControlSessionParams build() { throw new RuntimeException("Stub"); }
    }

    public static final class NotificationParams {
        private NotificationParams() {}

        public static final class Builder {
            public Builder(Notification notification, int notificationId) {}
            public NotificationParams build() { throw new RuntimeException("Stub"); }
        }
    }
}
