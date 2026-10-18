package com.raphael.beachcleanup.exceptions;
public class TooManyAttemptsException extends RuntimeException {
    public final long retryAfter;
    public TooManyAttemptsException(long retryAfter) { super("Muitas tentativas. Aguarde e tente novamente."); this.retryAfter = retryAfter; }
}
