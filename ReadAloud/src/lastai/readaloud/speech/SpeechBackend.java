package lastai.readaloud.speech;

/** A way to speak. All methods may block briefly; the Speech worker thread calls them, never the game thread. */
public interface SpeechBackend {
    String name();

    /** Starts speaking (replacing anything already being spoken). Throws if this backend can't speak. */
    void speak(SpeechRequest request) throws Exception;

    void stop();

    boolean isSpeaking();
}
