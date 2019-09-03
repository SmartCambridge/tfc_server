package uk.ac.cam.tfc_server.feedmaker;

//**********************************************************************
//**********************************************************************
//   ParseParkingDatex.java
//
// Convert the data read from the http 'get' of DatexII XML for parking occupancy
// into an Adaptive City Platform eventbus message.
//
// Author: Ian Lewis ijl20@cam.ac.uk
//
//**********************************************************************
//**********************************************************************

// *********************************************************************
// ************** SAMPLE XML FROM DATEX II FEED ************************
// *********************************************************************

// Note for now we only need the "parkingFacilityStatus" elements, and this
// tag name is RE-USED inside that element (WTF?).

/*
<d2LogicalModel xmlns="http://datex2.eu/schema/2/2_0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" modelBaseVersion="2">
  ...
  <payloadPublication xsi:type="GenericPublication" lang="en">
    <publicationTime>2019-09-02T16:01:15.3144836+01:00</publicationTime>
    ...
    <genericPublicationExtension>
      <parkingFacilityTablePublication>
        ...
        <parkingFacilityTable>
          <parkingFacilityTableVersionTime>2008-11-01T16:00:00</parkingFacilityTableVersionTime>
          <parkingArea id="CAMB-CP001" version="2.2">
            <parkingAreaName>
              <values>
                <value lang="en">Grand Arcade</value>
              </values>
            </parkingAreaName>
            <totalParkingCapacity>890</totalParkingCapacity>
            <area>
              <locationForDisplay>
                <latitude>52.2037277</latitude>
                <longitude>0.12105903</longitude>
              </locationForDisplay>
            </area>
            ...
          </parkingArea>
          ...
        </parkingFacilityTable>
        <!-- MORE parkingFacilityTable entries for other car parks -->
      </parkingFacilityTablePublication>
      <parkingFacilityTableStatusPublication>
        ....
        <parkingAreaStatus>
          <parkingAreaReference id="CAMB-CP001" version="2.2" targetClass="ParkingArea"/>
          <parkingFacilityStatus>
            <parkingFacilityExitRate>0</parkingFacilityExitRate>
            <parkingFacilityFillRate>1</parkingFacilityFillRate>
            <parkingFacilityOccupancy>170</parkingFacilityOccupancy>
            <parkingFacilityOccupancyTrend>decreasing</parkingFacilityOccupancyTrend>
            <parkingFacilityQueuingTime>0</parkingFacilityQueuingTime>
            <parkingFacilityReference id="CAMB-CP001" version="2.2" targetClass="ParkingFacility"/>
            <parkingFacilityStatus>open</parkingFacilityStatus>
            <parkingFacilityStatusTime>2019-09-02T16:00:02</parkingFacilityStatusTime>
            <assignedParkingSpacesStatus index="0">
              <assignedParkingSpacesStatus>
                <numberOfVacantAssignedParkingSpaces>720</numberOfVacantAssignedParkingSpaces>
              </assignedParkingSpacesStatus>
            </assignedParkingSpacesStatus>
          </parkingFacilityStatus>
        </parkingAreaStatus>
        <!-- MORE parkingAreaStatus entries for other car parks -->
      </parkingFacilityTableStatusPublication>
    </genericPublicationExtension>
  </payloadPublication>
</d2LogicalModel>

*/

// *********************************************************************
// ************** SAMPLE EVENTBUS MESSAGE       ************************
// *********************************************************************

/*
{
    "feed_id": "cam_park_rss",
    "filename": "1552946533.851_2019-03-18-22-02-13",
    "filepath": "2019/03/18",
    "module_id": "park_rss",
    "module_name": "feedmaker",
    "msg_type": "feed_car_parks",
    "request_data": [
        {
            "parking_id": "grand-arcade-car-park",
            "spaces_capacity": 890,
            "spaces_free": 864,
            "spaces_occupied": 26
        },
        ...
    ],
    "ts": 1552946533
}

*/
// java classes
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.DocumentBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;
import org.w3c.dom.Element;
import java.io.File;
import java.io.IOException;
import javax.xml.parsers.ParserConfigurationException;
import org.xml.sax.SAXException;
import org.xml.sax.InputSource;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

// vertx classes
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;
import io.vertx.core.buffer.Buffer;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

public class ParseParkingDatex implements FeedParser {

    private String area_id;

    private JsonObject config;

    private Log logger;

    DocumentBuilderFactory doc_factory;

    DocumentBuilder doc_builder;

    Map<String, String> id_map; // mappings from Datex II "CAMB-CP001" to "grand-arcade-car-park"

