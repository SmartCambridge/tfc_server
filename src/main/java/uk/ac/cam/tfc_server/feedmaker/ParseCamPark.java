package uk.ac.cam.tfc_server.feedmaker;

import java.util.ArrayList;
import java.util.HashMap;

import io.vertx.core.json.JsonObject;
import io.vertx.core.json.JsonArray;

// other tfc_server classes
import uk.ac.cam.tfc_server.util.Log;
import uk.ac.cam.tfc_server.util.Constants;

// ********************************************************************************
// **************  cam_park_carpark feed template   *******************************
// ********************************************************************************
/*
  <tr class="">
  <td style="text-align:center;width=35%;">
  <img title="Car parks UK" alt="" src="icons/car_park_light.gif" />
  </td>
  <td style="width:35%;">
  <a title="Carpark" href="/carparkdetail.aspx?t=carpark&amp;r=CAMB-CP001&amp;X=545041&amp;Y=258285&amp;format=xhtml">Grand Arcade</a>
  </td>
  <td style="text-align:center;">
  <a class="LocationLink" title="View on Map" href="/map.aspx?maplayers=car_park&amp;X=545041&amp;Y=258285&amp;ZoomLevel=6">View on Map</a>
  </td>
  <td class="sortable-numeric" ts_type="number" style="width:20%">812</td>
  <td class="sortable-numeric" ts_type="number" style="width:15%;">890</td>
  <td align="center" class="cargraphiccell sortable-numeric" ts_type="number" style="width:15%">8%<div class="carpark-occupancy-percent"><div class="carpark-occupancy-percentdiv" style="width:8%;"><span class="carpark-occupancy-percentspan">8</span></div></div></td>
  <td class="sortable-text" style="width:25%;">Filling</td>
  <td class="sortable-text">OPEN</td>
  </tr>
*/

public class ParseCamPark {

    public ArrayList<ParseFeed.RecordTemplate> get_record_templates()
    {
        ArrayList<ParseFeed.RecordTemplate> cam_park_carpark = new ArrayList<ParseFeed.RecordTemplate>();
        // Define common fields for this feed (i.e. fields the same for every RecordTemplate)

        ArrayList<ParseFeed.FieldTemplate> common_fields = new ArrayList<ParseFeed.FieldTemplate>();

        // 'spaces_free' is a <td> entry with the number between 'width:20%">' and '</td>'
        common_fields.add(new ParseFeed.FieldTemplate("spaces_free","int",null,0,"width:20%\">","</td>", false));

        // Grand Arcade Car Park
        // RecordTemplate for Grand Arcade Car Park from cam_park_carpark (similar for each Car Park in feed as below)
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ParseFeed.RecordTemplate ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        // The section of text containing Grand Arcade occupancy has the string "Grand Arcade" before the required values.
        ft.tag_start = "Grand Arcade";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grand-arcade-car-park",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,890,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This ParseFeed.RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // Madingley Road P&R
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Madingley Road";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","madingley-road-park-and-ride",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,930,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This ParseFeed.RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // Newmarket Road Front
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Newmarket Rd Front";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","newmarket-road-front-park-and-ride",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,259,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This ParseFeed.RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // Queen Anne Car Park
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Queen Anne";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","queen-anne-terrace-car-park",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,540,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // Grafton West
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Grafton West";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grafton-west-car-park",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,280,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // Park Street
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Park Street";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","park-street-car-park",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,375,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // Newmarket Road Rear
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Newmarket Rd Rear";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","newmarket-road-rear-park-and-ride",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,614,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // Grafton East
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Grafton East";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","grafton-east-car-park",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,780,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // P&R Milton
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Milton";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","milton-park-and-ride",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,800,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // P&R Trumpington
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Trumpington";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","trumpington-park-and-ride",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,1340,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        // P&R Babraham
        // Pre-populate the template with fields common to all car parks (i.e. 'common_fields' arraylist above)
        ft = new ParseFeed.RecordTemplate(common_fields);
        // Define the text strings (tags) that define the start and end of the text block in the field.
        ft.tag_start = "Babraham";
        // The required section of text (i.e. 'record') ends with the first '</item>' after the start string.
        ft.tag_end = "</tr>";
        // parking_id and spaces_capacity initialized with value in template
        // This 'fixed_string' value for parking_id ensures that JSON value in the result is always what is required
        ft.fields.add(new ParseFeed.FieldTemplate("parking_id","fixed_string","babraham-park-and-ride",0,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_capacity","fixed_int",null,1500,null,null,true));
        ft.fields.add(new ParseFeed.FieldTemplate("spaces_occupied","calc_minus",null,0,"spaces_capacity","spaces_free", true));
        // This RecordTemplate is completed, so add to the "cam_park_carpark" ArrayList
        cam_park_carpark.add(ft);

        return cam_park_carpark;
    } // end get_record_templates
} // end ParseCamPark
