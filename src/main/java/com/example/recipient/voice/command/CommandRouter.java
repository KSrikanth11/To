package com.example.recipient.voice.command;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.example.recipient.searchRecipient.model.ResolveRecipientResponse;
import com.example.recipient.searchRecipient.model.SpokenInput;
import com.example.recipient.voice.actions.searchRecipient.SearchRecipientsAction;
import com.example.recipient.voice.actions.setmessage.SetMessageAction;
import com.example.recipient.voice.actions.setsubject.SetSubjectAction;
import com.example.recipient.voice.command.model.CommandIntent;
import com.example.recipient.voice.command.model.ComposeFormState;
import com.example.recipient.voice.command.model.UiAction;
import com.example.recipient.voice.command.model.VoiceCommandContext;
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

    public VoiceCommandResponse route(String transcript, ComposeFormState currentForm) {
        return route(transcript, currentForm, VoiceCommandContext.empty());
    }

    public VoiceCommandResponse route(String transcript, ComposeFormState currentForm,
                                      VoiceCommandContext currentContext) {
        return route(transcript, currentForm, currentContext, null);
    }

    /** Routes a spoken command: {@code transcript} is the English translation, {@code spoken} the original words. */
    public VoiceCommandResponse route(String transcript, ComposeFormState currentForm,
                                      VoiceCommandContext currentContext, SpokenInput spoken) {
        ComposeFormState form = currentForm == null ? ComposeFormState.empty() : currentForm;
        VoiceCommandContext context = currentContext == null ? VoiceCommandContext.empty() : currentContext;
        List<CommandIntent> commands = spoken == null
                ? extractor.extractCommands(transcript, form, context)
                : extractor.extractCommands(transcript, form, context, spoken);
        // Keep compatibility with older extractor implementations and simple test doubles.
        if (commands == null || commands.isEmpty()) {
            CommandIntent legacy = extractor.extract(transcript, form);
            commands = legacy == null ? List.of() : List.of(legacy);
        }

        List<UiAction> actions = new ArrayList<>();
        ResolveRecipientResponse recipientResolution = null;
        boolean hadLowConfidenceCommand = false;

        for (CommandIntent command : commands) {
            if (command == null || command.intent() == CommandIntent.IntentType.UNKNOWN
                    || command.confidence() < MIN_CONFIDENCE) {
                hadLowConfidenceCommand = true;
                continue;
            }

            switch (command.intent()) {
                case SEARCH_RECIPIENTS -> {
                    recipientResolution = searchRecipients.resolve(transcript, command);
                    if (recipientResolution == null) {
                        actions.add(UiAction.clarifyRecipients("Recipients could not be searched. Please try again."));
                        break;
                    }

                    System.out.println("Recipient resolution: " + recipientResolution);
                    SearchRecipientsAction.Selection selection = searchRecipients.select(recipientResolution);
                    if (!selection.selected().isEmpty()) actions.add(UiAction.setRecipients(selection.selected()));
                    if (!selection.suggested().isEmpty()) actions.add(UiAction.suggestRecipients(selection.suggested()));
                    if (selection.clarification() != null) actions.add(UiAction.clarifyRecipients(selection.clarification()));
                    if ("ALL".equals(command.scope()) && selection.clarification() == null && !selection.selected().isEmpty()) {
                        form = new ComposeFormState(form.subject(), form.message(), form.labels(), selection.selected().size());
                    }
                }
                case SET_MESSAGE -> {
                    UiAction action = setMessage.apply(command);
                    actions.add(action);
                    form = new ComposeFormState(form.subject(), action.value(), form.labels(), form.recipientCount());
                }
                case APPEND_MESSAGE -> {
                    String addition = command.text() == null ? "" : command.text();
                    String message = form.message().isBlank() ? addition
                            : addition.isBlank() ? form.message() : form.message() + " " + addition;
                    form = new ComposeFormState(form.subject(), message, form.labels(), form.recipientCount());
                    actions.add(UiAction.setField("MESSAGE", message));
                }
                case SET_SUBJECT -> {
                    UiAction action = setSubject.apply(command);
                    actions.add(action);
                    form = new ComposeFormState(action.value(), form.message(), form.labels(), form.recipientCount());
                }
                case ADD_LABEL -> command.labels().forEach(label -> actions.add(UiAction.updateLabels("ADD", label)));
                case REMOVE_LABEL -> command.labels().forEach(label -> actions.add(UiAction.updateLabels("REMOVE", label)));
                case CLEAR_FIELD -> {
                    String field = command.field();
                    if ("SUBJECT".equals(field)) {
                        form = new ComposeFormState("", form.message(), form.labels(), form.recipientCount());
                    } else if ("MESSAGE".equals(field)) {
                        form = new ComposeFormState(form.subject(), "", form.labels(), form.recipientCount());
                    } else if ("LABELS".equals(field)) {
                        form = new ComposeFormState(form.subject(), form.message(), List.of(), form.recipientCount());
                    } else if ("RECIPIENTS".equals(field)) {
                        form = new ComposeFormState(form.subject(), form.message(), form.labels(), 0);
                    }
                    actions.add(new UiAction("CLEAR_FIELD", null, field, null, "CLEAR", null));
                }
                case SEND -> actions.add(UiAction.requestSendConfirmation());
                case UNKNOWN -> hadLowConfidenceCommand = true;
            }
        }

        if (actions.isEmpty()) actions.add(UiAction.notUnderstood("Please repeat"));
        else if (hadLowConfidenceCommand) actions.add(UiAction.notUnderstood("Some part of the command was unclear"));
        return new VoiceCommandResponse(transcript, form, recipientResolution, List.copyOf(actions),
                context.append(transcript));
    }
}
