package com.raphael.beachcleanup.exceptions;

public class EmailConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EmailConflictException() {
        super("O e-mail informado já está cadastrado.");
    }
}
