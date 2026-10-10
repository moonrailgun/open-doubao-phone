package android.app;

public final class AppInteractionAttribution {
    public static final int INTERACTION_TYPE_USER_QUERY = 1;
    private AppInteractionAttribution() {}

    public static final class Builder {
        public Builder(int interactionType) {}
        public AppInteractionAttribution build() { throw new RuntimeException("Stub"); }
    }
}
