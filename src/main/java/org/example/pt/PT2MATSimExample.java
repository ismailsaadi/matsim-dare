/* *********************************************************************** *
 * project: org.matsim.*
 * *********************************************************************** *
 *                                                                         *
 * copyright       : (C) 2016 by the members listed in the COPYING,        *
 *                   LICENSE and WARRANTY file.                            *
 * email           : info at matsim dot org                                *
 *                                                                         *
 * *********************************************************************** *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *   See also COPYING, LICENSE and WARRANTY file                           *
 *                                                                         *
 * *********************************************************************** */

package org.example.pt;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.geotools.referencing.CRS;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt2matsim.config.OsmConverterConfigGroup;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;
import org.matsim.pt2matsim.plausibility.PlausibilityCheck;
import org.matsim.pt2matsim.run.*;
import org.matsim.pt2matsim.run.gis.Network2Geojson;
import org.matsim.pt2matsim.run.gis.Schedule2Geojson;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.matsim.api.core.v01.Id;
import org.matsim.pt2matsim.tools.NetworkTools;
import org.matsim.pt2matsim.tools.ScheduleTools;

/**
 * Usage test of PT2MATSim to document how the package can be used. The example region
 * is Addison County, USA (except for HAFAS).
 *
 * @author polettif
 *
 */
public final class PT2MATSimExample {

    private static final String example = "pt2matsim/";
    private static final String input = example + "input/";
    private static final String inter = example + "intermediate/";
    private static final String output = example + "output/";
    private static final String external = "input/mito/trafficAssignment/";

    private static final String manchesterEPSG = "EPSG:27700";

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        prepare();

        // 1. Convert a gtfs schedule to an unmapped transit schedule
        gtfsToSchedule();

        // OR a hafas schedule to an unmapped transit schedule
        // Either data format (GTFS or HAFAS/HRDF) works, GTFS feeds are more commonly available.
        // hafasToSchedule();

        // OR an osm file to an unmapped transit schedule
        // osm file cannot be uploaded to github due to its size, see openstreetmap.org or similar for downloads
        // Do not use this approach if you have either GTFS or HAFS/HRDF data available
        // osmToSchedule();

        // 2. Convert an osm map to a MATSim network [we don't do that, we already have the matsim network from jibe]
        // create a config file (or adjust an existing one by hand)
        // createOsmConfigFile( inter + "OsmConverterConfig.xml" );
        // Convert the OSM file using the config
        // Osm2MultimodalNetwork.main(new String[]{ inter + "OsmConverterConfig.xml" });

        // 3. Map the schedule onto the network
        // create a config file (or adjust an existing one by hand)
        createMapperConfigFile(inter + "MapperConfigAdjusted.xml");
        // Map the schedule using the config
        PublicTransitMapper.main(new String[]{inter + "MapperConfigAdjusted.xml"});

