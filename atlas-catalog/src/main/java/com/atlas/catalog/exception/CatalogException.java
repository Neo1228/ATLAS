package com.atlas.catalog.exception;

public class CatalogException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public enum Code { INVALID_REQUEST, NOT_FOUND, CONFLICT, PRODUCT_NOT_ORDERABLE }

    private final Code code;

    public CatalogException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
