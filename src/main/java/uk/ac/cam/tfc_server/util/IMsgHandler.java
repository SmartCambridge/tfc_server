package uk.ac.cam.tfc_server.util;

// IMsgHandler.java
//
// Defines IMsgHandler interface, to synchonously handle messages from tfc_server classes.
//
// This is implemented to allow classes (such as Zones) to be called in asynchronous
// Verticals as well as synchronously by BatcherWorker.

import io.vertx.core.json.JsonObject;

public interface IMsgHandler {
    public void handle_msg(JsonObject msg, String address);
}

