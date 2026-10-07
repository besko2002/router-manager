package com.example.routermanager.common;

/** The addressed thing does not exist; answered with 404. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
