package uk.ac.cam.tfc_server.feedmaker;

// General interface for all FeedParsers

import io.vertx.core.json.JsonObject;
import io.vertx.core.buffer.Buffer;

public interface FeedParser {

    // parse() reads a Buffer that may contain multiple data records of interest
    // and returns that data as a eventbus message format JsonObject
    JsonObject parse(Buffer buf) throws Exception;

}

