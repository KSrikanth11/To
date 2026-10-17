package com.example.recipient.AudioService;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

@Service
public class SonioxTranscriptionService {
    private static final Logger log = LoggerFactory.getLogger(SonioxTranscriptionService.class);
    private final RestClient client;
    private final String apiKey;
    private final String model;
    private final int maxWaitSeconds;
    private final long maxFileSizeBytes;

    public SonioxTranscriptionService(RestClient.Builder builder,
                                      @Value("${soniox.api-key:}") String apiKey,
                                      @Value("${soniox.model:stt-async-v5}") String model,
                                      @Value("${soniox.max-wait-seconds:90}") int maxWaitSeconds,
                                      @Value("${soniox.max-file-size-bytes:25000000}") long maxFileSizeBytes) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.client = builder.requestFactory(requestFactory).baseUrl("https://api.soniox.com/v1").build();
        this.apiKey = apiKey;
        this.model = model;
        this.maxWaitSeconds = Math.max(1, maxWaitSeconds);
        this.maxFileSizeBytes = maxFileSizeBytes;
        log.info("soniox.provider.configured model={} api_key_present={} max_wait_seconds={}",
                model, !apiKey.isBlank(), this.maxWaitSeconds);
    }

    public Transcript transcribe(byte[] audio, String originalFilename, List<String> languageHints) {
        if (apiKey.isBlank()) {
            log.error("soniox.request.rejected reason=missing_api_key");
            throw new SonioxException("Speech-to-text is not configured");
        }
        if (audio == null || audio.length == 0) throw new AudioFileRejectedException("Upload a non-empty audio file", false);
        if (audio.length > maxFileSizeBytes) throw new AudioFileRejectedException("Audio file exceeds the configured size limit", true);
        // This endpoint promises translation from any supported source language. A caller's
        // language_hints describes the spoken language, so passing `en` can bias recognition
        // toward English and is not a request for English output. Omit hints for auto-detection.
        log.info("soniox.upload.start filename={} bytes={} model={} language_hints={} language_detection=automatic translation_target=en",
                safeFilename(originalFilename), audio.length, model, languageHints);
        String uploadedFileId = null;
        String providerStage = "file_upload";
        boolean safeToDeleteFile = true;
        try {
            String filename = safeFilename(originalFilename);
            MultiValueMap<String, Object> filePart = new LinkedMultiValueMap<>();
            filePart.add("file", new ByteArrayResource(audio) {
                @Override public String getFilename() { return filename; }
            });
            JsonNode uploaded = client.post().uri("/files").contentType(MediaType.MULTIPART_FORM_DATA)
                    .header("Authorization", "Bearer " + apiKey).body(filePart).retrieve().body(JsonNode.class);
            uploadedFileId = uploaded.path("id").asText("");
            if (uploadedFileId.isBlank()) {
                uploadedFileId = null;
                throw new SonioxException("Speech-to-text provider did not accept the audio file");
            }
            log.info("soniox.upload.complete");

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("file_id", uploadedFileId);
            // Request token language metadata explicitly. Translation status remains the
            // authoritative distinction between source and translated text.
            payload.put("enable_language_identification", true);
            payload.put("translation", Map.of("type", "one_way", "target_language", "en"));

            providerStage = "create_transcription";
            JsonNode created = client.post().uri("/transcriptions").contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey).body(payload).retrieve().body(JsonNode.class);
            String id = created.path("id").asText("");
            if (id.isBlank()) throw new SonioxException("Speech-to-text provider returned no transcription ID");
            safeToDeleteFile = false;
            log.info("soniox.transcription.created polling_timeout_seconds={}", maxWaitSeconds);

            long deadline = System.nanoTime() + Duration.ofSeconds(maxWaitSeconds).toNanos();
            String lastState = null;
            while (System.nanoTime() < deadline) {
                providerStage = "poll_transcription";
                JsonNode status = client.get().uri("/transcriptions/{id}", id)
                        .header("Authorization", "Bearer " + apiKey).retrieve().body(JsonNode.class);
                String state = status.path("status").asText("");
                if (!state.equals(lastState)) {
                    log.info("soniox.transcription.status state={}", state.isBlank() ? "unknown" : state);
                    lastState = state;
                }
                if ("completed".equalsIgnoreCase(state)) {
                    safeToDeleteFile = true;
                    providerStage = "fetch_transcript";
                    JsonNode transcript = client.get().uri("/transcriptions/{id}/transcript", id)
                            .header("Authorization", "Bearer " + apiKey).retrieve().body(JsonNode.class);
                    logTranscriptTokens(transcript);
                    String text = englishTranscript(transcript);
                    if (text.isBlank()) {
                        log.warn("soniox.translation.missing target_language=en token_count={}",
                                transcript.path("tokens").isArray() ? transcript.path("tokens").size() : 0);
                        throw new SonioxException("Speech-to-text provider did not return an English transcript");
                    }
                    deleteTranscriptionQuietly(id);
                    log.info("soniox.transcription.complete transcription_id={} english_text=\"{}\"",
                            id, text);
                    return new Transcript(id, text);
                }
                if ("error".equalsIgnoreCase(state) || "failed".equalsIgnoreCase(state)) {
                    safeToDeleteFile = true;
                    log.warn("soniox.transcription.failed provider_state={} provider_error_type={}", state,
                            status.path("error_type").asText("unknown"));
                    throw new SonioxException("Speech-to-text transcription failed");
                }
                Thread.sleep(750);
            }
            log.warn("soniox.transcription.timeout timeout_seconds={}", maxWaitSeconds);
            throw new SonioxTimeoutException("Speech-to-text did not finish before the configured timeout");
        } catch (SonioxException | SonioxTimeoutException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("soniox.transcription.interrupted");
            throw new SonioxException("Speech-to-text request was interrupted", ex);
        } catch (RestClientResponseException ex) {
            log.warn("soniox.provider.error stage={} error_type={} http_status={}", providerStage,
                    ex.getClass().getSimpleName(), ex.getStatusCode().value());
            throw new SonioxException("Speech-to-text provider is temporarily unavailable", ex);
        } catch (RestClientException ex) {
            log.warn("soniox.provider.error stage={} error_type={}", providerStage, ex.getClass().getSimpleName());
            throw new SonioxException("Speech-to-text provider is temporarily unavailable", ex);
        } finally {
            if (uploadedFileId != null && safeToDeleteFile) deleteFileQuietly(uploadedFileId);
        }
    }

    private static void logTranscriptTokens(JsonNode transcript) {
        StringBuilder original = new StringBuilder();
        StringBuilder translated = new StringBuilder();
        JsonNode tokens = transcript.path("tokens");
        if (tokens.isArray()) {
            for (JsonNode token : tokens) {
                String status = token.path("translation_status").asText("");
                String tokenText = token.path("text").asText("");
                if ("translation".equalsIgnoreCase(status)) translated.append(tokenText);
                else if ("original".equalsIgnoreCase(status) || "none".equalsIgnoreCase(status)) original.append(tokenText);
            }
        }
        log.info("soniox.transcript.tokens original_text=\"{}\" translated_text=\"{}\" tokens={}",
                original, translated, tokens);
    }

    private void deleteFileQuietly(String fileId) {
        try {
            client.delete().uri("/files/{id}", fileId).header("Authorization", "Bearer " + apiKey)
                    .retrieve().toBodilessEntity();
            log.debug("soniox.cleanup.file.complete");
        } catch (RestClientException ex) {
            log.warn("soniox.cleanup.file.failed error_type={}", ex.getClass().getSimpleName());
        }
    }

    private void deleteTranscriptionQuietly(String transcriptionId) {
        try {
            client.delete().uri("/transcriptions/{id}", transcriptionId).header("Authorization", "Bearer " + apiKey)
                    .retrieve().toBodilessEntity();
            log.debug("soniox.cleanup.transcription.complete");
        } catch (RestClientException ex) {
            log.warn("soniox.cleanup.transcription.failed error_type={}", ex.getClass().getSimpleName());
        }
    }

    /** Use translation tokens for the requested English target, or already-English source tokens. */
    static String englishTranscript(JsonNode transcript) {
        JsonNode tokens = transcript.path("tokens");
        if (tokens.isArray()) {
            StringBuilder translated = new StringBuilder();
            StringBuilder alreadyEnglish = new StringBuilder();
            boolean hasTranslation = false;
            for (JsonNode token : tokens) {
                String status = token.path("translation_status").asText("");
                String language = token.path("language").asText("");
                if ("translation".equalsIgnoreCase(status)) {
                    hasTranslation = true;
                    // This request explicitly sets target_language=en, so trust Soniox's
                    // translation marker even when token language metadata is absent or varies.
                    translated.append(token.path("text").asText(""));
                } else if ("en".equalsIgnoreCase(language)) {
                    alreadyEnglish.append(token.path("text").asText(""));
                }
            }
            String result = (hasTranslation ? translated : alreadyEnglish).toString().trim();
            if (!result.isBlank()) return result;
            // A token stream is authoritative. Falling back to `text` could return the source
            // Telugu/Hindi/etc. transcript and pass it to the resolver as if translated.
            return "";
        }
        return "";
    }

    private static String safeFilename(String original) {
        if (original == null || original.isBlank()) return "audio.bin";
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._-]", "_");
        return name.isBlank() ? "audio.bin" : name.substring(0, Math.min(name.length(), 128));
    }

    public record Transcript(String transcriptionId, String text) { }
}
