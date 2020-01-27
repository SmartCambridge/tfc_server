package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseBTJourneyLocations.java
//
//   Convert the received JSON Drakewell 'locations' data into 
//   json eventbus format.
//
//   Data is unchanged except 'ts' is injected into 'sites' and 'links'.
//
//**********************************************************************
//**********************************************************************

/*
{
  "sites": [
    {
      "id": "{1F867FB8-83E6-4E63-A265-51CD2E71E053}",
      "name": "A1303Mad/M11Jct",
      "description": "A1303 Madingley Road Between Park & Ride and M11",
      "location": {
        "lat": 52.21421,
        "lng": 0.0792
      }
    },
    ...
  ],
  "links": [
    {
      "id": "CAMBRIDGE_JTMS|9800WBETRSU3",
      "name": "34 Barton Rd Out *",
      "description": "MACSSL208528 to MACSSL208518",
      "sites": [
        "{952D4ABB-857B-467D-9770-62DC0A4B5A5A}",
        "{11AB98B0-4A1A-478A-857D-A23C30C9CC48}"
      ],
      "length": 2045
    },
    ...
  ],
  "compoundRoutes": [
    {
      "id": "CAMBRIDGE_JTMS|9800ZOHMPGPY",
      "name": "Cambridge - A14 Junc 35 Outbound",
      "description": "",
      "sites": [
        "{60D1C306-08B8-44C5-A635-8C1F8D138320}",
        "{F99550C6-52B8-4906-BE1C-1D15C48062DD}",
        "{F957674F-CBFF-4089-B45B-778FCDD80629}",
        "{F9072877-E815-4D5C-9DB4-76F7C7C27D7C}"
      ],
      "links": [
        "CAMBRIDGE_JTMS|9800ZMJIIRA4",
        "CAMBRIDGE_JTMS|9800Y6CS0QK2",
        "CAMBRIDGE_JTMS|9800Z0SUAHN1"
      ],
      "length": 3962
    },
    ...
  ]
}
*/

//
// As a minimum, a FeedParse will return a JsonObject { "request_data": [ ...parsed contents ] }
// and the FeedMaker will add other properties to the EventBus Message (like ts)

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseBTJourneyLocations implements FeedParser {

    private JsonObject config;

    private Log logger;
    
    ParseBTJourneyLocations(JsonObject config, Log logger)
    {
       this.config = config;

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseBTJourneyLocations started");
    }

    // Here is where we try and parse the page into a JsonObject
    public JsonObject parse(Buffer buf)
    {
        logger.log(Constants.LOG_DEBUG, "ParseBTJourneyLocations.parse() called");

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

} // end ParseBTJourneyLocations

