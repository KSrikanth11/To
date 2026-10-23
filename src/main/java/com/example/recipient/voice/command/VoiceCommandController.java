package com.example.recipient.voice.command;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.example.recipient.AudioService.AudioFileRejectedException;
import com.example.recipient.AudioService.SonioxException;
import com.example.recipient.AudioService.SonioxTranscriptionService;
import com.example.recipient.AudioService.TranscriptRejectedException;
import com.example.recipient.voice.command.model.ComposeFormState;
import com.example.recipient.voice.command.model.VoiceCommandContext;
import com.example.recipient.voice.command.model.VoiceCommandRequest;
import com.example.recipient.voice.command.model.VoiceCommandResponse;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/api/voice")
public class VoiceCommandController {
    private static final Logger log = LoggerFactory.getLogger(VoiceCommandController.class);
    private final CommandRouter router;
    private final SonioxTranscriptionService transcription;

    public VoiceCommandController(CommandRouter router, SonioxTranscriptionService transcription) {
        this.router = router;
        this.transcription = transcription;
    }

    /** Accept a transcript that has already been translated to the command language. */
    @PostMapping(value = "/command", consumes = "application/json")
    public VoiceCommandResponse command(@Valid @RequestBody VoiceCommandRequest request) {
        return routeTranscript(request.transcript(), request.form(), request.context());
    }

    /** Accept audio, translate it to English, then process it as a normal voice command. */
    @PostMapping(value = "/command", consumes = "multipart/form-data")
    public VoiceCommandResponse commandAudio(
            @RequestPart("audio") MultipartFile audio,
            @RequestPart(name = "form", required = false) @Valid ComposeFormState form,
            @RequestPart(name = "context", required = false) @Valid VoiceCommandContext context,
            @RequestParam(name = "language_hints", required = false) List<String> languageHints) {
        if (audio.isEmpty()) {
            throw new AudioFileRejectedException("Upload a non-empty audio file", false);
        }

        final SonioxTranscriptionService.Transcript result;
        try {
            result = transcription.transcribe(audio.getBytes(), audio.getOriginalFilename(), languageHints);
        } catch (IOException ex) {
            throw new SonioxException("Could not read the uploaded audio file", ex);
        }

        if (result.text().isBlank()) {
            log.warn("voice.audio.transcript.rejected chars={} reason=invalid_length", result.text().length());
            throw new TranscriptRejectedException("Audio transcript is empty or invalid");
        }
        log.info("voice.audio.transcript.ready Text==>{}", result.text());
        return routeTranscript(result.text(), form, context);
    }

    private VoiceCommandResponse routeTranscript(String transcript, ComposeFormState form,
                                                 VoiceCommandContext context) {
        return router.route(transcript, form == null ? ComposeFormState.empty() : form, context);
    }
}
