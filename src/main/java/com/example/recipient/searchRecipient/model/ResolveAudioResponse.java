package com.example.recipient.searchRecipient.model;

/**
 * @param transcript          English transcript from Soniox (what the resolver searched with)
 * @param original_transcript the words as spoken, before Soniox translated them
 * @param detected_language   language Soniox identified for the speech
 */
public record ResolveAudioResponse(String transcript, String soniox_transcription_id,
                                   ResolveRecipientResponse recipient_resolution,
                                   String original_transcript, String detected_language) {
    public ResolveAudioResponse(String transcript, String soniox_transcription_id,
                                ResolveRecipientResponse recipient_resolution) {
        this(transcript, soniox_transcription_id, recipient_resolution, transcript, "unknown");
    }
}
