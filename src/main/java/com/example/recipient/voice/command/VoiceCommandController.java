package com.example.recipient.voice.command;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.recipient.voice.command.model.VoiceCommandRequest;
import com.example.recipient.voice.command.model.VoiceCommandResponse;

import jakarta.validation.Valid;

@RestController
@Validated
@RequestMapping("/api/voice")
public class VoiceCommandController {
    private final CommandRouter router;

    public VoiceCommandController(CommandRouter router) {
        this.router = router;
    }

    @PostMapping("/command")
    public VoiceCommandResponse command(@Valid @RequestBody VoiceCommandRequest request) {
        return router.route(request.transcript(), request.form());
    }
}
