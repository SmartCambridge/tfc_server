package uk.ac.cam.tfc_server.feedcarto;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import uk.ac.cam.tfc_server.util.Log;

public class FeedCarto extends AbstractVerticle {
    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedcarto"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    private String FEEDHANDLER_ADDRESS; // config MODULE_NAME.feedhandler.address
    private String CARTO_API_KEY;

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    private EventBus eb = null;

    @Override
    public void start(Future<Void> fut) throws Exception {
        // load FeedCarto initialization values from config()
        if (!get_config()) {
            Log.log_err("FeedCarto "+ MODULE_ID + " failed to load initial config()");
            vertx.close();
            return;
        }

        System.out.println("FeedCarto: " + MODULE_ID + " started, listening to "+FEEDHANDLER_ADDRESS);

        eb = vertx.eventBus();

        eb.consumer(FEEDHANDLER_ADDRESS, message -> {
            System.out.println("FeedCarto got message from " + FEEDHANDLER_ADDRESS);
            //debug
            JsonObject feed_message = new JsonObject(message.body().toString());
            JsonArray entities = feed_message.getJsonArray("entities");
            System.out.println("FeedCarto feed_vehicle message #records: "+String.valueOf(entities.size()));
            handle_feed(feed_message);
        });

        // send periodic "system_status" messages
        vertx.setPeriodic(SYSTEM_STATUS_PERIOD, id -> {
            eb.publish(EB_SYSTEM_STATUS,
                    "{ \"module_name\": \""+MODULE_NAME+"\"," +
                            "\"module_id\": \""+MODULE_ID+"\"," +
                            "\"status\": \"UP\"," +
                            "\"status_amber_seconds\": "+String.valueOf( SYSTEM_STATUS_AMBER_SECONDS ) + "," +
                            "\"status_red_seconds\": "+String.valueOf( SYSTEM_STATUS_RED_SECONDS ) +
                            "}" );
        });
    }

    // Load initialization global constants defining this Zone from config()
    private boolean get_config()
    {
        // config() values needed by all TFC modules are:
        //   module.name - usually "feedcarto"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages

        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null) {
            Log.log_err("FeedCarto: module.name config() not set");
            return false;
        }

        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null) {
            Log.log_err("FeedCarto."+MODULE_ID+": module.id config() not set");
            return false;
        }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null) {
            Log.log_err("FeedCarto."+MODULE_ID+": eb.system_status config() not set");
            return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null) {
            Log.log_err("FeedCarto."+MODULE_ID+": eb.manager config() not set");
            return false;
        }

        FEEDHANDLER_ADDRESS = config().getString(MODULE_NAME+".feedhandler.address");
        if (FEEDHANDLER_ADDRESS == null) {
            Log.log_err("FeedCarto."+MODULE_ID+": "+MODULE_NAME+".feedhandler.address config() not set");
            return false;
        }

        CARTO_API_KEY = config().getString("carto_api_key");
        if (CARTO_API_KEY == null) {
            Log.log_err("FeedCarto: carto_api_key not set");
            return false;
        }

        return true;
    }

    private void handle_feed(JsonObject feed_message) {
        String url = "https://amc203.carto.com/api/v2/sql";
        String charset = StandardCharsets.UTF_8.name();
        JsonArray entities = feed_message.getJsonArray("entities");
        String[] sql_entries = new String[entities.size()];

        for (int i = 0; i < entities.size(); i++)
        {
            JsonObject json_record = entities.getJsonObject(i);
            sql_entries[i] = String.format("(to_timestamp(%d), '%s', '%s', '%s', '%s', %f, %f, %f, %d, '%s')",
                json_record.getLong("timestamp"),
                json_record.getString("vehicle_id",""), json_record.getString("label",""),
                json_record.getString("route_id",""), json_record.getString("trip_id",""),
                json_record.getFloat("latitude"), json_record.getFloat("longitude"),
                json_record.getFloat("bearing",0.0f), json_record.getLong("current_stop_sequence",0L),
                json_record.getString("stop_id","")) ;
        }

        try {
            String query = URLEncoder.encode(String.format("INSERT INTO bus_data (timestamp, vehicle_id, label, " +
                "route_id, trip_id, latitude, longitude, bearing, current_stop_sequence, stop_id) VALUES %s",
                String.join(", ", sql_entries)), charset);
            query = String.format("q=%s&api_key=%s", query, CARTO_API_KEY);

            //System.out.println("FeedCarto POST: "+query);
            byte[] out = query.getBytes(charset);
            URLConnection connection = new URL(url).openConnection();
            HttpURLConnection http = (HttpURLConnection)connection;
            http.setRequestMethod("POST"); // PUT is another valid option
            http.setDoOutput(true);
            http.setFixedLengthStreamingMode(out.length);
            http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            http.connect();
            http.getOutputStream().write(out);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
