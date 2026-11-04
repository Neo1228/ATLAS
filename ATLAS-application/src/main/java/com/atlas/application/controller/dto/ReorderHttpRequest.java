package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotNull;

public record ReorderHttpRequest(@NotNull Boolean confirmExistingCartMerge) {
}
