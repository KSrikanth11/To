package com.example.recipient.voice.actions.setmessage;

import org.springframework.stereotype.Component;

import com.example.recipient.voice.command.model.CommandIntent;
import com.example.recipient.voice.command.model.UiAction;

@Component
public class SetMessageAction {
    public UiAction apply(CommandIntent command) {
        return UiAction.setField("MESSAGE", command.text() == null ? "" : command.text());
    }
}
