package ar.edu.itba.pdc.natto.server.handlers;

import java.io.IOException;

public interface AcceptHandler extends SelectorHandler {
    void handle_accept() throws IOException;
}
