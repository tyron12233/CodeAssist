package com.tyron.builder.internal.logging.events;

public interface OutputEventListener {
    void onOutput(OutputEvent event);

    OutputEventListener NO_OP = new OutputEventListener() {
        @Override
        public void onOutput(OutputEvent event) {

        }
    };
}