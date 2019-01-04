package uk.ac.cam.tfc_server.rtmonitor;

import java.time.*;
import java.time.format.*;
import java.util.*;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

import uk.ac.cam.tfc_server.util.Constants;

    // Subscription to the eventbus message data
    // Each eventbus message may contain a JsonArray of multiple data records
    // Each client can have multiple subscriptions
    class Subscription {
        public String request_id;
        public boolean key_is_record_index; // Optimization flag if subscription is 
                                            // filtering on 'primary key'

        public JsonObject msg; // 'rt_subscribe' websocket message when subscription was requested
        public Filters filters; // The parsed 'filters' data given in the websocket request
        public int record_count; // The accumulated count of data records that have
                                 // been sent via this subscription
        public ZonedDateTime created;

        // Construct a new Subscription
        Subscription(JsonObject msg, String request_id, boolean key_is_record_index)
        {
            this.msg = msg;

            this.request_id = request_id;

            this.key_is_record_index = key_is_record_index;

            record_count = 0;

            created = ZonedDateTime.now(Constants.PLATFORM_TIMEZONE);

            try
            {
                JsonArray filters_property = msg.getJsonArray("filters", new JsonArray());
                filters = new Filters(filters_property);
            }
            catch (ClassCastException e)
            {
                filters = new Filters(new JsonArray());
            }
        }

        public String toString()
        {
            return msg.toString();
        }

        public String toHtml()
        {
            return "<tr class='subscription'>"+
                   "<td>"+RTMonitor.format_date(created)+"&nbsp;"+RTMonitor.format_time(created)+"</td>"+
                   "<td>"+record_count+"</td>"+
                   "<td>"+msg.toString()+"</td>"+
                   "</tr>";
        }
    } // end class Subscription

