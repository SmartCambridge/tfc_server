package uk.ac.cam.tfc_server.feedmaker;

import java.util.ArrayList;
import java.util.HashMap;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

// ********************************************************************************
// **************  cam_park_rss feed template   *******************************
// ********************************************************************************

public class ParseParkRss {

    public ArrayList<ParseFeed.RecordTemplate> get_record_templates()
    {
        ArrayList<ParseFeed.RecordTemplate> cam_park_rss = new ArrayList<ParseFeed.RecordTemplate>();
        // Define common fields for this feed (i.e. fields the same for every RecordTemplate)

        ArrayList<ParseFeed.FieldTemplate> common_fields = new ArrayList<ParseFeed.FieldTemplate>();

       common_fields.add(new ParseFeed.FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity",false));
       common_fields.add(new ParseFeed.FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken ",false));
       common_fields.add(new ParseFeed.FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied",false));
       // this is an alternative 'spaces_free' fixed value if record contains "100% full"
       // valid for *all* car parks in the RSS feed. We have similar rules for capacity and occupied for each car park.
       common_fields.add(new ParseFeed.FieldTemplate("spaces_free","conditional_fixed_int",null,0,"100% full",null,false));
       
       ParseFeed.RecordTemplate ft;

       // RecordTemplate for Grand Arcade Car Park from cam_park_rss (similar for each Car Park in feed as below)
       // Pre-populate the template with fields common to all car parks (i.e. 'fields' arraylist above)
       ft = new ParseFeed.RecordTemplate(common_fields);
       // Define the text strings (tags) that define the start and end of the text block in the field.
       // The section of text containing Grand Arcade occupancy has the string "Grand Arcade" before the required values, in a block ending '<tr>'
       ft.tag_start = "Grand Arcade";
       // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null,true));
       // Then (in addition to the 'common' fields defined first) we have additional non-required fields which only
       // generate JSON values when the record contains "100% full". This is necessary due to the changing source format.
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,890,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,890,"100% full",null,false));
       // This RecordTemplate is completed, so add to the "cam_park_rss" ArrayList
       cam_park_rss.add(ft);

       // Grafton East Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Grafton East";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grafton-east-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,780,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,780,"100% full",null,false));
       cam_park_rss.add(ft);

       // Grafton West Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Grafton West";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grafton-west-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,280,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,280,"100% full",null,false));
       cam_park_rss.add(ft);

       // Park Street Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Park Street";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","park-street-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,375,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,375,"100% full",null,false));
       cam_park_rss.add(ft);

       // Queen Anne Terrace Car Park
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Queen Anne";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","queen-anne-terrace-car-park",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,540,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,540,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Madingley Road
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Madingley Road";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","madingley-road-park-and-ride",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,930,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,930,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Trumpington
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Trumpington";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","trumpington-park-and-ride",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,1340,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,1340,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Babraham
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Babraham";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","babraham-park-and-ride",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,1500,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,1500,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Milton
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Milton";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","milton-park-and-ride",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,800,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,800,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Newmarket Road Front
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Newmarket Rd Front";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","newmarket-road-front-park-and-ride",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,259,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,259,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Newmarket Road Rear
       ft = new ParseFeed.RecordTemplate(common_fields);
       ft.tag_start = "Newmarket Rd Rear";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","newmarket-road-rear-park-and-ride",0,null,null,true));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","conditional_fixed_int",null,614,"100% full",null,false));
       ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","conditional_fixed_int",null,614,"100% full",null,false));
       cam_park_rss.add(ft);

       return cam_park_rss;
    } // end get_record_templates
} // end ParseCamPark
