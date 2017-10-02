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

    private String feed_type; // e.g. Constants.FEED_XML_FLAT

    private String area_id;

    private String record_tag;

    private JsonObject config;

    private Log logger;

    ParseFeedXMLFlat(JsonObject config, Log logger)
    {
       this.config = config;

       this.feed_type = config.getString("feed_type");

       this.area_id = config.getString("area_id","");

       this.record_tag = config.getString("record_tag","");

       this.logger = logger;

       logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat started for feed_type "+feed_type);
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

        logger.log(Constants.LOG_DEBUG, "ParseFeed xml_flat searching for "+record_tag);
        // <record_tag>..</record_tag> is the flattenable XML object that possibly repeats in the page 
        // cursor is our current position on the page as we step through parsing records
        int record_cursor = 0;
        // While we have some page left, continue parsing records
        while (record_cursor < page.length()) // this could be 'while (true)' as a 'break' should always occur anyway
        {
            logger.log(Constants.LOG_DEBUG, "ParseFeed xml_flat searching for record from index "+record_cursor);
            // We will accumulate the flat Json from the XML into json_record
            // I.e. each XML <Foo>xyz</Foo>
            // becomes "Foo": "xyz"
            // and any nesting of XML objects is ignored.
            // This assumes the flattenable XML does NOT contain duplicate XML tags WITHIN records
            // although the records themselves can be repeated. This works for e.g. Siri-VM.
            JsonObject json_record = new JsonObject();
            // Move cursor forwards to the next occurrence of the record_tag
            record_cursor = page.indexOf("<"+record_tag+">", record_cursor);
            logger.log(Constants.LOG_DEBUG, "ParseFeed xml_flat "+record_tag+" search result "+record_cursor);
            if (record_cursor < 0)
            {
                logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array no more "+record_tag+" records");
                // no more record_tag objects so finish
                break; // quit outermost records loop
            }
            int record_end = page.indexOf("</"+record_tag+">", record_cursor);
            if (record_end < 0)
            {
                // wtf, we got an opening record_tag but not a closing one, finish anyway
                logger.log(Constants.LOG_WARN, "ParseFeedXMLFlat.parse_array incomplete "+record_tag+" XML object");
                break; // quit outermost records loop
            }
            // Ok, we think we have a record between 'record_cursor' and 'record_end'
            // record_cursor is currently pointing at opening '<' of '<record_tag>'
            logger.log(Constants.LOG_DEBUG, "ParseFeed xml_flat record at "+record_cursor);

            // Now loop within this record_tag object picking out the atomic objects <foo>X</foo>

            // Basic technique is to step through the tags, and only make a Json property out of
            // consecutive opening and closing tags that match.
            String current_tag = "";

            while (record_cursor < record_end)
            {
                // Searching forwards inside the 'record_tag' XML object
                // We will find the next <..> or </..> tag
                // Note we are moving the cursor forward each time at the earliest opportunity
                int next_cursor = page.indexOf("<", record_cursor);
                logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array next_cursor at "+next_cursor);
                // This could be the record_tag closing tag
                if (next_cursor >= record_end)
                {
                    logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array no more properties in this "+record_tag);
                    record_cursor = record_end;
                    break;
                }
                // tag could be <foo> or <foo route=66>, either way we want the "foo"
                // tag_close is index of the closing '>'
                int tag_close = page.indexOf(">", next_cursor);
                logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array tag_close at "+tag_close);
                if (tag_close < 0)
                {
                    // wtf, we got a '<' but no '>'
                    logger.log(Constants.LOG_WARN, "ParseFeedXMLFlat.parse_array incomplete tag in "+record_tag+" XML object");
                    record_cursor = page.length(); // force completion of this page
                    break;
                }

                // We found '<'...'>' but if that's actually '<'...'/>' then skip this self-closed object
                if (page.substring(tag_close - 1, tag_close).equals("/"))
                {
                    logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array "+
                               "skipping self-closed "+page.substring(record_cursor, tag_close+1));
                    record_cursor = tag_close;
                    break;
                }

                // See if we find a space character inside the tag (e.g. <foo route=66>)
                int tag_space = page.indexOf(" ", next_cursor);
                
                int tag_end = (tag_space > 0) && (tag_space < tag_close) ? tag_space : tag_close;
                logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array tag_end at "+tag_end);
                // Note we KNOW we at least have a '>' at the end of the record_tag object from the code above
                // So check the tag we found is still within the current record_tag object
                if (tag_end < record_end)
                {
                    // Given a '<'..'>' (but not '<'..'/>')
                    String next_tag = page.substring(++next_cursor, tag_end);
                    logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array "+
                               "found tag "+next_tag);
                    // Process tag here...
                    if (next_tag.equals("/"+current_tag))
                    {
                        logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array "+
                                   "found atomic tag "+current_tag+".."+tag_close);
                        // Create new Json property here...
                        String tag_value = page.substring(record_cursor+1, next_cursor-1);
                        json_record.put(current_tag, tag_value);
                    }

                    current_tag = next_tag;
                    record_cursor = tag_close;
                }
                else
                {
                    logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat.parse_array "+
                               "at record end");
                    record_cursor = record_end;
                } // end if
            } // end while loop for properties within current record

            //json_record.put("feed_data", page);
            // Add the current record to the 'records' result list
            records.add(json_record);
            // shift cursor to the end of the current record before we loop to look for the next record
            record_cursor = record_end;
        }
        logger.log(Constants.LOG_DEBUG, "ParseFeedXMLFlat parse_array completed");
        return records;
     } // end parse_array
} // end ParseFeedXMLFlat

