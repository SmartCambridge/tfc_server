package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseCamParkingLocal.java
//
//   Convert the data read from the local parking web feed into Json
//**********************************************************************
//**********************************************************************

// parse the data returned from Cambridge local parking occupancy API

// Polls https://www.cambridge.go.uk/jdi_parking_ajax/complete
// Gets (without these added linebreaks):
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
   "module_name": "feedmaker",                  // as given to the FeedMaker in config, typically "feedmaker"
   "module_id":   "cam_parking_local",          // from config, but platform unique value within module_name
   "msg_type":    "car_parking",                // Constants.FEED_CAR_PARKING
   "feed_id":     "cam_parking_local",          // identifies http source, matches config
   "filename":    "1459762951_2016-04-04-10-42-31",
   "filepath":    "2016/04/04",
   "request_data":[                             // actual parsed data from source, in this case car park occupancy
                    { "area_id":         "cam",
                      "parking_id":      "grafton_east",
                      "parking_name":    "Grafton East",
                      "spaces_capacity": 874,
                      "spaces_free":     384,
                      "spaces_occupied": 490
                    } ...
                   ]
}

*/

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseCamParkingLocal {

    private String area_id;

    ArrayList<ParkingRecord> car_parks;
    
    ParseCamParkingLocal(String area_id)
        {
           this.area_id = area_id;

           car_parks = new ArrayList<ParkingRecord>();
           car_parks.add( new ParkingRecord() {{ id       = "grafton-east-car-park";
                                                 name     = "Grafton East";
                                                 capacity = 874;
                                                 tag1     = "<strong>";
                                                 tag2     = " spaces";
                                              }});
           car_parks.add( new ParkingRecord() {{ id       = "grafton-west-car-park";
                                                 name     = "Grafton West";
                                                 capacity = 280;
                                                 tag1     = "<strong>";
                                                 tag2     = " spaces";
                                              }});
           car_parks.add( new ParkingRecord() {{ id       = "grand-arcade-car-park";
                                                 name     = "Grand Arcade";
                                                 capacity = 953;
                                                 tag1     = "<strong>";
                                                 tag2     = " spaces";
                                              }});
           car_parks.add( new ParkingRecord() {{ id       = "park-street-car-park";
                                                 name     = "Park Street";
                                                 capacity = 390;
                                                 tag1     = "<strong>";
                                                 tag2     = " spaces";
                                              }});
           car_parks.add( new ParkingRecord() {{ id       = "queen-anne-terrace-car-park";
                                                 name     = "Queen Anne Terrace";
                                                 capacity = 570;
                                                 tag1     = "<strong>";
                                                 tag2     = " spaces";
                                              }});
        }

    // Here is where we try and parse the page and return a JsonArray
    public JsonArray parse_array(String page)
        {

            JsonArray records = new JsonArray();
            // try and match each known car park to the data
            for (int i=0; i<car_parks.size(); i++)
                {
                    ParkingRecord parking = car_parks.get(i);
                    
                    // ...grafton-east-car-park...<strong>384 spaces...
                    int rec_start = page.indexOf(parking.id); // find start of record for this car park
                    if (rec_start < 0) continue;  // if not found then skip current parking

                    // find index of start of 'spaces' number, or skip this parking
                    int spaces_start = page.indexOf(parking.tag1, rec_start);
                    if (spaces_start < 0) continue;
                    spaces_start = spaces_start + parking.tag1.length();

                    // find index of end of 'spaces' number, or skip this parking
                    int spaces_end = page.indexOf(parking.tag2, spaces_start);
                    if (spaces_end < 0) continue;
                    if (spaces_end - spaces_start > 5) continue;

                    // pick out the 'XX spaces' number, or skip if not recognized
                    int spaces_free;
                    try {
                        spaces_free = Integer.parseInt(page.substring(spaces_start, spaces_end));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                
                    JsonObject json_record = new JsonObject();
                    json_record.put("area_id",area_id);
                    json_record.put("parking_id",parking.id);
                    json_record.put("parking_name",parking.name);
                    json_record.put("spaces_capacity",parking.capacity);
                    json_record.put("spaces_free",spaces_free);
                    int spaces_occupied = parking.capacity - spaces_free;
                    if (spaces_occupied < 0) spaces_occupied = 0;
                    json_record.put("spaces_occupied", spaces_occupied);

                    records.add(json_record);
                }

            return records;
        }
    
    // get current local time as "YYYY-MM-DD hh:mm:ss"
    public static String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    class ParkingRecord {
        String id;
        String name;
        String tag1;
        String tag2;
        int capacity;
    }
        
}
