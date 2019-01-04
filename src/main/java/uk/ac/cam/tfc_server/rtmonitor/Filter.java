package uk.ac.cam.tfc_server.rtmonitor;

import java.util.*;
import java.time.*;
import java.time.format.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import uk.ac.cam.tfc_server.util.Constants;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Position;
import uk.ac.cam.tfc_server.util.Log;

    // Client subscription filter e.g. { "test": "=", "key": "A>B", "value": "X" }
    class Filter {
        private Log logger;

        private String MODULE_NAME = "RTMonitor";
        private String MODULE_ID = "Monitor";

        public JsonObject msg;

        Filter(JsonObject msg)
        {
            logger = new Log(RTMonitor.LOG_LEVEL);

            this.msg = msg;
        }

        // Test a JsonObject records against this Filter
        public boolean test(JsonObject record)
        {
            // test can default to "="
            String test = msg.getString("test");
            if (test == null)
            {
                test = "=";
            }

            switch (test)
            {
                case "=": 
                    return test_equals(record);

                case "inside":
                    return test_inside(record);

                default:
                    logger.log(Constants.LOG_WARN, MODULE_NAME+"."+MODULE_ID+
                        ": Filter.test '"+test+"' not recognised");
                    break;
            }
            return false;
        } // end Filter.test()

        private boolean test_equals(JsonObject record)
        {
            // Given a filter say { "test": "=", "key": "VehicleRef", "value": "SCNH-35224" }
            String key = msg.getString("key");
            if (key == null)
            {
                return false;
            }

            //DEBUG TODO allow numeric value
            String value = msg.getString("value");
            if (value == null)
            {
                return false;
            }

            // Try and pick out the property "key" from the data record
            String record_value = record.getString(key);
            if (record_value == null)
            {
                return false;
            }

            //DEBUG TODO allow different tests than just "="
            return record_value.equals(value);
        } // end Filter.test_equals()

        private boolean test_inside(JsonObject record)
        {
            //DEBUG TODO implement this
            // Example filter:
            //   { "test": "inside",
            //     "lat_key": "Latitude",
            //     "lng_key": "Longitude",
            //     "points": [
            //         {  "lat": 52.21411510, "lng": 0.09916394948 },
            //         {  "lat": 52.20885583, "lng": 0.14877408742 },
            //         {  "lat": 52.19170630, "lng": 0.13778775930 },
            //         {  "lat": 52.19496839, "lng": 0.10053724050 }
            //     ]
            //   }

            //DEBUG TODO move this into the Subscription constructor for speedup
            String lat_key = msg.getString("lat_key", "acp_lat");

            String lng_key = msg.getString("lng_key", "acp_lng");

            JsonArray points = msg.getJsonArray("points");

            ArrayList<Position> polygon = new ArrayList<Position>();

            for (int i=0; i<points.size(); i++)
            {
                polygon.add(new Position(points.getJsonObject(i)));
            }

            double lat;
            double lng;
            
            try
            {
                lat = get_double(record, lat_key);

                lng = get_double(record, lng_key);
            }
            catch (Exception e)
            {
                return false;
            }

            Position pos = new Position(lat,lng);

            // ah, all ready, now we can call the 'inside' test of the Position.
            return pos.inside(polygon);

        } // end Filter.test_inside()

        // Get a 'double' from the data record property 'key'
        // e.g. return the value of a "Latitude" property.
        // Note this could be a string or a number...
        private double get_double(JsonObject record, String key)
        {
            try
            {
                return record.getDouble(key);
            }
            catch (ClassCastException e)
            {
                return Double.parseDouble(record.getString(key));
            }
            //throw new Exception("get_double failed to parse number");
        }

    } // end class Filter

