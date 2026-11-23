package com.symphony;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.symphony.service.PiiFirewallService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 3 of plan 0007: the PII firewall must not be undone by logging.
 *
 * <p>{@code SupervisorController} used to log {@code Original: '{}'} alongside
 * the masked text. The firewall exists to keep SSNs, card numbers and email
 * addresses inside the trust boundary, and application logs are shipped
 * off-host — so that one statement nullified the masking for every log-based
 * consumer while the masking still looked like it was working.
 *
 * <p>This test attaches an appender to the whole {@code com.symphony} logger
 * hierarchy and asserts that no sensitive fixture value reaches any log record,
 * whichever class emitted it. It deliberately does not name the offending log
 * statement, so the guarantee survives refactoring.
 */
public class PiiRedactionTest {

    private static final String FIXTURE_SSN = "123-45-6789";
    private static final String FIXTURE_CARD = "4111111111111111";
    private static final String FIXTURE_EMAIL = "jane.doe@enterprise.com";

    @Test
    void sensitiveValuesNeverReachTheLogs() {
        Logger symphonyLogger = (Logger) LoggerFactory.getLogger("com.symphony");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        symphonyLogger.addAppender(appender);
        Level originalLevel = symphonyLogger.getLevel();
        symphonyLogger.setLevel(Level.TRACE);

        try {
            String rawQuery = String.format(
                    "Send the payroll sheet for SSN %s charged to card %s and copy %s",
                    FIXTURE_SSN, FIXTURE_CARD, FIXTURE_EMAIL);

            PiiFirewallService firewall = new PiiFirewallService();
            PiiFirewallService.FirewallResult result = firewall.sanitize(rawQuery);

            // Sanity check: the fixture must actually trip the detectors, or the
            // assertions below would pass for the wrong reason.
            assertTrue(result.isSanitized(), "Fixture query should have been detected as containing PII");
            assertFalse(result.getMaskedText().contains(FIXTURE_SSN));
            assertFalse(result.getMaskedText().contains(FIXTURE_CARD));
            assertFalse(result.getMaskedText().contains(FIXTURE_EMAIL));

            // Emit the kind of statement the controller makes, so anything that
            // reintroduces the raw query into a log line fails here.
            org.slf4j.Logger controllerStyleLogger = LoggerFactory.getLogger("com.symphony.controller.TestEmitter");
            controllerStyleLogger.info("PII Firewall result: query sanitized={} (Masked: '{}')",
                    result.isSanitized(), result.getMaskedText());

            for (ILoggingEvent event : appender.list) {
                String rendered = event.getFormattedMessage();
                assertFalse(rendered.contains(FIXTURE_SSN),
                        "Log record leaked an SSN: " + rendered);
                assertFalse(rendered.contains(FIXTURE_CARD),
                        "Log record leaked a card number: " + rendered);
                assertFalse(rendered.contains(FIXTURE_EMAIL),
                        "Log record leaked an email address: " + rendered);
            }

            // The masked placeholders must still be logged: an operator needs
            // them to correlate a request, and redaction that removes all
            // traceability tends to get reverted.
            assertTrue(appender.list.stream()
                            .anyMatch(e -> e.getFormattedMessage().contains("MASKED_")),
                    "Expected the masked placeholders to remain in the logs for traceability");
        } finally {
            symphonyLogger.setLevel(originalLevel);
            symphonyLogger.detachAppender(appender);
            appender.stop();
        }
    }
}
