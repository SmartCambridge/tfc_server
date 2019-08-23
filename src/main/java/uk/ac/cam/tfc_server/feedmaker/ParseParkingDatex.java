package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseParkingDatex.java
//
// Convert the data read from the http 'get' of DatexII XML for parking occupancy
// into an Adaptive City Platform eventbus message.
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
//**********************************************************************
//**********************************************************************


import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseParkingDatex implements FeedParser {

    private String area_id;

    private JsonObject config;

    private Log logger;
    
    ParseParkingDatex(JsonObject config, Log logger)
    {
       this.config = config;

       this.area_id = config.getString("area_id","");

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseParkingDatex started");
    }

    // Here is where we try and parse the page and return a JsonArray
    public JsonObject parse(Buffer buf)
    {

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex.parse() called");

        JsonArray records = new JsonArray();

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex plain record");
        JsonObject json_record = new JsonObject();
        json_record.put("feed_data", buf.getBytes());
        records.add(json_record);
        JsonObject msg = new JsonObject();
        msg.put("request_data", records);
        return msg;
    }

} // end ParseParkingDatex

