package com.example.recipient.service;

public class SonioxException extends RuntimeException {
    public SonioxException(String message) { super(message); }
    public SonioxException(String message, Throwable cause) { super(message, cause); }
}
