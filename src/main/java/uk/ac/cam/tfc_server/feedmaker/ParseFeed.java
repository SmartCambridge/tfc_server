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

    class RecordTemplate {
        public String tag_start; // the string that begins the block of the source file, e.g. "Madingley Road"
        public String tag_end;   // the string that ends the block, e.g. "</item>"
        public ArrayList<FieldTemplate> fields;

        RecordTemplate(ArrayList<FieldTemplate> fields)
        {
            this.fields = new ArrayList<FieldTemplate>(fields);
        }
    }

    // this is a 'template' for a field to be extracted from the feed
    class FieldTemplate {
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

    }

    // initialise the templates structure
    //debug - this feed template config data is hardcoded into this program, will be moved out into
    // config file or database, with feed_type used to look it up.
    HashMap<String,ArrayList<RecordTemplate>> init_templates()
    {
       HashMap<String,ArrayList<RecordTemplate>> record_templates = new HashMap<String,ArrayList<RecordTemplate>>();

       // ********************************************************************************
       // **************  cam_park_local feed template    ********************************
       // ********************************************************************************
       ArrayList<RecordTemplate> cam_park_local = new ArrayList<RecordTemplate>();
 
       ArrayList<FieldTemplate> common_fields;
       
       common_fields = new ArrayList<FieldTemplate>();
       
       common_fields.add(new FieldTemplate("spaces_free","int",null,0,"<strong>"," spaces", false));
       // this is an alternative 'spaces_free' fixed value if record contains "This car park is full"
       // valid for *all* car parks in the RSS feed. We have similar rules for capacity and occupied for each car park.
       common_fields.add(new FieldTemplate("spaces_free","conditional_fixed_int",null,0,"This car park is full",null,false));

       RecordTemplate ft;

       // Grafton East Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "grafton-east-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-east-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,780,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Grafton West Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "grafton-west-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-west-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,280,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Grand Arcade Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "grand-arcade-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,890,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Park Street Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "park-street-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","park-street-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,375,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       // Queen Anne Terrace Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "queen-anne-terrace-car-park";
       ft.tag_end = "</p>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","queen-anne-terrace-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,540,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       cam_park_local.add(ft);

       record_templates.put("cam_park_local", cam_park_local);

       // ********************************************************************************
       // **************  cam_park_rss feed template    ********************************
       // ********************************************************************************
       ArrayList<RecordTemplate> cam_park_rss = new ArrayList<RecordTemplate>();

       common_fields = new ArrayList<FieldTemplate>();
       common_fields.add(new FieldTemplate("spaces_capacity","int",null,0,"taken out of "," capacity",false));
       common_fields.add(new FieldTemplate("spaces_occupied","int",null,0,"There are "," spaces taken ",false));
       common_fields.add(new FieldTemplate("spaces_free","calc_minus",null,0,"spaces_capacity","spaces_occupied",false));
       // this is an alternative 'spaces_free' fixed value if record contains "100% full"
       // valid for *all* car parks in the RSS feed. We have similar rules for capacity and occupied for each car park.
       common_fields.add(new FieldTemplate("spaces_free","conditional_fixed_int",null,0,"100% full",null,false));
       

       // RecordTemplate for Grand Arcade Car Park from cam_park_rss (similar for each Car Park in feed as below)
       // Pre-populate the template with fields common to all car parks (i.e. 'fields' arraylist above)
       ft = new RecordTemplate(common_fields);
       // Define the text strings (tags) that define the start and end of the text block in the field.
       // The section of text containing Grand Arcade occupancy has the string "Grand Arcade" before the required values, in a block ending '<tr>'
       ft.tag_start = "Grand Arcade";
       // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
       ft.tag_end = "</tr>";
       // parking_id and spaces_capacity initialized with value in template
       // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null,true));
       // Then (in addition to the 'common' fields defined first) we have additional non-required fields which only
       // generate JSON values when the record contains "100% full". This is necessary due to the changing source format.
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,890,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,890,"100% full",null,false));
       // This RecordTemplate is completed, so add to the "cam_park_rss" ArrayList
       cam_park_rss.add(ft);

       // Grafton East Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Grafton East";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-east-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,780,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,780,"100% full",null,false));
       cam_park_rss.add(ft);

       // Grafton West Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Grafton West";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grafton-west-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,280,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,280,"100% full",null,false));
       cam_park_rss.add(ft);

       // Park Street Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Park Street";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","park-street-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,375,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,375,"100% full",null,false));
       cam_park_rss.add(ft);

       // Queen Anne Terrace Car Park
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Queen Anne";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","queen-anne-terrace-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,540,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,540,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Madingley Road
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Madingley Road";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","madingley-road-park-and-ride",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,930,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,930,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Trumpington
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Trumpington";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","trumpington-park-and-ride",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,1340,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,1340,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Babraham
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Babraham";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","babraham-park-and-ride",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,1500,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,1500,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Milton
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Milton";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","milton-park-and-ride",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,800,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,800,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Newmarket Road Front
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Newmarket Rd Front";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","newmarket-road-front-park-and-ride",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,259,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,259,"100% full",null,false));
       cam_park_rss.add(ft);

       // P&R Newmarket Road Rear
       ft = new RecordTemplate(common_fields);
       ft.tag_start = "Newmarket Rd Rear";
       ft.tag_end = "</item>";
       // parking_id and spaces_capacity initialized with value in template
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","newmarket-road-rear-park-and-ride",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","conditional_fixed_int",null,614,"100% full",null,false));
       ft.fields.add(new FieldTemplate("spaces_occupied","conditional_fixed_int",null,614,"100% full",null,false));
       cam_park_rss.add(ft);


       record_templates.put("cam_park_rss", cam_park_rss);

       // ********************************************************************************
       // **************  cam_park_carpark feed template   *******************************
       // ********************************************************************************
       /*
         <tr class="">
         <td style="text-align:center;width=35%;">
         <img title="Car parks UK" alt="" src="icons/car_park_light.gif" />
         </td>
         <td style="width:35%;">
         <a title="Carpark" href="/carparkdetail.aspx?t=carpark&amp;r=CAMB-CP001&amp;X=545041&amp;Y=258285&amp;format=xhtml">Grand Arcade</a>
         </td>
         <td style="text-align:center;">
         <a class="LocationLink" title="View on Map" href="/map.aspx?maplayers=car_park&amp;X=545041&amp;Y=258285&amp;ZoomLevel=6">View on Map</a>
         </td>
         <td class="sortable-numeric" ts_type="number" style="width:20%">812</td>
         <td class="sortable-numeric" ts_type="number" style="width:15%;">890</td>
         <td align="center" class="cargraphiccell sortable-numeric" ts_type="number" style="width:15%">8%<div class="carpark-occupancy-percent"><div class="carpark-occupancy-percentdiv" style="width:8%;"><span class="carpark-occupancy-percentspan">8</span></div></div></td>
         <td class="sortable-text" style="width:25%;">Filling</td>
         <td class="sortable-text">OPEN</td>
         </tr>
       */
       ArrayList<RecordTemplate> cam_park_carpark = new ArrayList<RecordTemplate>();

       // Define common fields for this feed (i.e. fields the same for every RecordTemplate)

       common_fields = new ArrayList<FieldTemplate>();
       // 'spaces_free' is a <td> entry with the number between 'width:20%">' and '</td>'
       common_fields.add(new FieldTemplate("spaces_free","int",null,0,"width:20%\">","</td>", false));

       // Grand Arcade Car Park
       // RecordTemplate for Grand Arcade Car Park from cam_park_carpark (similar for each Car Park in feed as below)
       // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
       ft = new RecordTemplate(common_fields);
       // Define the text strings (tags) that define the start and end of the text block in the field.
       // The section of text containing Grand Arcade occupancy has the string "Grand Arcade" before the required values.
       ft.tag_start = "Grand Arcade";
       // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
       ft.tag_end = "</tr>";
       // parking_id and spaces_capacity initialized with value in template
       // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
       ft.fields.add(new FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_capacity","fixed_int",null,890,null,null,true));
       ft.fields.add(new FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
       // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
       cam_park_carpark.add(ft);



       record_templates.put("cam_park_carpark", cam_park_carpark);

       return record_templates;
    }


}
