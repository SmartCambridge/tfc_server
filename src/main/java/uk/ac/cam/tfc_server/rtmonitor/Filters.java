package uk.ac.cam.tfc_server.rtmonitor;

import java.util.*;
import java.time.*;
import java.time.format.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

    // List of filters in a given subscription
    class Filters {
        private ArrayList<Filter> filters;

        public JsonArray msg;

        // Construct new Filters object from "filters" JsonArray in client subscription
        Filters(JsonArray filters)
        {
            msg = filters;

            this.filters = new ArrayList<Filter>();

            if (filters != null)
            {
                // initialize the filters ArrayList with JsonObject filters from msg 
                for (int filter_num=0; filter_num<filters.size(); filter_num++)
                {
                    this.filters.add(new Filter(filters.getJsonObject(filter_num)));
                }
            }
        }

        // Test (AND) filters against a JsonObject data record
        public boolean test(JsonObject record)
        {
            
            // An empty filters array always succeeds
            if (filters.size() == 0)
            {
                return true;
            }

            // start with 'filter_passed = true' and set to false if any filter fails
            boolean filters_passed = true;

            // test all the filters on the eventbus_msg and send on websocket if it passes
            for (int filter_num=0; filter_num<filters.size(); filter_num++)
            {
                Filter filter = filters.get(filter_num);

                if (!filter.test(record))
                {
                    filters_passed = false;
                }
            }

            //logger.log(Constants.LOG_DEBUG, MODULE_NAME+"."+MODULE_ID+
            //         ": tested filter"+filters.toString()+(filters_passed?"passed":"failed"));
            return filters_passed;
        } // end Filters.test()


    } // end class Filters

