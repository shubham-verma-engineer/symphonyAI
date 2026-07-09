package com.symphony.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class PiiFirewallService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,16}\\b");
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FirewallResult {
        private String maskedText;
        private Map<String, String> tokenMap;
        private boolean sanitized;
    }

    public FirewallResult sanitize(String rawText) {
        if (rawText == null || rawText.trim().isEmpty()) {
            return FirewallResult.builder()
                    .maskedText(rawText)
                    .tokenMap(new HashMap<>())
                    .sanitized(false)
                    .build();
        }

        Map<String, String> tokens = new HashMap<>();
        String currentText = rawText;
        boolean sanitized = false;

        // 1. Mask Email Addresses
        Matcher emailMatcher = EMAIL_PATTERN.matcher(currentText);
        int emailIdx = 1;
        while (emailMatcher.find()) {
            String email = emailMatcher.group();
            String placeholder = "[MASKED_EMAIL_" + emailIdx++ + "]";
            tokens.put(placeholder, email);
            currentText = currentText.replace(email, placeholder);
            sanitized = true;
            emailMatcher = EMAIL_PATTERN.matcher(currentText); // reset matcher with updated string
        }

        // 2. Mask Credit Cards
        Matcher cardMatcher = CREDIT_CARD_PATTERN.matcher(currentText);
        int cardIdx = 1;
        while (cardMatcher.find()) {
            String card = cardMatcher.group();
            String placeholder = "[MASKED_CARD_" + cardIdx++ + "]";
            tokens.put(placeholder, card);
            currentText = currentText.replace(card, placeholder);
            sanitized = true;
            cardMatcher = CREDIT_CARD_PATTERN.matcher(currentText);
        }

        // 3. Mask SSNs
        Matcher ssnMatcher = SSN_PATTERN.matcher(currentText);
        int ssnIdx = 1;
        while (ssnMatcher.find()) {
            String ssn = ssnMatcher.group();
            String placeholder = "[MASKED_SSN_" + ssnIdx++ + "]";
            tokens.put(placeholder, ssn);
            currentText = currentText.replace(ssn, placeholder);
            sanitized = true;
            ssnMatcher = SSN_PATTERN.matcher(currentText);
        }

        if (sanitized) {
            log.info("PII Firewall triggered: masked {} sensitive elements", tokens.size());
        }

        return FirewallResult.builder()
                .maskedText(currentText)
                .tokenMap(tokens)
                .sanitized(sanitized)
                .build();
    }

    public String detokenize(String maskedText, Map<String, String> tokenMap) {
        if (maskedText == null || tokenMap == null || tokenMap.isEmpty()) {
            return maskedText;
        }
        String restored = maskedText;
        for (Map.Entry<String, String> entry : tokenMap.entrySet()) {
            restored = restored.replace(entry.getKey(), entry.getValue());
        }
        return restored;
    }
}
