package uk.ac.cam.tfc_server.feedmaker;

//***********************************************************************************************
//***********************************************************************************************
//   ParseFeedGTFS.java
//
//   Read the POSTed Google protobuf GTFS data, store to file, and broadcast on eventbus as Json
//
//   Author: ijl20
//
//***********************************************************************************************
//***********************************************************************************************

//
// Note that a FeedParser (like this one) ALWAYS returns a Json Array, even for a single record
//

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseFeedGTFS implements FeedParser {

    private String area_id;

    private JsonObject config;

    private Log logger;
    
    ParseFeedGTFS(JsonObject config, Log logger)
    {
       this.config = config;

       this.area_id = config.getString("area_id","");

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseFeedGTFS started");
    }

    // Here is where we try and parse the page into a JsonObject
    public JsonObject parse(Buffer buf)
    {
        logger.log(Constants.LOG_DEBUG, "ParseFeedGTFS.parse() called");

        return new JsonObject(); // data from feed is returned as a JsonObject
    }

} // end ParseFeedGTFS

