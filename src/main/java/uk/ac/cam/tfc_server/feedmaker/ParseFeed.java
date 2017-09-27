package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseFeed.java
//
//   Convert the data read from the http feed into Json
//**********************************************************************
//**********************************************************************

// E.g. parse the data returned from Cambridge local parking occupancy API

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
// So the basic idea is that ParseFeed is passed a general (typically human-readable) page from which it
// extracts an ARRAY of records of parsed data.
// The source page (typically a web page) may contain, for example, the occupancy of various car parks.
//
// ParseFeed uses 'RecordTemplates' that specify where the data is in the received page, in the form of:
//  * a 'start_tag' (i.e. text string) which indicates the start of the corresponding data record on the page
//  * a list of 'field' definitions (FieldTemplates) which show where the required field data is on the page
//      immediately following the 'start_tag'.
//  * a field definition can also provide a hard-coded value for a field to be returned.
// ParseFeed will iterate through the defined RecordTemplates picking out the required data as instructed by each.
// Each RecordTemplate results in a JsonObject, which are accumulated in a JsonArray that is the returned result.
//

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;


public class ParseFeed {

    static final int MAX_TAG_SIZE = 40; // maximum number of chars in a 'tag' used to match text in template
    
    private String feed_type; // e.g. "cam_park_local"

    private String area_id;

    private Log logger;

    // structure holding templates for each feed type
    // i.e. record_templates["cam_park_local"] gives templates for that feed type
    HashMap<String, ArrayList<RecordTemplate>> record_templates;
    
    ParseFeed(String feed_type, String area_id, Log logger)
        {
           this.feed_type = feed_type;

           this.area_id = area_id;

           this.logger = logger;

           record_templates = init_templates();
           
           logger.log(Constants.LOG_DEBUG, "ParseFeed started for "+feed_type);
        }

