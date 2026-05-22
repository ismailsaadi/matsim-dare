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

package en.phm.pt;

import org.matsim.api.core.v01.network.Network;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.ConfigWriter;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.pt.transitSchedule.api.TransitSchedule;
import org.matsim.pt2matsim.config.OsmConverterConfigGroup;
import org.matsim.pt2matsim.config.PublicTransitMappingConfigGroup;
import org.matsim.pt2matsim.plausibility.PlausibilityCheck;
import org.matsim.pt2matsim.run.CreateDefaultOsmConfig;
import org.matsim.pt2matsim.run.CreateDefaultPTMapperConfig;
import org.matsim.pt2matsim.run.Gtfs2TransitSchedule;
import org.matsim.pt2matsim.run.Hafas2TransitSchedule;
import org.matsim.pt2matsim.run.Osm2TransitSchedule;
import org.matsim.pt2matsim.run.PublicTransitMapper;
import org.matsim.pt2matsim.run.gis.Network2Geojson;
import org.matsim.pt2matsim.tools.NetworkTools;
import org.matsim.pt2matsim.tools.ScheduleTools;

import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * End-to-end pipeline for converting Greater Manchester GTFS transit data
 * into a MATSim-compatible mapped transit schedule and multimodal network.
 * <p>
 * Workflow steps:
 * <ol>
 *   <li>Convert GTFS schedule to an unmapped MATSim transit schedule</li>
 *   <li>(Optional) Convert OSM to a MATSim street network — skipped here
 *       because the base network comes from JIBE</li>
 *   <li>Map the transit schedule onto the street network</li>
 *   <li>Run a plausibility check on the mapping result</li>
 * </ol>
 *
 * Based on the pt2matsim example by @author polettif, adapted for Greater Manchester by @Ismail
 */
public final class PT2MATSimExample {

    private static final String EXAMPLE = "pt2matsim/";
    private static final String INPUT = EXAMPLE + "input/";
    private static final String INTERMEDIATE = EXAMPLE + "intermediate/";
    private static final String OUTPUT = EXAMPLE + "output/";

    /** Base street network from JIBE project */
    private static final String EXTERNAL = "input/mito/trafficAssignment/";

    /** Coordinate reference system for Greater Manchester (British National Grid) */
    private static final String MANCHESTER_EPSG = "EPSG:27700";

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        prepare();

        // Step 1: Convert GTFS schedule to unmapped transit schedule
        gtfsToSchedule();

        // Step 2: Map the schedule onto the network
        createMapperConfigFile(INTERMEDIATE + "MapperConfigAdjusted.xml");
        PublicTransitMapper.main(new String[]{INTERMEDIATE + "MapperConfigAdjusted.xml"});

        // Step 3: Plausibility check
        checkPlausibility();
    }

    /** Creates required output directories. */
    public static void prepare() {
        new File(OUTPUT + "plausibilityResults/").mkdirs();
        new File(INTERMEDIATE).mkdirs();
    }

    /**
     * Converts a GTFS zip file to an unmapped MATSim transit schedule.
     * Uses the day with the most trips for representative service selection.
     */
    public static void gtfsToSchedule() {
        String[] gtfsConverterArgs = new String[]{
                INPUT + "all-modes-adjusted-gtfs.zip",  // GTFS zip file
                "dayWithMostTrips",                      // Service ID selection strategy
                MANCHESTER_EPSG,                         // Output coordinate system
                INTERMEDIATE + "schedule_unmapped.xml.gz", // Output schedule
                INTERMEDIATE + "vehicles_unmapped.xml",    // Output vehicles
        };
        Gtfs2TransitSchedule.main(gtfsConverterArgs);
    }

    /**
     * Creates the PT-to-network mapper config file.
     * Configures how transit routes are mapped onto the street network.
     */
    public static void createMapperConfigFile(String configFile) {
        CreateDefaultPTMapperConfig.main(new String[]{INTERMEDIATE + "MapperConfigDefault.xml"});

        Config config = ConfigUtils.loadConfig(
                INTERMEDIATE + "MapperConfigDefault.xml",
                PublicTransitMappingConfigGroup.createDefaultConfig());

        config.global().setCoordinateSystem(MANCHESTER_EPSG);
        config.network().setInputCRS(MANCHESTER_EPSG);

        PublicTransitMappingConfigGroup ptmConfig = ConfigUtils.addOrGetModule(config, PublicTransitMappingConfigGroup.class);

        // Network files — base network should already include merged tram links
        ptmConfig.setInputNetworkFile(OUTPUT + "network_with_tramLinks.xml.gz");
        ptmConfig.setInputScheduleFile(INTERMEDIATE + "schedule_unmapped.xml.gz");

        ptmConfig.setOutputNetworkFile(OUTPUT + "multimodal_network.xml.gz");
        ptmConfig.setOutputScheduleFile(OUTPUT + "manchester_schedule.xml.gz");
        ptmConfig.setOutputStreetNetworkFile(OUTPUT + "multimodal_streetnetwork.xml.gz");

        // Rail and light rail use free-speed routing (no street-level mapping)
        ptmConfig.setScheduleFreespeedModes(CollectionUtils.stringToSet("rail, light_rail"));

        // Mode-to-network mapping: buses use car/bus links; trams stick to tram links
        // (PreparePTNetwork already added "tram" to embedded street-running sections,
        //  so allowing "car" here would only enable spurious mapping onto unrelated roads).
        ptmConfig.getTransportModeAssignment().put("bus", Set.of("car", "bus"));
        ptmConfig.getTransportModeAssignment().put("tram", Set.of("tram"));

        // Preserve these modes during network cleanup. "tram" must be included, otherwise
        // dedicated tram-only links not chosen by the mapper get deleted by
        // ScheduleCleaner.removeNotUsedTransitLinks, shrinking the output network.
        ptmConfig.setModesToKeepOnCleanUp(CollectionUtils.stringToSet("car,walk,bike,truck,tram"));

        // Use available cores for parallel mapping (capped at 20)
        int maxThreads = Math.min(Runtime.getRuntime().availableProcessors(), 20);
        ptmConfig.setNumOfThreads(maxThreads);

        new ConfigWriter(config).write(configFile);
    }

    /**
     * Runs a plausibility check on the mapped schedule and network.
     * Outputs warnings CSV and a network GeoJSON for visual inspection.
     */
    public static void checkPlausibility() {
        String scheduleFile = OUTPUT + "manchester_schedule.xml.gz";
        String networkFile = OUTPUT + "multimodal_network.xml.gz";
        String resultFolder = OUTPUT + "plausibilityResults/";

        new File(resultFolder).mkdirs();

        TransitSchedule schedule = ScheduleTools.readTransitSchedule(scheduleFile);
        Network network = NetworkTools.readNetwork(networkFile);

        PlausibilityCheck check = new PlausibilityCheck(schedule, network, MANCHESTER_EPSG);
        check.runCheck();
        check.writeCsv(resultFolder + "allPlausibilityWarnings.csv");
        check.printStatisticsLog();

        // Export network as GeoJSON for visual validation
        Network2Geojson.run(MANCHESTER_EPSG, network, resultFolder + "network.geojson");

        System.out.println("================================================================");
        System.out.println("Plausibility check complete.");
        System.out.println("CSV warnings  : " + resultFolder + "allPlausibilityWarnings.csv");
        System.out.println("Network GeoJSON: " + resultFolder + "network.geojson");
        System.out.println("================================================================");
    }
}
