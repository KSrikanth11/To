package com.example.recipient.controller;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.recipient.model.ResolveAudioResponse;
import com.example.recipient.model.ResolveRecipientRequest;
import com.example.recipient.model.ResolveRecipientResponse;
import com.example.recipient.service.RecipientResolverService;
import com.example.recipient.service.RecipientSearchIndex;
import com.example.recipient.service.RecipientSearchIndex.IndexStatus;
import com.example.recipient.service.SonioxException;
import com.example.recipient.service.SonioxTranscriptionService;
import com.example.recipient.service.TranscriptRejectedException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

@RestController
@Validated
@RequestMapping("/api/v1/recipients")
public class RecipientController {
    private static final Logger log = LoggerFactory.getLogger(RecipientController.class);
    private final RecipientResolverService resolver;
    private final SonioxTranscriptionService transcription;

    @Autowired
    public RecipientController(RecipientResolverService resolver, SonioxTranscriptionService transcription) {
        this.resolver = resolver; this.transcription = transcription;
    }

    /** Retained for tests or callers that used the earlier constructor. */
    public RecipientController(RecipientResolverService resolver, SonioxTranscriptionService transcription,
                               RecipientSearchIndex ignoredIndex) {
        this(resolver, transcription);
    }

    @PostMapping("/resolve")
    public ResolveRecipientResponse resolve(@Valid @RequestBody ResolveRecipientRequest request) { return resolver.resolve(request); }

    @PostMapping(value = "/resolve-audio", consumes = "multipart/form-data")
    public ResolveAudioResponse resolveAudio(
            @RequestPart("audio") MultipartFile audio,
            @RequestParam(name = "language_hints", required = false) List<String> languageHints,
            @RequestParam(name = "listId", required = false) @Min(1) Long listId,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int offset) {
        if (audio.isEmpty()) {
            log.warn("audio.request.rejected reason=empty_upload");
            throw new TranscriptRejectedException("Upload a non-empty audio file");
        }
        log.info("audio.request.received filename={} bytes={} language_hints={} list_id={} limit={} offset={}",
                audio.getOriginalFilename(), audio.getSize(), languageHints, listId, limit, offset);
        SonioxTranscriptionService.Transcript transcript;

        try {
            transcript = transcription.transcribe(audio.getBytes(), audio.getOriginalFilename(), languageHints);
        } catch (IOException ex) {
            throw new SonioxException("Could not read the uploaded audio file", ex);
        }
        if (transcript.text().isBlank() || transcript.text().length() > 500) {
            log.warn("audio.transcript.rejected chars={} reason=invalid_length", transcript.text().length());
            throw new TranscriptRejectedException("Audio transcript must contain 1 to 500 characters");
        }
        log.info("audio.transcript.ready transcription_id={} english_query=\"{}\"",
                transcript.transcriptionId(), transcript.text());
        ResolveRecipientRequest textRequest = new ResolveRecipientRequest(
                transcript.text(), limit, offset, listId);
        return new ResolveAudioResponse(transcript.text(), transcript.transcriptionId(), resolver.resolve(textRequest));
    }

    /** Retained for direct calls using the previous signature; multipart HTTP requests may include listId. */
    public ResolveAudioResponse resolveAudio(MultipartFile audio, List<String> languageHints, int limit, int offset) {
        return resolveAudio(audio, languageHints, null, limit, offset);
    }

    @PostMapping("/index/reload")
    public IndexStatus reloadIndex() { return resolver.reloadSource(); }
}
