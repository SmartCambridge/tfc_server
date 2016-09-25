package uk.ac.cam.tfc_server.feeddb;

import com.datastax.driver.core.Cluster;
import info.archinnov.achilles.annotations.Column;
import info.archinnov.achilles.annotations.CompileTimeConfig;
import info.archinnov.achilles.annotations.PartitionKey;
import info.archinnov.achilles.annotations.Table;
import info.archinnov.achilles.generated.ManagerFactory;
import info.archinnov.achilles.generated.ManagerFactoryBuilder;
import info.archinnov.achilles.generated.manager.BusData_Manager;
import info.archinnov.achilles.type.CassandraVersion;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import uk.ac.cam.tfc_server.util.Log;

public class FeedDB extends AbstractVerticle {
    // from config()
    private String MODULE_NAME;       // config module.name - normally "feedhandler"
    private String MODULE_ID;         // config module.id
    private String EB_SYSTEM_STATUS;  // config eb.system_status
    private String EB_MANAGER;        // config eb.manager
    private String FEEDHANDLER_ADDRESS; // config MODULE_NAME.feedhandler.address

    private final int SYSTEM_STATUS_PERIOD = 10000; // publish status heartbeat every 10 s
    private final int SYSTEM_STATUS_AMBER_SECONDS = 15;
    private final int SYSTEM_STATUS_RED_SECONDS = 25;

    private EventBus eb = null;

    private BusData_Manager manager;

    @CompileTimeConfig(cassandraVersion = CassandraVersion.CASSANDRA_3_5)
    public interface AchillesConfig {

    }

    @Table(table="busdata")
    public class BusData
    {
        @PartitionKey
        private Long timestamp;

        @Column
        private String vehicle_id;

        @Column
        private String label;

        @Column
        private String route_id;

        @Column
        private String trip_id;

        @Column
        private Float latitude;

        @Column
        private Float longitude;

        @Column
        private Float bearing;

        @Column
        private Long current_stop_sequence;

        @Column
        private String stop_id;

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }

        public String getVehicle_id() {
            return vehicle_id;
        }

        public void setVehicle_id(String vehicle_id) {
            this.vehicle_id = vehicle_id;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getRoute_id() {
            return route_id;
        }

        public void setRoute_id(String route_id) {
            this.route_id = route_id;
        }

        public String getTrip_id() {
            return trip_id;
        }

        public void setTrip_id(String trip_id) {
            this.trip_id = trip_id;
        }

        public Float getLatitude() {
            return latitude;
        }

        public void setLatitude(Float latitude) {
            this.latitude = latitude;
        }

        public Float getLongitude() {
            return longitude;
        }

        public void setLongitude(Float longitude) {
            this.longitude = longitude;
        }

        public Float getBearing() {
            return bearing;
        }

        public void setBearing(Float bearing) {
            this.bearing = bearing;
        }

        public Long getCurrent_stop_sequence() {
            return current_stop_sequence;
        }

        public void setCurrent_stop_sequence(Long current_stop_sequence) {
            this.current_stop_sequence = current_stop_sequence;
        }

        public String getStop_id() {
            return stop_id;
        }

        public void setStop_id(String stop_id) {
            this.stop_id = stop_id;
        }
    }


    @Override
    public void start(Future<Void> fut) throws Exception {
        // load FeedDB initialization values from config()
        if (!get_config()) {
            Log.log_err("FeedDB "+ MODULE_ID + " failed to load initial config()");
            vertx.close();
            return;
        }

        Cluster cluster = Cluster.builder().build();
        ManagerFactory managerFactory = ManagerFactoryBuilder.builder(cluster)
                .withDefaultKeyspaceName("Test Keyspace").doForceSchemaCreation(true).build();

        manager = managerFactory.forBusData();



        System.out.println("FeedDB: " + MODULE_ID + " started, listening to "+FEEDHANDLER_ADDRESS);

        eb = vertx.eventBus();

        eb.consumer(FEEDHANDLER_ADDRESS, message -> {
            System.out.println("FeedDB got message from " + FEEDHANDLER_ADDRESS);
            //debug
            JsonObject feed_message = new JsonObject(message.body().toString());
            JsonArray entities = feed_message.getJsonArray("entities");
            System.out.println("FeedDB feed_vehicle message #records: "+String.valueOf(entities.size()));

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
        //   module.name - usually "feeddb"
        //   module.id - unique module reference to be used by this verticle
        //   eb.system_status - String eventbus address for system status messages
        //   eb.manager - eventbus address for manager messages

        MODULE_NAME = config().getString("module.name");
        if (MODULE_NAME == null) {
            Log.log_err("FeedDB: module.name config() not set");
            return false;
        }

        MODULE_ID = config().getString("module.id");
        if (MODULE_ID == null) {
            Log.log_err("FeedDB."+MODULE_ID+": module.id config() not set");
            return false;
        }

        EB_SYSTEM_STATUS = config().getString("eb.system_status");
        if (EB_SYSTEM_STATUS == null) {
            Log.log_err("FeedDB."+MODULE_ID+": eb.system_status config() not set");
            return false;
        }

        EB_MANAGER = config().getString("eb.manager");
        if (EB_MANAGER == null) {
            Log.log_err("FeedDB."+MODULE_ID+": eb.manager config() not set");
            return false;
        }

        FEEDHANDLER_ADDRESS = config().getString(MODULE_NAME+".feedhandler.address");
        if (FEEDHANDLER_ADDRESS == null) {
            Log.log_err("FeedDB."+MODULE_ID+": "+MODULE_NAME+".feedhandler.address config() not set");
            return false;
        }

        return true;
    }

    private void handle_feed(JsonObject feed_message) {
        JsonArray entities = feed_message.getJsonArray("entities");
        for (int i = 0; i < entities.size(); i++)
        {
            JsonObject json_record = entities.getJsonObject(i);
            BusData busdata = new BusData();
            busdata.setTimestamp(json_record.getLong("timestamp"));
            busdata.setVehicle_id(json_record.getString("vehicle_id",""));
            busdata.setLabel(json_record.getString("label",""));
            busdata.setRoute_id(json_record.getString("route_id",""));
            busdata.setTrip_id(json_record.getString("trip_id",""));
            busdata.setLatitude(json_record.getFloat("latitude"));
            busdata.setLongitude(json_record.getFloat("longitude"));
            busdata.setBearing(json_record.getFloat("bearing",0.0f));
            busdata.setCurrent_stop_sequence(json_record.getLong("current_stop_sequence",0L));
            busdata.setStop_id(json_record.getString("stop_id",""));
            manager.crud().insert(busdata).execute();
        }
    }

}
