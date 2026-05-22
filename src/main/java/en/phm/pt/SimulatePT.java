package en.phm.pt;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Network;
import org.matsim.api.core.v01.events.PersonDepartureEvent;
import org.matsim.api.core.v01.events.PersonEntersVehicleEvent;
import org.matsim.api.core.v01.events.PersonLeavesVehicleEvent;
import org.matsim.api.core.v01.events.handler.PersonDepartureEventHandler;
import org.matsim.api.core.v01.events.handler.PersonEntersVehicleEventHandler;
import org.matsim.api.core.v01.events.handler.PersonLeavesVehicleEventHandler;
import org.matsim.api.core.v01.population.*;
import org.matsim.api.core.v01.events.TransitDriverStartsEvent;
import org.matsim.api.core.v01.events.handler.TransitDriverStartsEventHandler;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.QSimConfigGroup;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.vehicles.Vehicle;

import java.util.*;

/**
 * Diagnostic PT simulation for Greater Manchester.
 *
 * What this does:
 *   1. Loads a multimodal network, mapped transit schedule, and transit vehicles.
 *   2. Routes 10 dummy agents (home → work → home) via Swiss Rail Raptor, which
 *      chooses walk or bike for access/egress legs to/from PT stops.
 *   3. Physically simulates PT vehicles on the road network so they share links
 *      with car traffic — a prerequisite for future mixed-traffic studies.
 *   4. Prints a summary of which PT lines/routes were used and via what
 *      access/egress modes (walk vs bike).
 */
public class SimulatePT {

    // Inputs — produced by the mapping step (PT2MATSimExample): the mapped
    // network and schedule land in pt2matsim/output/, the transit vehicles in
    // pt2matsim/intermediate/. Paths are relative to the working directory.
    private static final String PT_OUTPUT       = "pt2matsim/output/";
    private static final String PT_INTERMEDIATE = "pt2matsim/intermediate/";
    private static final String NETWORK  = PT_OUTPUT + "multimodal_network.xml.gz";
    private static final String SCHEDULE = PT_OUTPUT + "manchester_schedule.xml.gz";
    private static final String VEHICLES = PT_INTERMEDIATE + "vehicles_unmapped.xml";
    private static final String OUTPUT   = "output_pt_simulation/";

    /** Representative Greater Manchester locations (EPSG:27700 / British National Grid). */
    private static final Coord[] GM_LOCATIONS = {
        new Coord(383997, 398258),  // Manchester Piccadilly
        new Coord(383177, 398951),  // Salford Central
        new Coord(381243, 396622),  // Trafford
        new Coord(385163, 397498),  // Shudehill
        new Coord(377018, 387851),  // Altrincham
        new Coord(390474, 398040),  // Victoria
        new Coord(380511, 396180),  // Old Trafford
        new Coord(384314, 401506),  // Cheetham Hill
        new Coord(388142, 394486),  // Didsbury
        new Coord(378833, 392071),  // Wythenshawe
    };

    public static void main(String[] args) {
        Config config = buildConfig();
        Scenario scenario = ScenarioUtils.loadScenario(config);

        // Strip walk/bike mode from links not in the largest connected component for that
        // mode, so MATSim's NetworkRoutingProvider sees a consistent per-mode subgraph.
        // Links that lose walk/bike still carry car/truck and are not removed.
        System.out.println("\n-- Cleaning per-mode subnetworks for routed access/egress --");
        cleanModeSubnetworkAndReport(scenario.getNetwork(), TransportMode.walk);
        cleanModeSubnetworkAndReport(scenario.getNetwork(), TransportMode.bike);

        // Fill any gaps between schedule and vehicles file — safe to call even when
        // TransitVehicles.xml already covers all routes (it only adds missing entries).
        new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles()).run();

        createDummyPopulation(scenario);

        PTUsageTracker tracker = new PTUsageTracker();

        Controller controller = new Controler(scenario);
        // Swiss Rail Raptor replaces the default PT router. It supports intermodal
        // trips where walk or bike legs connect the agent to/from a PT stop.
        controller.addOverridingModule(new SwissRailRaptorModule());
        // Register the event tracker to receive all QSim events.
        controller.addOverridingModule(new AbstractModule() {
            @Override public void install() {
                addEventHandlerBinding().toInstance(tracker);
            }
        });
        controller.run();

