package com.example.recipient.voice.command;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.recipient.voice.actions.searchRecipient.SearchRecipientsAction;
import com.example.recipient.voice.actions.setmessage.SetMessageAction;
import com.example.recipient.voice.actions.setsubject.SetSubjectAction;
import com.example.recipient.voice.command.model.CommandIntent;
import com.example.recipient.voice.command.model.ComposeFormState;
import com.example.recipient.voice.command.model.UiAction;
import com.example.recipient.voice.command.model.VoiceCommandResponse;

@Service
public class CommandRouter {
    private static final double MIN_CONFIDENCE = 0.6;
    private final IntentExtractor extractor;
    private final SearchRecipientsAction searchRecipients;
    private final SetMessageAction setMessage;
    private final SetSubjectAction setSubject;

    public CommandRouter(IntentExtractor extractor, SearchRecipientsAction searchRecipients,
                         SetMessageAction setMessage, SetSubjectAction setSubject) {
        this.extractor = extractor;
        this.searchRecipients = searchRecipients;
        this.setMessage = setMessage;
        this.setSubject = setSubject;
    }

    public VoiceCommandResponse route(String transcript, ComposeFormState form) {
        CommandIntent command = extractor.extract(transcript, form);
        if (command.intent() == CommandIntent.IntentType.UNKNOWN || command.confidence() < MIN_CONFIDENCE) {
            return new VoiceCommandResponse(transcript, List.of(UiAction.notUnderstood("Please repeat")));
        }

        UiAction action = switch (command.intent()) {
            case SEARCH_RECIPIENTS -> searchRecipients.apply(transcript, command);
            case SET_MESSAGE -> setMessage.apply(command);
            case SET_SUBJECT -> setSubject.apply(command);
            default -> UiAction.notUnderstood("Please repeat");
        };
        return new VoiceCommandResponse(transcript, List.of(action));
    }
}
