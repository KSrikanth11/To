package com.example.recipient.model;

public record ResolveAudioResponse(String transcript, String soniox_transcription_id,
                                   ResolveRecipientResponse recipient_resolution) { }
