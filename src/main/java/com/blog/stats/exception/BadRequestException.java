package com.blog.stats.exception;

/** Paramètre ou corps invalide côté client : renvoyé en 400 avec ce message comme {@code detail}. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
