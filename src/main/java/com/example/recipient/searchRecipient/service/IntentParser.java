package com.example.recipient.searchRecipient.service;

import com.example.recipient.searchRecipient.model.Intent;

public interface IntentParser {
    Intent parse(String query);
}
