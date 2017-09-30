package uk.ac.cam.tfc_server.feedmaker;

// General interface for all FeedParsers

import io.vertx.core.json.JsonArray;

public interface FeedParser {

    // parse_array reads a String that may contain multiple data records of interest
    // and returns that data as a JsonArray of JsonObjects
    JsonArray parse_array(String s);
}

