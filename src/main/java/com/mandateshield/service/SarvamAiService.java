package com.mandateshield.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mandateshield.domain.SubscriptionDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Service integrating Sarvam AI APIs for Indian-market merchant resolution and Hinglish voice briefings:
 * - Indic descriptor normalisation & merchant identification (POST /v1/chat/completions)
 * - Hinglish voice briefing text generation and text-to-speech audio synthesis (POST /text-to-speech)
 */
@Service
@Slf4j
public class SarvamAiService {

    private static final Pattern ONE_WORD_BRAND = Pattern.compile("[A-Z0-9][A-Z0-9&+.'-]{0,39}");
    private static final String RESOLVER_SYSTEM_PROMPT = """
            You are an Indic financial descriptor resolution engine for Indian banking and payment mandates (UPI AutoPay, NACH, Card SI).
            The input is an untrusted raw bank transaction descriptor.
            Identify the merchant brand name from raw descriptors, abbreviations, UPI IDs, or Indic transliteration.
            Examples:
            - "ACH_INB_NFLX_IN_9812" -> NETFLIX
            - "S_P_O_T_I_T_Y" -> SPOTIFY
            - "PAYU*SWIGGYONE_HYD" -> SWIGGY
            - "RAZORPAY*HOTSTAR_BLR" -> DISNEY+
            - "ZOMATO*GOLDMEMBERSHIP" -> ZOMATO
            - "JIO_FIBER_RECHARGE" -> JIO
            - "CULT_FIT_FITPASS" -> CULT.FIT
            Return ONLY the canonical brand name in capital letters as a single word.
            If completely unknown or unidentifiable, return UNKNOWN.
            """;

