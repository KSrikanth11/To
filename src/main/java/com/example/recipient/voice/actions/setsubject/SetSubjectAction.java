package com.example.recipient.voice.actions.setsubject;

import org.springframework.stereotype.Component;

import com.example.recipient.voice.command.model.CommandIntent;
import com.example.recipient.voice.command.model.UiAction;

@Component
public class SetSubjectAction {
    public UiAction apply(CommandIntent command) {
        return UiAction.setField("SUBJECT", command.text() == null ? "" : command.text());
    }
}