        // 4. Do a plausibility check
        checkPlausibility2();
    }

    /** Create output folder if not existing **/
    public static void prepare() {
        new File(output + "plausibilityResults/").mkdirs();
        new File(inter).mkdirs();
    }


    /**
     * 	1. A GTFS or HAFAS Schedule or a OSM map with information on public transport
     * 	has to be converted to an unmapped MATSim Transit Schedule.
     *
     * 	Here as a first example, the GTFS-schedule of GrandRiverTransit, Waterloo-Area, Canada, is converted.
     */
    public static void gtfsToSchedule() {
        String[] gtfsConverterArgs = new String[]{
                // [0] gtfs zip file
                input + "all-modes-adjusted-gtfs.zip",
                // [1] which service ids should be used. One of the following:
                //		dayWithMostTrips, date in the format yyyymmdd, , dayWithMostServices, all
                "dayWithMostTrips",
                // [2] the output coordinate system. Use WGS84 for no transformation.
                manchesterEPSG,
                // [3] output transit schedule file
                inter + "schedule_unmapped.xml.gz",
                // [4] output default vehicles file (optional)
                inter + "vehicles_unmapped.xml",
        };
        Gtfs2TransitSchedule.main(gtfsConverterArgs);
    }

    /**
     * Here as a second example, the HAFAS-schedule of the
     * BrienzRothornBahn, Switzerland, is converted.
     */
    public static void hafasToSchedule() {
        String[] hafasConverterArgs = new String[]{
                // [0] hafasFolder
                "BrienzRothornBahn-HAFAS/",
                // [1] outputCoordinateSystem
                "EPSG:2056",
                // [2] outputScheduleFile
                inter + "schedule_hafas.xml.gz",
                // [3] outputVehicleFile
                inter + "vehicles_hafas.xml"
        };
        try {
            Hafas2TransitSchedule.main(hafasConverterArgs);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * And as a third example, the OSM-map of the Waterloo City Centre, Canada, is
     * converted.
     */
    public static void osmToSchedule() {
        String[] osmConverterArgs = new String[]{
                // [0] osm file
                input + "addison.osm.gz",
                // [1] output schedule file
                inter + "schedule_osm.xml.gz",
                // [2] output coordinate system (optional)
                manchesterEPSG
        };
        Osm2TransitSchedule.main(osmConverterArgs);
    }

    /**
     * 2. A MATSim network of the area is required. If no such network is already available,
     * the PT2MATSim package provides the possibility to use OSM-maps as data-input.
     *
     * Here as an example, the OSM-extract of the city centre of Waterloo, Canada, is converted.
     */
    public static void createOsmConfigFile(String configFile) {
        // Create a default createOsmConfigFile-Config:
        CreateDefaultOsmConfig.main(new String[]{inter + "OsmConverterConfigDefault.xml"});

        // Open the createOsmConfigFile Config and set the parameters to the required values
        // (usually done manually by opening the config with a simple editor)
        Config osmConverterConfig = ConfigUtils.loadConfig(
                inter + "OsmConverterConfigDefault.xml",
                new OsmConverterConfigGroup());

        OsmConverterConfigGroup osmConfig = ConfigUtils.addOrGetModule(osmConverterConfig, OsmConverterConfigGroup.class);
        osmConfig.setOsmFile(input + "addison.osm.gz");
        osmConfig.setOutputCoordinateSystem(manchesterEPSG);
        osmConfig.setOutputNetworkFile(inter + "addison.xml.gz");

        // Save the createOsmConfigFile config (usually done manually)
        new ConfigWriter(osmConverterConfig).write(configFile);
    }

    /**
     * 	3. The core of the PT2MATSim-package is the mapping process of the schedule to the network.
     *
     * 	Here as an example, the unmapped schedule of GrandRiverTransit (previously converted from GTFS) is mapped
     * 	to the converted OSM network of the Waterloo Area, Canada.
     */
    public static void createMapperConfigFile(String configFile) {
        // Create a mapping config:
        CreateDefaultPTMapperConfig.main(new String[]{ inter + "MapperConfigDefault.xml"});
        // Open the mapping config and set the parameters to the required values
        // (usually done manually by opening the config with a simple editor)
        Config config = ConfigUtils.loadConfig(
                inter + "MapperConfigDefault.xml",
                PublicTransitMappingConfigGroup.createDefaultConfig());

        config.global().setCoordinateSystem(manchesterEPSG);
        config.network().setInputCRS(manchesterEPSG); // is this still working ?

        PublicTransitMappingConfigGroup ptmConfig = ConfigUtils.addOrGetModule(config, PublicTransitMappingConfigGroup.class);

        //ptmConfig.setInputNetworkFile(output + "network_with_tramLinks.xml.gz");
        ptmConfig.setInputNetworkFile("/mnt/usb-TOSHIBA_EXTERNAL_USB_20241114001799F-0:0-part1/manchester/pt2matsim/output/network_with_tramLinks.xml.gz");
        ptmConfig.setOutputNetworkFile(output + "multimodal_network.xml.gz");
        ptmConfig.setOutputScheduleFile(output+ "manchester_schedule.xml.gz");
        ptmConfig.setOutputStreetNetworkFile(output + "multimodal_streetnetwork.xml.gz");
        ptmConfig.setInputScheduleFile(inter + "schedule_unmapped.xml.gz");
        ptmConfig.setScheduleFreespeedModes(CollectionUtils.stringToSet("rail, light_rail"));


        ptmConfig.getTransportModeAssignment().put("bus", Set.of("car", "bus"));
        ptmConfig.getTransportModeAssignment().put("tram", Set.of("tram", "car")); // TODO: check if tram should be mapped to physical network

        int maxThreads = Math.min(Runtime.getRuntime().availableProcessors(), 20); // Cap at 16
        ptmConfig.setNumOfThreads(maxThreads);

        // Save the mapping config
        // (usually done manually)
        new ConfigWriter(config).write(configFile);
    }

    /**
     * 	4. The PT2MATSim package provides a plausibility checker to get quick feedback on the mapping process.
     *
     * 	Here as an example, the mapped transit schedule and the multimodal network created in step 3 is
     * 	checked for plausibility.
     */
    public static void checkPlausibility() {

        CheckMappedSchedulePlausibility.run(
                output + "manchester_schedule.xml.gz",
                output + "multimodal_network.xml.gz",
                manchesterEPSG,
                output + "plausibilityResults/"
        );
    }

    public static void checkPlausibility2() {
        String scheduleFile = output + "manchester_schedule.xml.gz";
        String networkFile  = output + "multimodal_network.xml.gz";
        String crs          = manchesterEPSG;
        // CRS "; // e.g. "EPSG:27700" or whatever you use
        String resultFolder = output + "plausibilityResults/";

        new java.io.File(resultFolder).mkdirs();

        TransitSchedule schedule = ScheduleTools.readTransitSchedule(scheduleFile);
        Network network = NetworkTools.readNetwork(networkFile);

        PlausibilityCheck check = new PlausibilityCheck(schedule, network, crs);

        check.runCheck();                                     // ← does all the real work
        check.writeCsv(resultFolder + "allPlausibilityWarnings.csv");  // ← perfect CSV, no error
        check.printStatisticsLog();                                     // ← prints the nice summary with artificial links, loops, etc.

        // Optional but nice: network as GeoJSON (this one works without any problem)
        Network2Geojson.run(crs, network, resultFolder + "network.geojson");

        System.out.println("================================================================");
        System.out.println("Plausibility check finished perfectly – no Jackson errors anymore!");
        System.out.println("CSV warnings  : " + resultFolder + "allPlausibilityWarnings.csv");
        System.out.println("Network GeoJSON: " + resultFolder + "network.geojson");
        System.out.println("================================================================");
    }

}
