package com.example.routermanager.common;

/** The caller asked for something impossible; answered with 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
