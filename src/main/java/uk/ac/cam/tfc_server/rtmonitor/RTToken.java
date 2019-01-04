package uk.ac.cam.tfc_server.rtmonitor;

import java.time.*;
import java.time.format.*;

import io.vertx.core.json.JsonObject;

import uk.ac.cam.tfc_server.util.Constants;
import uk.ac.cam.tfc_server.util.Log;

// An RTToken holds a 'cached' version of the token used by a client making a connection.
// The property .client_token holds the actual (decrypted) JsonObject from the client.

class RTToken {
    String token_hash;              // hash key assigned to this token by RTMonitor (derived from encrypt string)
    JsonObject client_token;        // The decrypted 'original' client token
    public ZonedDateTime issued;    // Datetime token was created. iso8601 converted to native datetime
    public ZonedDateTime expires;   // Datetime token expires
    public String origin;           // 'Origin' header from the client at time of connection
    public int uses;                // Max permitted number of connects allowed with this token
    public int use_count;           // Accumulated count of the number of times token has been used.

    Log logger;

    RTToken(String token_hash, JsonObject client_token, String client_origin)
    {
        logger = new Log(RTMonitor.LOG_LEVEL);

        this.token_hash = token_hash;

        this.client_token = client_token;

        issued = ZonedDateTime.parse(client_token.getString("issued"));

        expires = ZonedDateTime.parse(client_token.getString("expires"));

        origin = client_origin;

        uses = Integer.parseInt(client_token.getString("uses"));

        use_count = 1;
    }

    public boolean check()
    {
        // get the current time, will check the clients against this
        ZonedDateTime now = ZonedDateTime.now(Constants.PLATFORM_TIMEZONE);

        if (now.isAfter(expires))
        {
            logger.log(Constants.LOG_DEBUG, 
                       "RTMonitor.RTToken expired "+origin+" "+client_token.encodePrettily());
            return false;
        }

        return true;
    }

}

