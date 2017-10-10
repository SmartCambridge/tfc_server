package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseFeedPlain.java
//
// Convert the data read from the http feed into standard
// Adaptive City Platform eventbus message with received data as
// single 'string' Json property
//
//**********************************************************************
//**********************************************************************


// Returns entire page as a single "feed_data" json property e.g.:
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

public class ParseFeedPlain implements FeedParser {

    private String area_id;

    private JsonObject config;

    private Log logger;
    
    ParseFeedPlain(JsonObject config, Log logger)
    {
       this.config = config;

       this.area_id = config.getString("area_id","");

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseFeedPlain started");
    }

    // Here is where we try and parse the page and return a JsonArray
    public JsonObject parse(String page)
    {

        logger.log(Constants.LOG_DEBUG, "ParseFeedPlain.parse() called");

        JsonArray records = new JsonArray();

        logger.log(Constants.LOG_DEBUG, "ParseFeed plain record");
        JsonObject json_record = new JsonObject();
        json_record.put("feed_data", page);
        records.add(json_record);
        JsonObject msg = new JsonObject();
        msg.put("request_data", records);
        return msg;
    }

} // end ParseFeedPlain

