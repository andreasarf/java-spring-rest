package com.company.resourceapi.exceptions;

public class NotFoundException extends Exception {

    static final String MESSAGE = "%s %d not found.";

    public NotFoundException(Class cls, long id) {
        this(String.format(MESSAGE, cls.getSimpleName(), id));
    }

    public NotFoundException(String message) {
        super(message);
    }
}
