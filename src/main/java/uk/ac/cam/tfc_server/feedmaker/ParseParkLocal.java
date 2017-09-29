package uk.ac.cam.tfc_server.feedmaker;

import java.util.ArrayList;
import java.util.HashMap;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

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
// ********************************************************************************
// **************  cam_park_local feed template   *******************************
// ********************************************************************************

public class ParseParkLocal {

    public ArrayList<ParseFeed.RecordTemplate> get_record_templates()
    {
       ArrayList<ParseFeed.RecordTemplate> cam_park_local = new ArrayList<ParseFeed.RecordTemplate>();
       // Define common fields for this feed (i.e. fields the same for every RecordTemplate)

       ArrayList<ParseFeed.FieldTemplate> common_fields = new ArrayList<ParseFeed.FieldTemplate>();
       
       common_fields.add(new ParseFeed.FieldTemplate("spaces_free","int",null,0,"<strong>"," spaces", false));
       // this is an alternative 'spaces_free' fixed value if record contains "This car park is full"
       // valid for *all* car parks in the RSS feed. We have similar rules for capacity and occupied for each car park.
       common_fields.add(new ParseFeed.FieldTemplate("spaces_free","conditional_fixed_int",null,0,"This car park is full",null,false));

       ParseFeed.RecordTemplate ft;

       // Grafton East Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "grafton-east-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grafton-east-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,780,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Grafton West Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "grafton-west-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grafton-west-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,280,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Grand Arcade Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "grand-arcade-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,890,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Park Street Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "park-street-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","park-street-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,375,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Queen Anne Terrace Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "queen-anne-terrace-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","queen-anne-terrace-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,540,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       return cam_park_local;
    } // end get_record_templates
} // end ParseCamPark

