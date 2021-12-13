package com.tyron.builder.exception;

public class CompilationFailedException extends Exception {

	public CompilationFailedException(String message, Throwable t) {
		super(message, t);
	}
	public CompilationFailedException(Exception exception) {
		super(exception);
	}

	public CompilationFailedException(String message) {
		super(message);
	}
}
