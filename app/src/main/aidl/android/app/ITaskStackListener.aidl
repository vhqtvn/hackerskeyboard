package android.app;

/**
 * Minimal stand-in for the framework's @hide android.app.ITaskStackListener.
 * Only the first method (onTaskStackChanged, transaction code 0) is declared, so it
 * lines up with the real interface; every other callback the framework sends lands on
 * an unknown transaction and is dropped (all callbacks are oneway, so that's harmless).
 *
 * At runtime this generated interface/Stub is shadowed by the framework's real one
 * (parent-first class loading), so our listener IS-A framework ITaskStackListener.
 */
oneway interface ITaskStackListener {
    void onTaskStackChanged();
}
