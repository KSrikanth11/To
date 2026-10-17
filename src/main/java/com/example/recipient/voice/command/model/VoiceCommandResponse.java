package com.example.recipient.voice.command.model;

import java.util.List;

public record VoiceCommandResponse(String transcript, List<UiAction> actions) {
}