    // Here is where we try and parse the page and return a JsonArray
    public JsonArray parse_array(String page)
        {

            logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array called with page");

            JsonArray records = new JsonArray();

            // if feed_type is "plain", just return a simple JsonArray of a single JsonObject {"feed_data": page }
            if (feed_type==Constants.FEED_PLAIN)
            {
                logger.log(Constants.LOG_DEBUG, "ParseFeed plain record");
                JsonObject json_record = new JsonObject();
                json_record.put("feed_data", page);
                records.add(json_record);
                return records;
            }

            // otherwise try and match each known car park to the data
            for (int i=0; i<record_templates.get(feed_type).size(); i++)
                {
                    RecordTemplate record_template = record_templates.get(feed_type).get(i);
                      
                    logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array trying template "+record_template.tag_start);

                    // ...grafton-east-car-park...<strong>384 spaces...
                    int rec_start = page.indexOf(record_template.tag_start); // find start of record 
                    if (rec_start < 0) continue;  // if not found then skip current record_template
                    
                    int rec_end = page.indexOf(record_template.tag_end, rec_start); // find end of record 
                    if (rec_end < 0) continue;  // if not found then skip current record_template

                    String record = page.substring(rec_start, rec_end);
                    
                    logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array matched template "+
                               record_template.tag_start+
                               " (length "+record.length()+")"+
                               "["+record_template.fields.size()+"]");

                    logger.log(Constants.LOG_DEBUG, "record=\""+record+"\"");

                    JsonObject json_record = new JsonObject();

                    boolean record_ok = true;  // this flag will be set to false if a 'required' field is missing
                    
                    for (int j=0; j<record_template.fields.size(); j++)
                    {
                        FieldTemplate field_template = record_template.fields.get(j);
                           
                        logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array trying field "+field_template.field_name);

                        // if field value already in field template, just use that
                        if (field_template.field_type == "fixed_int")
                        {
                          json_record.put(field_template.field_name, field_template.fixed_int);
                          //logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array "+
                          //                                field_template.field_name+" fixed_int = "+
                          //                                field_template.fixed_int
                          //          );
                          continue;
                        }
                        else if (field_template.field_type == "conditional_fixed_int")
                        {
                          int field_start = record.indexOf(field_template.s1);
                          if (field_start >= 0)
                          {
                            json_record.put(field_template.field_name, field_template.fixed_int);
                            logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array using conditional_fixed_int "+
                                       field_template.field_name+" "+field_template.s1);
                          }
                          continue;
                        }
                        else if (field_template.field_type == "fixed_string")
                        {
                          json_record.put(field_template.field_name, field_template.fixed_string);
                          //logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array "+
                          //                                field_template.field_name+" fixed_string = "+
                          //                                field_template.fixed_string
                          //          );
                          continue;
                        }

                        else if (field_template.field_type == "calc_minus")
                        {
                            try {
                            int v1 = json_record.getInteger(field_template.s1);
                            int v2 = json_record.getInteger(field_template.s2);
                            json_record.put(field_template.field_name, v1-v2);
                            } catch (Exception e) {
                                if (field_template.required)
                                    {
                                        logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array skipping due to "+
                                                   field_template.field_name);
                                        record_ok = false;
                                        break;
                                    }
                            }
                            continue;
                        }
                        else if (field_template.field_type == "calc_plus")
                        {
                            try {
                            int v1 = json_record.getInteger(field_template.s1);
                            int v2 = json_record.getInteger(field_template.s2);
                            json_record.put(field_template.field_name, v1+v2);
                            } catch (Exception e) {
                                if (field_template.required)
                                    {
                                        logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array skipping due to "+
                                                   field_template.field_name);
                                        record_ok = false;
                                        break;
                                    }
                            }
                            continue;
                        }
                        // field value was not in template, so parse from record

                        // find index of start of field, or skip this record_template
                        int field_start = record.indexOf(field_template.s1);
                        if (field_start < 0)
                            { 
                                if (field_template.required)
                                    {
                                        logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array skipping due to "+
                                        field_template.field_name);
                                        record_ok = false;
                                        break;
                                    }
                                continue;
                            }
                        field_start = field_start + field_template.s1.length();

                        // find index of end of field, or skip this record_template
                        int field_end = record.indexOf(field_template.s2, field_start);
                        if (field_end < 0)
                            { 
                                if (field_template.required)
                                    {
                                        logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array skipping due to "+
                                        field_template.field_name);
                                        record_ok = false;
                                        break;
                                    }
                                continue;
                            }
                        if (field_end - field_start > MAX_TAG_SIZE) continue;

                        // pick out the field value, or skip if not recognized
                        String field_string = record.substring(field_start, field_end);
                        if (field_template.field_type == "int")
                        {
                            try {
                                int int_value = Integer.parseInt(field_string);
                                json_record.put(field_template.field_name, int_value);
                            } catch (NumberFormatException e) {
                                if (field_template.required)
                                    {
                                        logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array skipping due to "+
                                                   field_template.field_name);
                                        record_ok = false;
                                        break;
                                    }
                                continue;
                            }
                            continue;
                        }
                        else if (field_template.field_type == "string")
                        {
                            json_record.put(field_template.field_name, field_string);
                            continue;
                        }
                    }

                    if (record_ok)
                        {
                            logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array found "+json_record);

                            records.add(json_record);
                        }
                    else
                        {
                            logger.log(Constants.LOG_DEBUG, "ParseFeed.parse_array skipping matched template "+
                                       record_template.tag_start);
                        }
                }

            return records;
        }
    
    // get current local time as "YYYY-MM-DD hh:mm:ss"
    public static String local_datetime_string()
    {
        LocalDateTime local_time = LocalDateTime.now();
        return local_time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // initialise the templates structure
    //debug - this feed template config data is hardcoded into this program, will be moved out into
    // config file or database, with feed_type used to look it up.
    HashMap<String,ArrayList<RecordTemplate>> init_templates()
    {
       HashMap<String,ArrayList<RecordTemplate>> record_templates = new HashMap<String,ArrayList<RecordTemplate>>();

       record_templates.put("cam_park_local", (new ParseParkLocal()).get_record_templates());

       record_templates.put("cam_park_rss", (new ParseParkRss()).get_record_templates());

       record_templates.put("cam_park_carpark", (new ParseCamPark()).get_record_templates());

       return record_templates;
    }

    static public class RecordTemplate {
        public String tag_start; // the string that begins the block of the source file, e.g. "Madingley Road"
        public String tag_end;   // the string that ends the block, e.g. "</item>"
        public ArrayList<FieldTemplate> fields;

        RecordTemplate(ArrayList<FieldTemplate> fields)
        {
            this.fields = new ArrayList<FieldTemplate>(fields);
        }
    } // end RecordTemplate

    // this is a 'template' for a field to be extracted from the feed
    static public class FieldTemplate {
        public String field_name;  // the JSON name to be given to the value
        public String field_type;  // int | string | fixed_int | fixed_string
        public String fixed_string; // if field_type = "fixed_string" then this will be required fixed value
        public int    fixed_int;    // as above but for int value
        public String s1; // s1,s2 are multi-purpose strings:
        public String s2; // (i) start and end tags of required fields
        // (ii) the names of two existing json fields for field_type="calc*" (e.g. calc_minus)
        public boolean required; // if this field is not present in record, ignore the record

        FieldTemplate(String field_name, String field_type, String fixed_string, int fixed_int,
                      String s1, String s2, boolean required)
        {
            this.field_name   = field_name;
            this.field_type   = field_type;
            this.fixed_string = fixed_string;
            this.fixed_int    =  fixed_int;
            this.s1           = s1;
            this.s2           = s2;
            this.required     = required;
        }

    } // end FieldTemplate

} // end ParseFeed


