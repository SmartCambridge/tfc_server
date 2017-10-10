package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseFeedEventbusMsg.java
//
//   Read the Eventbus Message read from the http feed as Json
//**********************************************************************
//**********************************************************************


// Expects the http message received to be a previous EventBus message from
// the Adaptive City Platform
//
//
// [ { "feed_data": "jhdsf asdfh afhask fdakjs kjf " } ]
//
// Note that a FeedParser (like this one) ALWAYS returns a Json Array, even for a single record
//

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseFeedEventbusMsg implements FeedParser {

    private String area_id;

    private JsonObject config;

    private Log logger;
    
    ParseFeedEventbusMsg(JsonObject config, Log logger)
    {
       this.config = config;

       this.area_id = config.getString("area_id","");

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseFeedEventbusMsg started");
    }

    // Here is where we try and parse the page into a JsonObject
    public JsonObject parse(String page)
    {
        logger.log(Constants.LOG_DEBUG, "ParseFeedEventbusMsg.parse() called");

        return new JsonObject(page); // EventBus message from feed is returned as a JsonObject
    }

} // end ParseFeedEventbusMsg

