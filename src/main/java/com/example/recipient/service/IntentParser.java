package com.example.recipient.service;

import com.example.recipient.model.Intent;

public interface IntentParser {
    Intent parse(String query);
}
