package com.tyron.builder.exception;

public class CompilationFailedException extends Exception {

	public CompilationFailedException(Exception exception) {
		super(exception);
	}

	public CompilationFailedException(String message) {
		super(message);
	}
}