    ParseParkingDatex(JsonObject config, Log logger)
    {
        this.config = config;

        this.area_id = config.getString("area_id","");

        this.logger = logger;

        // The XML DatexII id mapping table from new to old for the initial car parks
        // We are continuing to use the original (e.g. rss) keys for the those parking id's
        id_map = new HashMap<String, String>() {{
            put("CAMB-CP001", "grand-arcade-car-park");
            put("CAMB-CP002", "grafton-east-car-park");
            put("CAMB-CP003", "grafton-west-car-park");
            put("CAMB-CP004", "park-street-car-park");
            put("CAMB-CP005", "queen-anne-terrace-car-park");
            put("CAMB-CP006", "madingley-road-park-and-ride");
            put("CAMB-CP007", "trumpington-road-park-and-ride");
            put("CAMB-CP008", "babraham-park-and-ride");
            put("CAMB-CP009", "milton-park-and-ride");
            put("CAMB-CP010", "newmarket-road-front-park-and-ride");
            put("CAMB-CP011", "newmarket-road-rear-park-and-ride");
        }};

        try
        {
            doc_factory = DocumentBuilderFactory.newInstance();

            doc_builder = doc_factory.newDocumentBuilder();
        }
        catch (javax.xml.parsers.ParserConfigurationException e)
        {
            logger.log(Constants.LOG_FATAL, "ParseParkingDatex XML parser load failure");
        }

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex started");
    }

    // Get the Datex II parking id from the XML node.
    // Check whether it maps to an old id, if so return that, otherwise return it.
    String get_id(Element e)
    {
        Node ref = e.getElementsByTagName("parkingFacilityReference").item(0);

        String datex_id = ((Element) ref).getAttribute("id");
        String old_id = id_map.get(datex_id);
        if (old_id != null)
        {
            return old_id;
        }
        return datex_id;
    }

    // Get the integer value of some tag within an
    int get_int(Element e, String tag)
    {
        Node ref = e.getElementsByTagName(tag).item(0);

        String tag_value = ref.getTextContent();

        return Integer.parseInt(tag_value);
    }

    // Here is where we try and parse the XML source and return a JsonArray for "request_data"
    // Note the other fields (e.g. ts) are added in the Feedmaker
    public JsonObject parse(Buffer buf) throws Exception
    {

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex.parse() called");

        // records JsonArray will hold the array of "request_data" JsonObjects
        JsonArray records = new JsonArray();

        // Use the Java DOM xml parser to parse the data input in a single pass.
        // will throw exception on bad parse, caught by FeedMaker
        Document doc = doc_builder.parse(new InputSource(new StringReader(buf.toString())));

        // CAUTION: the "parkingFacilityStatus" elements ALSO have "parkingFacilityStatus" sub-elements.
        NodeList parking_nodes = doc.getElementsByTagName("parkingFacilityStatus");

        // Each node has the structure:
        /*
          <parkingFacilityStatus>
            ...
            <parkingFacilityOccupancy>170</parkingFacilityOccupancy>
            <parkingFacilityReference id="CAMB-CP001" version="2.2" targetClass="ParkingFacility"/>
            <assignedParkingSpacesStatus index="0">
              <assignedParkingSpacesStatus>
                <numberOfVacantAssignedParkingSpaces>720</numberOfVacantAssignedParkingSpaces>
            <!-- etc -->
        */
        // In json:
        // {
        //    "parking_id": "grand-arcade-car-park",
        //    "spaces_capacity": 890,
        //    "spaces_free": 864,
        //    "spaces_occupied": 26
        // }

        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex parking count " +parking_nodes.getLength());

        // iterate through the "parkingFacilityStatus" nodes, including the same-named sub-elements
        for (int i = 0; i < parking_nodes.getLength(); i++)
        {
            Node parking_node = parking_nodes.item(i);

            // We use 'number of child elements' to make sure we only use the higher-level
            // "parkingFacilityStatus" elements
            if (parking_node.getChildNodes().getLength() > 2)
            {
                // Here we have a "parkingFacilityStatus" parent node for one car park, so get data
                Element e = (Element) parking_node;
                String id = get_id(e); // get parking_id, e.g. "CAMB-011" or "grand-arcade-car-park"
                // Get occupancy / free counts
                int occupied = get_int(e, "parkingFacilityOccupancy");
                int free = get_int(e,"numberOfVacantAssignedParkingSpaces");
                //logger.log(Constants.LOG_DEBUG, "ParseParkingDatex parking "+id+" "+free + "/"+(free + occupied));

                // Easy, got the data, so build JsonObject and add to "request_data" array
                JsonObject record = new JsonObject();
                record.put("parking_id", id);
                record.put("spaces_capacity", free+occupied);
                record.put("spaces_free", free);
                record.put("spaces_occupied", occupied);

                records.add(record);
            }
        }

        // "records" should now contain JsonObjects for all car parks, so add to "request_data" and return.
        logger.log(Constants.LOG_DEBUG, "ParseParkingDatex xml record");

        JsonObject msg = new JsonObject();
        msg.put("request_data", records);
        return msg;
    }

} // end ParseParkingDatex

