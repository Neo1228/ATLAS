package com.atlas.application.controller.dto;

import jakarta.validation.constraints.NotNull;

public record ReorderRequest(@NotNull Boolean confirmExistingCartMerge) {
}
