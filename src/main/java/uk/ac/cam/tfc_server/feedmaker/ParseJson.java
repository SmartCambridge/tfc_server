package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseJson.java
//
//   Convert the received JSON msg into 
//   { "request_data": [ {msg} ] }
//   json eventbus format.
//
//
//**********************************************************************
//**********************************************************************

//
// As a minimum, a FeedParse will return a JsonObject { "request_data": [ ...parsed contents ] }
// and the FeedMaker will add other properties to the EventBus Message (like ts)

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseJson implements FeedParser {

    private JsonObject config;

    private Log logger;
    
    ParseJson(JsonObject config, Log logger)
    {
       this.config = config;

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseJson started");
    }

    // Here is where we try and parse the page into a JsonObject
    public JsonObject parse(Buffer buf)
    {
        logger.log(Constants.LOG_DEBUG, "ParseJson.parse() called");

        // parse the incoming data feed as JSON
        JsonObject feed_jo = new JsonObject(buf.toString());

        // Create the eventbus message JsonObject this FeedParser will return
        JsonObject msg = new JsonObject();

        JsonArray records = new JsonArray();

        records.add(feed_jo);

        msg.put("request_data", records);

        // return { "request_data": [ {original feed data json object} ] }
        return msg;
    }

} // end ParseJson

