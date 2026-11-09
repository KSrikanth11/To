package com.example.recipient.AudioService;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class SonioxTranscriptParsingTest {
    private static JsonNode json(String value) throws Exception { return new ObjectMapper().readTree(value); }

    @Test void translatedSpeechKeepsBothTheEnglishAndTheOriginalWords() throws Exception {
        JsonNode transcript = json("""
                {"tokens":[
                  {"text":"నల్గొండ","language":"te","translation_status":"original"},
                  {"text":" SI","language":"en","translation_status":"original"},
                  {"text":" లకు పంపండి","language":"te","translation_status":"original"},
                  {"text":"Send to SIs in Nalgonda","language":"en","translation_status":"translation"}
                ]}""");
        assertEquals("Send to SIs in Nalgonda", SonioxTranscriptionService.englishTranscript(transcript));
        assertEquals("నల్గొండ SI లకు పంపండి", SonioxTranscriptionService.spokenTranscript(transcript));
        assertEquals("te", SonioxTranscriptionService.dominantLanguage(transcript));
    }

    @Test void englishSpeechIsItsOwnOriginal() throws Exception {
        JsonNode transcript = json("""
                {"tokens":[{"text":"All SHOs","language":"en","translation_status":"none"},
                           {"text":" in Cyberabad","language":"en","translation_status":"none"}]}""");
        assertEquals("All SHOs in Cyberabad", SonioxTranscriptionService.englishTranscript(transcript));
        assertEquals("All SHOs in Cyberabad", SonioxTranscriptionService.spokenTranscript(transcript));
        assertEquals("en", SonioxTranscriptionService.dominantLanguage(transcript));
    }

    @Test void missingLanguageMetadataIsUnknown() throws Exception {
        assertEquals("unknown", SonioxTranscriptionService.dominantLanguage(json("{\"tokens\":[{\"text\":\"hi\"}]}")));
        assertEquals("", SonioxTranscriptionService.spokenTranscript(json("{}")));
    }

    @Test void legacyConstructorHasNoOriginalSpeech() {
        var transcript = new SonioxTranscriptionService.Transcript("id", "Send to SIs");
        assertEquals("", transcript.originalText());
        assertEquals("unknown", transcript.language());
    }
}
