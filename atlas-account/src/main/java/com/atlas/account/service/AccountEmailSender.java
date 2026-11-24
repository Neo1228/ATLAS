package com.atlas.account.service;

public interface AccountEmailSender {
    void sendVerification(String email, String token);
    void sendPasswordReset(String email, String token);
}