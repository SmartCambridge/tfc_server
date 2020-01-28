package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseBTJourneyTimes.java
//
//   Convert the received JSON Drakewell 'livejourneytimes' data into 
//   json eventbus format.
//
//   The incoming data is a Json ARRAY, so we convert this to a JsonObject
//   with single property "journeytimes", i.e. the EventBus message is:
//
//   {
//      "ts": 1580132048,
//      "file_name" :... and other standard fields
//      "request_data": [ { "journeytimes": [ original data list ] } ],
//   }
//
//**********************************************************************
//**********************************************************************

/*
 
The Drakewell 'journeytimes' API returns a JsonArray containing a JsonObject for
each link or route in the system (both are defined by a common 'id'):

[
  {
    "id": "CAMBRIDGE_JTMS|9800WBETRSU3",
    "time": "2020-01-26 09:28:22",
    "period": 674,
    "travelTime": 127,
    "normalTravelTime": 129.39
  },
  ...
]

These JsonObjects will be returned in the EventBus message "request_data" property.
*/

//
// As a minimum, a FeedParser will return a JsonObject { "request_data": [ ...parsed contents ] }
// and the FeedMaker will add other properties to the EventBus Message (like ts)

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseBTJourneyTimes implements FeedParser {

    private JsonObject config;

    private Log logger;
    
    ParseBTJourneyTimes(JsonObject config, Log logger)
    {
       this.config = config;

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseBTJourneyTimes started");
    }

    // Here is where we try and parse the page into a JsonObject
    public JsonObject parse(Buffer buf)
    {
        logger.log(Constants.LOG_DEBUG, "ParseBTJourneyTimes.parse() called");

        // parse the incoming data feed as JSON
        JsonArray feed_json_array = new JsonArray(buf.toString());

        // Create the eventbus message JsonObject this FeedParser will return
        JsonObject msg = new JsonObject();

        msg.put("request_data", feed_json_array);

        return msg;
    }

} // end ParseBTJourneyTimes

