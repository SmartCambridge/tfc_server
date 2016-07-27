package uk.ac.cam.tfc_server.msgfiler;

// **********************************************************************************************
// **********************************************************************************************
// FilerFilter is created with field/compare/value paramters suitable for filtering JsonObject messages
// and provides a 'match' method which returns 'true' if a given message meets those filter requirements
// **********************************************************************************************
// **********************************************************************************************

import io.vertx.core.json.JsonObject;

public class FilerFilter {
    public String field;
    public String compare;
    public String value;

    public FilerFilter(JsonObject source_filter)
    {
        field = source_filter.getString("field");
        compare = source_filter.getString("compare");
        value = source_filter.getString("value");
    }

    // match returns 'true' if msg.field compares ok with value
    // e.g. if field="module_id", compare="contains", value="zone"
    // then match will return true if this msg.module_id contains the string "zone"
    public boolean match(JsonObject msg)
    {
        String msg_value = msg.getString(field);
        if (msg_value == null)
        {
            return false;
        }

        switch (compare)
        {
            case "=":
                return msg_value.equals(value);

            case ">":
                return msg_value.compareTo(value) > 0;

            case "<":
                return msg_value.compareTo(value) < 0;

            case "contains":
                return msg_value.contains(value);

            default:
        }

        return false;
    } // end match()

    // return a string representation of this filter, e.g. "msg_type = zone_completion"
    public String toString()
    {
        return field + " " + compare + " " + value;
    }
} // end class FilerFilter

    
