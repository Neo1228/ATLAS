package com.atlas.inventory;

public final class InventoryUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public InventoryUnavailableException(String message) {
        super(message);
    }
}