        tracker.printSummary();
    }

    // -------------------------------------------------------------------------
    // Network preprocessing
    // -------------------------------------------------------------------------

    /**
     * Runs MATSim's per-mode connectivity cleaner for a single mode and reports
     * how many links lost the mode. Links that end up with no allowed modes are
     * removed; for walk/bike on a road network this should not happen because
     * those links also carry car/truck.
     */
    private static void cleanModeSubnetworkAndReport(Network network, String mode) {
        int linksBefore = network.getLinks().size();
        long withModeBefore = network.getLinks().values().stream()
                .filter(l -> l.getAllowedModes().contains(mode))
                .count();

        NetworkUtils.cleanNetwork(network, Set.of(mode));

        int linksAfter = network.getLinks().size();
        long withModeAfter = network.getLinks().values().stream()
                .filter(l -> l.getAllowedModes().contains(mode))
                .count();

        System.out.printf(
                "  %-5s : %d links had mode -> %d kept, %d lost mode  (total links: %d -> %d)%n",
                mode, withModeBefore, withModeAfter,
                withModeBefore - withModeAfter, linksBefore, linksAfter);
    }

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private static Config buildConfig() {
        Config config = ConfigUtils.createConfig();

        // --- Input ---
        config.network().setInputFile(NETWORK);
        config.transit().setTransitScheduleFile(SCHEDULE);
        config.transit().setVehiclesFile(VEHICLES);

        // useTransit = true: QSim creates a transit driver agent for every scheduled
        // vehicle departure. That driver physically moves the vehicle along the route's
        // network links, competing for road capacity with car agents. This is what makes
        // PT vehicles interact with cars — without it vehicles are teleported between stops.
        config.transit().setUseTransit(true);

        // Agents whose leg mode is "pt" are handed to the transit router (Raptor).
        // Other modes (walk, bike, car) are handled by their own routers.
        config.transit().setTransitModes(Set.of("pt"));

        // --- Controller ---
        // lastIteration = 0: in iteration 0 MATSim first routes all unrouted plans
        // (replanning phase) and then runs the mobility simulation. A single pass is
        // enough for a diagnostic check of the PT setup.
        config.controller().setLastIteration(0);
        config.controller().setOutputDirectory(OUTPUT);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);
        // Write events and plans for every iteration so results can be inspected.
        config.controller().setWriteEventsInterval(1);
        config.controller().setWritePlansInterval(1);

        // --- Swiss Rail Raptor (intermodal access/egress) ---
        configureRaptor(config);

        // --- Routing ---
        // Remove teleportation parameters for walk and bike so that they are routed
        // on the actual road network instead of moved at a fixed speed in a straight line.
        config.routing().removeParameterSet(config.routing().getOrCreateModeRoutingParams(TransportMode.walk));
        config.routing().removeParameterSet(config.routing().getOrCreateModeRoutingParams(TransportMode.bike));
        // Declare walk and bike as network modes — the router will compute link-level paths.
        config.routing().setNetworkModes(List.of(TransportMode.walk, TransportMode.bike));
        // accessEgressModeToLink: the access/egress route starts and ends at network links
        // (not just at stop facilities), giving physically realistic walking/cycling distances.
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);

        // --- QSim ---
        // flowCapFactor / storageCapFactor: scale network capacities relative to the
        // demand represented by the population. With only 10 agents simulating the whole
        // GM network, a factor of 1000 prevents artificial congestion that would distort
        // PT vehicle travel times. In a full scenario this should equal 1/sample-rate.
        config.qsim().setFlowCapFactor(1000.0);
        config.qsim().setStorageCapFactor(1000.0);
        // End the simulation when the last agent finishes rather than at a fixed wall-clock
        // time — avoids cutting off late-arriving PT vehicles.
        config.qsim().setSimEndtimeInterpretation(
            QSimConfigGroup.EndtimeInterpretation.minOfEndtimeAndMobsimFinished);

        // --- Scoring ---
        configureScoring(config);

        // --- Replanning ---
        // ReRoute: recompute routes for all agents before each iteration. With
        // lastIteration = 0 this is effectively the initial routing pass.
        ReplanningConfigGroup.StrategySettings reRoute = new ReplanningConfigGroup.StrategySettings();
        reRoute.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute);
        reRoute.setWeight(1.0);
        config.replanning().addStrategySettings(reRoute);

        return config;
    }

    /**
     * Configures Swiss Rail Raptor for intermodal access/egress.
     *
     * Raptor first searches for PT stops within initialSearchRadius. If it finds
     * no usable stop it expands outward up to maxRadius. Larger radii improve
     * coverage but increase router computation time.
     */
    private static void configureRaptor(Config config) {
        SwissRailRaptorConfigGroup raptor =
            ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);
        raptor.setUseIntermodalAccessEgress(true);

        // Walk: 500 m initial search, up to 5 km. Typical urban walking distance
        // to a PT stop is well under 1 km; 5 km is a generous upper bound.
        raptor.addIntermodalAccessEgress(intermodalParams(TransportMode.walk, 500, 5000));

        // Bike: same radii as walk. A 5 km cycling leg is fast (~15 min) and not
        // unrealistically long for a bike-and-ride trip in Greater Manchester.
        raptor.addIntermodalAccessEgress(intermodalParams(TransportMode.bike, 500, 5000));
    }

    private static SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet intermodalParams(
            String mode, double initialRadius, double maxRadius) {
        SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet p =
            new SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
        p.setMode(mode);
        p.setInitialSearchRadius(initialRadius);
        p.setMaxRadius(maxRadius);
        return p;
    }

    /**
     * Scoring parameters for home and work activities.
     *
     * typicalDuration sets the marginal utility of time spent at an activity, which
     * in turn determines how strongly agents penalise long travel times. Opening/
     * closing times and latestStartTime create time-of-day pressure that encourages
     * realistic departure-time patterns even with a fixed schedule.
     */
    private static void configureScoring(Config config) {
        ScoringConfigGroup.ActivityParams home = new ScoringConfigGroup.ActivityParams("home");
        home.setTypicalDuration(12 * 3600);
        config.scoring().addActivityParams(home);

        ScoringConfigGroup.ActivityParams work = new ScoringConfigGroup.ActivityParams("work");
        work.setTypicalDuration(8 * 3600);
        work.setOpeningTime(8 * 3600);
        work.setLatestStartTime(10 * 3600);
        work.setClosingTime(18 * 3600);
        config.scoring().addActivityParams(work);

        // Raptor uses "non_network_walk" for short transfer walks between stops at the same
        // stop area. MATSim's default mode params cover car/pt/walk/bike/ride/other but not
        // this synthetic mode, so without explicit params Raptor's scoring lookup NPEs.
        // Mirror the walk parameters since transfer walks are physically the same activity.
        ScoringConfigGroup.ModeParams walkParams = config.scoring().getModes().get(TransportMode.walk);
        ScoringConfigGroup.ModeParams nonNetworkWalk = new ScoringConfigGroup.ModeParams("non_network_walk");
        nonNetworkWalk.setMarginalUtilityOfTraveling(walkParams.getMarginalUtilityOfTraveling());
        nonNetworkWalk.setMarginalUtilityOfDistance(walkParams.getMarginalUtilityOfDistance());
        nonNetworkWalk.setConstant(walkParams.getConstant());
        nonNetworkWalk.setMonetaryDistanceRate(walkParams.getMonetaryDistanceRate());
        config.scoring().addModeParams(nonNetworkWalk);
    }

    // -------------------------------------------------------------------------
    // Population
    // -------------------------------------------------------------------------

    /**
     * Creates 10 dummy agents with home → work → home PT plans.
     *
     * Origins and destinations are offset by 4 positions in the GM_LOCATIONS array
     * to produce non-trivial OD pairs that span different parts of Greater Manchester.
     * Departure times are staggered over ±15 min around 07:30 to spread PT load.
     */
    private static void createDummyPopulation(Scenario scenario) {
        Population pop = scenario.getPopulation();
        PopulationFactory pf  = pop.getFactory();
        Random rnd = new Random(42);

        for (int i = 0; i < GM_LOCATIONS.length; i++) {
            Coord home = GM_LOCATIONS[i];
            Coord work = GM_LOCATIONS[(i + 4) % GM_LOCATIONS.length];

            Activity homeMorning = pf.createActivityFromCoord("home", home);
            homeMorning.setEndTime(27000 + rnd.nextInt(1800)); // 07:30 ± 15 min

            Activity workAct = pf.createActivityFromCoord("work", work);
            workAct.setMaximumDuration(9 * 3600);

            Activity homeEvening = pf.createActivityFromCoord("home", home);

            Plan plan = pf.createPlan();
            plan.addActivity(homeMorning);
            plan.addLeg(pf.createLeg("pt")); // Raptor will insert walk/bike + PT sub-legs
            plan.addActivity(workAct);
            plan.addLeg(pf.createLeg("pt"));
            plan.addActivity(homeEvening);

            Person person = pf.createPerson(Id.createPersonId("agent_" + i));
            person.addPlan(plan);
            pop.addPerson(person);
        }
    }

    // -------------------------------------------------------------------------
    // Event tracking
    // -------------------------------------------------------------------------

    /**
     * Tracks PT route usage and access/egress modes from QSim events.
     *
     * Event sequence for an intermodal trip:
     *   PersonDepartureEvent(walk)        ← access leg begins
     *   PersonEntersVehicleEvent(pt_veh)  ← board PT; access mode = walk
     *   PersonLeavesVehicleEvent(pt_veh)  ← alight PT; flag person as "just alighted"
     *   PersonDepartureEvent(walk/bike)   ← egress leg begins; recorded as egress mode
     *
     * Transit drivers are identified via TransitDriverStartsWorkEvent and excluded
     * from passenger counts.
     */
    static class PTUsageTracker implements
            TransitDriverStartsEventHandler,
            PersonDepartureEventHandler,
            PersonEntersVehicleEventHandler,
            PersonLeavesVehicleEventHandler {

        // vehicleId → "line :: route" label built from TransitDriverStartsWorkEvent
        private final Map<Id<Vehicle>, String> vehicleToRoute    = new HashMap<>();
        private final Set<Id<Person>>          transitDrivers    = new HashSet<>();

        // Per-person transient state (cleared once consumed)
        private final Map<Id<Person>, String>  lastDepartMode    = new HashMap<>();
        private final Map<Id<Person>, String>  justAlightedRoute = new HashMap<>();

        // Aggregated results
        private final Map<String, Integer>              boardings   = new TreeMap<>();
        private final Map<String, Map<String, Integer>> accessModes = new TreeMap<>();
        private final Map<String, Map<String, Integer>> egressModes = new TreeMap<>();

        @Override
        public void handleEvent(TransitDriverStartsEvent e) {
            vehicleToRoute.put(e.getVehicleId(),
                e.getTransitLineId() + " :: " + e.getTransitRouteId());
            transitDrivers.add(e.getDriverId()); // getDriverId() replaces getPersonId() in MATSim 2026.x
        }

        @Override
        public void handleEvent(PersonDepartureEvent e) {
            if (transitDrivers.contains(e.getPersonId())) return;
            lastDepartMode.put(e.getPersonId(), e.getLegMode());

            // If this person just alighted from PT, the current leg is the egress leg.
            String route = justAlightedRoute.remove(e.getPersonId());
            if (route != null) {
                egressModes.computeIfAbsent(route, k -> new TreeMap<>())
                           .merge(e.getLegMode(), 1, Integer::sum);
            }
        }

        @Override
        public void handleEvent(PersonEntersVehicleEvent e) {
            if (transitDrivers.contains(e.getPersonId())) return;
            String route = vehicleToRoute.get(e.getVehicleId());
            if (route == null) return; // not a transit vehicle (e.g. a car)

            boardings.merge(route, 1, Integer::sum);
            accessModes.computeIfAbsent(route, k -> new TreeMap<>())
                       .merge(lastDepartMode.getOrDefault(e.getPersonId(), "unknown"),
                              1, Integer::sum);
        }

        @Override
        public void handleEvent(PersonLeavesVehicleEvent e) {
            if (transitDrivers.contains(e.getPersonId())) return;
            String route = vehicleToRoute.get(e.getVehicleId());
            // Flag person so the next PersonDepartureEvent is recognised as egress.
            if (route != null) justAlightedRoute.put(e.getPersonId(), route);
        }

        void printSummary() {
            System.out.println("\n========== PT SIMULATION SUMMARY ==========");

            System.out.println("\n-- PT Routes Used (total boardings) --");
            if (boardings.isEmpty()) {
                System.out.println("  (no boardings recorded — verify schedule/network alignment)");
            } else {
                boardings.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .forEach(e -> System.out.printf("  %-80s  %d%n", e.getKey(), e.getValue()));
            }

            System.out.println("\n-- Access Mode per PT Route --");
            if (accessModes.isEmpty()) {
                System.out.println("  (none)");
            } else {
                accessModes.forEach((route, modes) ->
                    System.out.printf("  %-80s  %s%n", route, modes));
            }

            System.out.println("\n-- Egress Mode per PT Route --");
            if (egressModes.isEmpty()) {
                System.out.println("  (none)");
            } else {
                egressModes.forEach((route, modes) ->
                    System.out.printf("  %-80s  %s%n", route, modes));
            }

            System.out.println("\n===========================================");
        }
    }
}
