package com.atlas.account.service.impl;

import com.atlas.account.service.AccountEmailSender;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
public class SmtpAccountEmailSender implements AccountEmailSender {
    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final String publicBaseUrl;

    public SmtpAccountEmailSender(JavaMailSender mailSender,
            @Value("${atlas.account.mail.from:no-reply@localhost}") String fromAddress,
            @Value("${atlas.account.mail.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.publicBaseUrl = validatePublicBaseUrl(publicBaseUrl);
    }

    @Override
    public void sendVerification(String email, String token) {
        send(email, "Verify your ATLAS account",
                "Verify your email address using this one-time token: " + token
                        + "\n\nSubmit POST " + publicBaseUrl + "/api/auth/verify-email with this token."
                        + "\nThe token expires after 24 hours.");
    }

    @Override
    public void sendPasswordReset(String email, String token) {
        send(email, "Reset your ATLAS password",
                "Reset your password using this one-time token: " + token
                        + "\n\nSubmit POST " + publicBaseUrl + "/api/auth/password-reset/confirm"
                        + " with the token and new password. The token expires after 30 minutes.");
    }

    private void send(String email, String subject, String text) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(fromAddress);
        message.setTo(email);
        message.setSubject(subject);
        message.setText(text + "\n\nAccount portal: " + publicBaseUrl);
        mailSender.send(message);
    }

    private static String validatePublicBaseUrl(String value) {
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host)
                || "::1".equals(host) || "[::1]".equals(host);
        boolean secure = "https".equalsIgnoreCase(scheme) || ("http".equalsIgnoreCase(scheme) && loopback);
        if (!secure || host == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Account email action base URL must use HTTPS outside loopback");
        }
        return uri.toString().replaceAll("/+$", "");
    }
}