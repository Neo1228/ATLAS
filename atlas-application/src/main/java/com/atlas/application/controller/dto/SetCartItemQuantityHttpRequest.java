package com.atlas.application.controller.dto;

import jakarta.validation.constraints.Positive;

public record SetCartItemQuantityHttpRequest(@Positive int quantity) {
}
