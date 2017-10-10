package uk.ac.cam.tfc_server.feedmaker;

// General interface for all FeedParsers

import io.vertx.core.json.JsonObject;

public interface FeedParser {

    // parse() reads a String that may contain multiple data records of interest
    // and returns that data as a eventbus message format JsonObject
    JsonObject parse(String s);
}

