package uk.ac.cam.tfc_server.feedmaker;

import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;


public class ParseFeedXMLFlat implements FeedParser {

    static final int MAX_TAG_SIZE = 40; // maximum number of chars in a 'tag' used to match text in template
    
    private String feed_type; // e.g. "cam_park_local"

    private String area_id;

    private String record_tag;

    private JsonObject config;

    private Log logger;

    ParseFeedXMLFlat(JsonObject config, Log logger)
    {
       this.config = config;

       this.feed_type = config.getString("feed_type");

       this.area_id = config.getString("area_id","");

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseFeed started for "+feed_type);
    }

    // Here is where we try and parse the page and return a JsonArray
    public JsonArray parse_array(String page)
    {

        logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array called for feed type "+feed_type);

        JsonArray records = new JsonArray();

        // if feed_type is "feed_xml_flat", return flattened content of XML elements given in config 'record_tag'
        if (!(feed_type.equals(Constants.FEED_XML_FLAT)))
        {
            logger.log(Constants.LOG_WARN, "ParseFeedXMLFlat called with incompatible feed_type "+feed_type);
            return records;
        }

        logger.log(Constants.LOG_DEBUG, "ParseFeed xml_flat record for "+config.getString("record_tag"));
        // <record_tag>..</record_tag> is the flattenable XML object that possibly repeats in the page 
        String record_tag = config.getString("record_tag");
        // cursor is our current position on the page as we step through parsing records
        int cursor = 0;
        // While we have some page left, continue parsing records
        while (cursor >= 0) // this could be 'while (true)' as a 'break' should always occur anyway
        {
            // We will accumulate the flat Json from the XML into json_record
            // I.e. each XML <Foo>xyz</Foo>
            // becomes "Foo": "xyz"
            // and any nesting of XML objects is ignored.
            // This assumes the flattenable XML does NOT contain duplicate XML tags WITHIN records
            // although the records themselves can be repeated. This works for e.g. Siri-VM.
            JsonObject json_record = new JsonObject();
            cursor = page.indexOf("<"+record_tag+">", cursor);
            if (cursor < 0)
            {
                break;
            }
            int record_end = page.indexOf("</"+record_tag+">", cursor);
            if (record_end < 0)
            {
                break;
            }
            // Ok, we think we have a record between 'cursor' and 'record_end'
            logger.log(Constants.LOG_DEBUG, "ParseFeed xml_flat record at "+cursor);

            // shift cursor to the end of the current record
            //json_record.put("feed_data", page);
            // Add the current record to the 'records' result list
            records.add(json_record);
            // shift cursor to the end of the current record before we loop to look for the next record
            cursor = record_end;
        }
        logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat parse_array completed");
        return records;
     } // end parse_array
} // end ParseFeedXMLFlat

