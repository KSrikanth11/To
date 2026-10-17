package com.example.recipient.AudioService;

public class AudioFileRejectedException extends RuntimeException {
    private final boolean tooLarge;
    public AudioFileRejectedException(String message, boolean tooLarge) { super(message); this.tooLarge = tooLarge; }
    public boolean isTooLarge() { return tooLarge; }
}
