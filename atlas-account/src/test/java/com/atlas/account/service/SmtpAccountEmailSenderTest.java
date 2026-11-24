package com.atlas.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.mockito.ArgumentCaptor;

class SmtpAccountEmailSenderTest {
    @Test
    void rejectsPlainHttpActionEndpointsOutsideLoopback() {
        JavaMailSender mailSender = mock(JavaMailSender.class);

        assertThrows(IllegalArgumentException.class, () -> new SmtpAccountEmailSender(
                mailSender, "no-reply@example.com", "http://accounts.example.com"));
    }

    @Test
    void allowsLoopbackHttpAndSendsActionInstructionsToConfiguredSecureOrigin() {
        JavaMailSender mailSender = mock(JavaMailSender.class);
        SmtpAccountEmailSender localSender = new SmtpAccountEmailSender(
                mailSender, "no-reply@localhost", "http://127.0.0.1:8080/");
        SmtpAccountEmailSender secureSender = new SmtpAccountEmailSender(
                mailSender, "no-reply@example.com", "https://accounts.example.com/");

        localSender.sendVerification("buyer@example.com", "local-token");
        secureSender.sendPasswordReset("buyer@example.com", "reset-token");

        ArgumentCaptor<SimpleMailMessage> messages = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender, times(2)).send(messages.capture());
        assertThat(messages.getAllValues().get(0).getText())
                .contains("http://127.0.0.1:8080/api/auth/verify-email")
                .contains("local-token");
        assertThat(messages.getAllValues().get(1).getText())
                .contains("https://accounts.example.com/api/auth/password-reset/confirm")
                .contains("reset-token");
    }
}
