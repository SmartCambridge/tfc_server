package uk.ac.cam.tfc_server.feedscraper;

// parse the data returned from Cambridge local parking occupancy API

// Polls https://www.cambridge.go.uk/jdi_parking_ajax/complete
// Gets:
/*
<h2><a href="/grafton-east-car-park">Grafton East car park</a></h2><p><strong>384 spaces</strong> (51% full and filling)</p>
<h2><a href="/grafton-west-car-park">Grafton West car park</a></h2><p><strong>98 spaces</strong> (65% full and filling)</p>
<h2><a href="/grand-arcade-car-park">Grand Arcade car park</a></h2><p><strong>40 spaces</strong> (96% full and filling)</p>
<h2><a href="/park-street-car-park">Park Street car park</a></h2><p><strong>152 spaces</strong> (59% full and filling)</p>
<h2><a href="/queen-anne-terrace-car-park">Queen Anne Terrace car park</a></h2><p><strong>1 spaces</strong> (100% full and emptying)</p>
*/

// Returns:
/*
{
   "module_name": "feedscraper",                // as given to the FeedScraper in config, typically "feedscraper"
   "module_id":   "cam_parking_local",          // from config, but platform unique value within module_name
   "msg_type":    "car_parking",                // Constants.FEED_CAR_PARKING
   "feed_id":     "cam_parking_local",          // identifies http source, matches config
   "filename":    "1459762951_2016-04-04-10-42-31",
   "filepath":    "2016/04/04",
   "request_data":[                             // actual parsed data from source, in this case car park occupancy
                    { "area_id":         "cam",
                      "parking_id":      "grafton_east",
                      "parking_name":    "Grafton East",
                      "spaces_total":    874,
                      "spaces_free":     384,
                      "spaces_occupied": 490
                    } ...
                   ]
}

*/

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import io.vertx.core.json.JsonObject;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseCamParkingLocal {

    private String MODULE_NAME;
    private String MODULE_ID;
    private String area_id;
    private String feed_id;

    private JsonObject msg;
    
    ParseCamParkingLocal(String MODULE_NAME, String MODULE_ID, JsonObject config)
        {
           this.MODULE_NAME = MODULE_NAME;
           this.MODULE_ID = MODULE_ID;
           area_id = config.getString("area_id");
           feed_id = config.getString("feed_id");

        }

    public JsonObject parse(String page)
        {
            msg = new JsonObject();
            msg.put("module_name", MODULE_NAME);
            msg.put("module_id", MODULE_ID);
            msg.put("msg_type", Constants.FEED_CAR_PARKING);
            msg.put("feed_id", feed_id);
            //debug
            System.out.println("processing "+area_id);
            return msg;
        }
    
    // get current local time as "YYYY-MM-DD hh:mm:ss"
    public static String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

}
