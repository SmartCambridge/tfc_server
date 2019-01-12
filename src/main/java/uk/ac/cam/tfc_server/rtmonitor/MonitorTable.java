package uk.ac.cam.tfc_server.rtmonitor;

import java.util.*;
import java.time.*;
import java.time.format.*;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.ext.web.handler.sockjs.SockJSSocket;

    // ****************************************************************************************
    // ****************************************************************************************
    // MonitorTable contains the data structure for each running Monitor
    // This has been implemented as a Class on the possibility that some broader-based access functions
    // may be needed (e.g. find a Monitor given a subscriber) but in the interim this Class could
    // equally just be the simple Hashtable defined within (i.e. the variable 'monitors')
    class MonitorTable {

        private Hashtable<String, Monitor> monitors;

        // Constructor to create a new MonitorTable.  This verticle only has one, in global var 'monitors'
        MonitorTable() {
            monitors = new Hashtable<String,Monitor>();
        }

        // The verticle supports multiple monitors, each is created via this 'add()' function.
        public void add(String uri, String address, String records_array, String record_index)
        {
            Monitor monitor = new Monitor(address, records_array, record_index);
            monitors.put(uri, monitor);
        }

        // add_client() is called when a client browser connects to a websocket
        public String add_client(String uri, 
                                 String UUID, 
                                 SockJSSocket sock, 
                                 JsonObject sock_msg,
                                 RTToken token)
        {
            return monitors.get(uri).add_client(UUID, sock, sock_msg, token);
        }

        // add_subscription() is called when a websocket 'rt_subscribe" subscription arrives
        public void add_subscription(String uri, String UUID, JsonObject sock_msg)
        {
            monitors.get(uri).add_subscription(UUID, sock_msg);
        }

        // remove_subscription() is called when a websocket 'rt_unsubscribe' message arrives
        public void remove_subscription(String uri, String UUID, JsonObject sock_msg)
        {
            monitors.get(uri).remove_subscription(UUID, sock_msg);
        }

        // handle an incoming "rt_request" for client pull of data
        public void handle_rt_request(String uri, String UUID, JsonObject sock_msg)
        {
            monitors.get(uri).handle_rt_request(UUID, sock_msg);
        }

        // remove_client() is called when a websocket is closed
        public void remove_client(String uri, String UUID)
        {
            monitors.get(uri).remove_client(UUID);
        }

        public ClientTable get_clients(String uri)
        {
            return monitors.get(uri).clients;
        }

        // update_state() will be called when a new message arrives on the monitored eventbus address
        public void update_state(String uri, JsonObject msg)
        {
            monitors.get(uri).update_state(msg);
        }

        // update_clients() will be called after update_state() when new data arrives on the eventbus
        public void update_clients(String uri, JsonObject msg)
        {
            monitors.get(uri).update_clients(msg);
        }

        public Set<String> keySet()
        {
            return monitors.keySet();
        }

        public Monitor get(String key)
        {
            return monitors.get(key);
        }

        public int size()
        {
            return monitors.size();
        }
    } // end class MonitorTable
        
