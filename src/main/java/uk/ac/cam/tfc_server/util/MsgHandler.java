package uk.ac.cam.tfc_server.util;

// MsgHandler.java
//
// Defines MsgHandler interface, to synchonously handle messages from tfc_server classes.
//
// This is implemented to allow classes (such as Zones) to be called in asynchronous
// Verticals as well as synchronously by BatcherWorker.

import io.vertx.core.json.JsonObject;

public interface MsgHandler {
    public void handle_msg(JsonObject msg, String address);
}