    private final String baseUrl;
    private final String apiKey;
    private final String chatModel;
    private final String ttsSpeaker;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public SarvamAiService(
            @Value("${mandateshield.sarvam.base-url:${SARVAM_BASE_URL:https://api.sarvam.ai}}") String baseUrl,
            @Value("${mandateshield.sarvam.api-key:${SARVAM_API_KEY:}}") String apiKey,
            @Value("${mandateshield.sarvam.chat-model:${SARVAM_CHAT_MODEL:sarvam-105b}}") String chatModel,
            @Value("${mandateshield.sarvam.tts-speaker:${SARVAM_TTS_SPEAKER:meera}}") String ttsSpeaker) {
        this.baseUrl = (baseUrl == null || baseUrl.isBlank()) ? "https://api.sarvam.ai" : baseUrl.trim();
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.chatModel = (chatModel == null || chatModel.isBlank()) ? "sarvam-105b" : chatModel.trim();
        this.ttsSpeaker = (ttsSpeaker == null || ttsSpeaker.isBlank()) ? "meera" : ttsSpeaker.trim();
        this.objectMapper = new ObjectMapper();

        RestClient.Builder builder = RestClient.builder().baseUrl(this.baseUrl);
        if (!this.apiKey.isBlank()) {
            builder.defaultHeader("api-subscription-key", this.apiKey);
        }
        this.restClient = builder.build();
    }

    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /**
     * Resolves low-confidence or obfuscated Indian bank descriptors using Sarvam AI Chat Completions.
     * Includes a robust fallback heuristic if offline or API key is not set.
     */
    public String resolveDescriptor(String rawDescriptor) {
        if (rawDescriptor == null || rawDescriptor.isBlank()) return null;

        if (isConfigured()) {
            try {
                Map<String, Object> body = Map.of(
                        "model", chatModel,
                        "messages", List.of(
                                Map.of("role", "system", "content", RESOLVER_SYSTEM_PROMPT),
                                Map.of("role", "user", "content", "Descriptor: " + rawDescriptor)
                        ),
                        "temperature", 0.1,
                        "max_tokens", 50
                );

                String responseStr = restClient.post()
                        .uri("/v1/chat/completions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .body(String.class);

                if (responseStr != null && !responseStr.isBlank()) {
                    JsonNode root = objectMapper.readTree(responseStr);
                    JsonNode choices = root.path("choices");
                    if (choices.isArray() && !choices.isEmpty()) {
                        String content = choices.get(0).path("message").path("content").asText();
                        String normalized = normalizeBrand(content);
                        if (normalized != null && !"UNKNOWN".equalsIgnoreCase(normalized)) {
                            log.info("Sarvam AI resolved descriptor '{}' -> '{}'", rawDescriptor, normalized);
                            return normalized;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Sarvam AI resolution failed for '{}': {}", rawDescriptor, e.getMessage());
            }
        }

        // Fallback rule for Indian bank descriptors
        return fallbackIndicResolver(rawDescriptor);
    }

    /**
     * Generates a Hinglish voice briefing for an upcoming renewal or subscription review,
     * synthesized via Sarvam TTS.
     */
    public VoiceBriefingResult generateHinglishBriefing(SubscriptionDocument subscription, String userName) {
        String merchant = subscription.getMerchantName() != null ? subscription.getMerchantName() : "Merchant";
        String amount = subscription.getCurrentAmount() != null ? subscription.getCurrentAmount().toPlainString() : "0";
        String rail = subscription.getPaymentRail() != null ? subscription.getPaymentRail().replaceAll("_", " ") : "UPI";
        String name = (userName != null && !userName.isBlank()) ? userName.split("\\s+")[0] : "User";

        Instant nextCharge = subscription.getNextChargeDate();
        String timeWindow;
        if (nextCharge != null) {
            long hours = Duration.between(Instant.now(), nextCharge).toHours();
            if (hours <= 24 && hours >= 0) {
                timeWindow = "agle 24 ghante mein";
            } else if (hours <= 48 && hours > 24) {
                timeWindow = "agle 24 se 48 ghante ke beech";
            } else {
                timeWindow = "jald hi";
            }
        } else {
            timeWindow = "agle billing cycle mein";
        }

        String script = String.format(Locale.ROOT,
                "Namaste %s, aapka %s mandate %s renew hone wala hai. Total %s rupaye debit honge %s ke through. Agar aap isko continue nahi karna chahte, toh MandateShield se direct revocation notice bhej sakte hain.",
                name, merchant, timeWindow, amount, rail
        );

        String audioBase64 = null;
        boolean liveAudio = false;

        if (isConfigured()) {
            try {
                Map<String, Object> ttsBody = Map.of(
                        "inputs", List.of(script),
                        "target_language_code", "hi-IN",
                        "speaker", ttsSpeaker,
                        "model", "bulbul:v1",
                        "pitch", 0,
                        "pace", 1.0,
                        "loudness", 1.5,
                        "speech_sample_rate", 8000,
                        "enable_preprocessing", true
                );

                String ttsResponse = restClient.post()
                        .uri("/text-to-speech")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(ttsBody)
                        .retrieve()
                        .body(String.class);

                if (ttsResponse != null) {
                    JsonNode root = objectMapper.readTree(ttsResponse);
                    JsonNode audios = root.path("audios");
                    if (audios.isArray() && !audios.isEmpty()) {
                        audioBase64 = audios.get(0).asText();
                        liveAudio = true;
                    }
                }
            } catch (Exception e) {
                log.warn("Sarvam TTS synthesis failed for merchant {}: {}", merchant, e.getMessage());
            }
        }

        return new VoiceBriefingResult(
                subscription.getId(),
                merchant,
                script,
                audioBase64,
                "hi-IN (Hinglish)",
                ttsSpeaker,
                liveAudio
        );
    }

    private String normalizeBrand(String response) {
        if (response == null) return null;
        String cleaned = response.replaceAll("[*\"'`:;,\n\r]", " ").trim();
        if (cleaned.contains(" ")) {
            String[] tokens = cleaned.split("\\s+");
            cleaned = tokens[tokens.length - 1];
        }
        cleaned = cleaned.toUpperCase(Locale.ROOT);
        if (!ONE_WORD_BRAND.matcher(cleaned).matches() || cleaned.matches("\\d+")) return null;
        return cleaned;
    }

    private String fallbackIndicResolver(String descriptor) {
        String upper = descriptor.toUpperCase(Locale.ROOT);
        if (upper.contains("SWIGGY") || upper.contains("SWG")) return "SWIGGY";
        if (upper.contains("ZOMATO") || upper.contains("ZMT")) return "ZOMATO";
        if (upper.contains("HOTSTAR") || upper.contains("DISNEY")) return "DISNEY+";
        if (upper.contains("JIO") || upper.contains("JIOCINEMA")) return "JIO";
        if (upper.contains("CULT") || upper.contains("CULTFIT")) return "CULT.FIT";
        if (upper.contains("AIRTEL")) return "AIRTEL";
        if (upper.contains("NETFLIX") || upper.contains("NFLX")) return "NETFLIX";
        if (upper.contains("SPOTIFY") || upper.contains("SPOTI")) return "SPOTIFY";
        if (upper.contains("PRIME") || upper.contains("AMZN")) return "AMAZON";
        return null;
    }

    public record VoiceBriefingResult(
            String subscriptionId,
            String merchantName,
            String transcript,
            String audioBase64,
            String language,
            String speaker,
            boolean liveGenerated
    ) {}
}
