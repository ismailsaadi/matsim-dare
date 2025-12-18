package org.example.pt;

import ch.sbb.matsim.config.SwissRailRaptorConfigGroup;
import ch.sbb.matsim.routing.pt.raptor.SwissRailRaptorModule;
import com.google.inject.name.Names;
import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigGroup;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.AbstractModule;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.router.util.TravelTime;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.core.utils.collections.CollectionUtils;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.simwrapper.SimWrapperModule;
import org.matsim.vehicles.Vehicle;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

class WalkTravelTime implements TravelTime {
    private final double walkSpeed;  // in m/s, e.g., 1.38889 ≈ 5 km/h

    public WalkTravelTime(double speed) {
        this.walkSpeed = speed;
    }

    @Override
    public double getLinkTravelTime(Link link, double time, Person person, Vehicle vehicle) {
        return link.getLength() / walkSpeed;
    }
}

public class simulatePT {

    public static void main(String[] args) {

        if (args.length < 3) {
            System.out.println("Usage: java simulatePT <network.xml.gz> <transitSchedule.xml.gz> <transitVehicles.xml.gz> [outputDir]");
            return;
        }

        String networkFile = args[0];
        String scheduleFile = args[1];
        String vehiclesFile = args[2];
        String outputDir = args.length >= 4 ? args[3] : "output_pt_walk_manchester/";

        Config config = ConfigUtils.createConfig();

        config.network().setInputFile(networkFile);
        config.transit().setTransitScheduleFile(scheduleFile);
        config.transit().setVehiclesFile(vehiclesFile);
        config.transit().setUseTransit(true);

        //
        Set<String> transportationModes = new HashSet<>();
        transportationModes.add("pt");
        config.transit().setTransitModes(transportationModes);

        //
        config.controller().setLastIteration(2);
        config.controller().setOutputDirectory(outputDir);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);
        config.controller().setWriteEventsInterval(2);
        config.controller().setWritePlansInterval(2);

        //
        SwissRailRaptorConfigGroup srrConfig = ConfigUtils.addOrGetModule(config, SwissRailRaptorConfigGroup.class);
        srrConfig.setUseIntermodalAccessEgress(true);

        // Configure default walk (required when intermodal is enabled)
        SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet walkSet = new SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
        walkSet.setMode(TransportMode.walk);
        walkSet.setMaxRadius(1000.0);
        walkSet.setInitialSearchRadius(500.0);
        srrConfig.addIntermodalAccessEgress(walkSet);

        // Configure custom mode for detailed routes
        SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet accessWalkSet = new SwissRailRaptorConfigGroup.IntermodalAccessEgressParameterSet();
        accessWalkSet.setMode("access_walk");
        accessWalkSet.setInitialSearchRadius(1000.0);   // e.g., start searching within 500m
        accessWalkSet.setMaxRadius(2000.0);  // larger radius to encourage its use
        srrConfig.addIntermodalAccessEgress(accessWalkSet);

        // Configure "access_walk" as full network mode
        var accessParams = config.routing().getOrCreateModeRoutingParams("access_walk");
        accessParams.setTeleportedModeSpeed(null);                  // force network routing → full NetworkRoute
        accessParams.setBeelineDistanceFactor(1.3);
        accessParams.setTeleportedModeFreespeedFactor(1.0);         // dummy for consistency

        //
        config.routing().setNetworkModes(Arrays.asList("walk", "access_walk"));  // Add both!

        //config.routing().setNetworkModes(Arrays.asList(TransportMode.car, TransportMode.walk));
        //config.routing().removeParameterSet(config.routing().getOrCreateModeRoutingParams(TransportMode.walk));
        //config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.accessEgressModeToLink);




        //config.routing().getOrCreateModeRoutingParams(TransportMode.walk).setTeleportedModeSpeed(1.38889); // ~5 km/h in m/s
        //config.routing().getOrCreateModeRoutingParams(TransportMode.walk).setBeelineDistanceFactor(1.3); // Accounts for non-straight paths

        //
        //config.transitRouter().setSearchRadius(1000.0);

        // fast sim
        config.qsim().setFlowCapFactor(1000);
        config.qsim().setStorageCapFactor(1000);
        config.qsim().setEndTime(36 * 3600);
        Set<String> mainModes = new HashSet<>();
        mainModes.add("car");
        mainModes.add("tram");
        mainModes.add("bus");
        mainModes.add("rail");
        config.qsim().setMainModes(mainModes);

        // important: proper access/egress walk routing

        // <<< ADD THIS ONE LINE >>>
        // config.controller().setLinkToLinkRoutingEnabled(true);
        // config.controller().setLinkToLinkRoutingEnabled();

        // === THIS IS THE FIX – ADD SCORING PARAMETERS FOR home AND work ===
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("home")
                .setTypicalDuration(12 * 3600));
        config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams("work")
                .setTypicalDuration(8 * 3600));

        // optional: make PT a bit more attractive (not required, but nice)
        config.scoring().getModes().get("pt").setMarginalUtilityOfTraveling(-6.0);
        config.scoring().getModes().get("pt").setConstant(-1.0);

        // only rerouting (fast & sufficient for 10 agents)
        ReplanningConfigGroup.StrategySettings strat = new ReplanningConfigGroup.StrategySettings();
        strat.setStrategyName(DefaultPlanStrategiesModule.DefaultStrategy.ReRoute);
        strat.setWeight(1.0);
        config.replanning().addStrategySettings(strat);


        Scenario scenario = ScenarioUtils.loadScenario(config);

        //
        for (Link link : scenario.getNetwork().getLinks().values()) {
            Set<String> modes = new HashSet<>(link.getAllowedModes());
            if (modes.contains(TransportMode.walk)) {
                modes.add("access_walk");
            }
            link.setAllowedModes(modes);
        }

        // create missing transit vehicles (safety)
        new CreateVehiclesForSchedule(scenario.getTransitSchedule(), scenario.getTransitVehicles()).run();

        // ============================
        // 10 DUMMY PERSONS – WALK + PT ONLY
        // ============================
        Population pop = scenario.getPopulation();
        PopulationFactory pf = pop.getFactory();
        Random rnd = new Random(2025);

        Coord[] locations = {
                new Coord(383997.433469516, 398258.3164167263), // Piccadilly
                new Coord(383176.9864780864, 398950.96491924033), // Salford
                new Coord(381243.3895996723, 396621.92106414476), // Trafford
                new Coord(385163.0545203696, 397497.9561363385), // Shudehill
                new Coord(377018.2134128619, 387851.496493614), // Altrincham
                new Coord(390474.18371438404, 398040.4283476201), // Victoria
                new Coord(380511.2868218826, 396179.87520340725), // Old Trafford
                new Coord(384313.5680226146, 401505.9052472415), // Cheetham Hill
                new Coord(388142.33324807795, 394485.61652863293), // Didsbury
                new Coord(378832.9669145816, 392070.6823141931) // Wythenshawe
        };

        for (int i = 0; i < 10; i++) {
            Person person = pf.createPerson(Id.createPersonId("mcr_pt_" + i));
            Plan plan = pf.createPlan();
            person.addPlan(plan);
            pop.addPerson(person);

            Coord home = locations[i];
            Coord dest = locations[(i + 4) % 10]; // go somewhere else in GM

            Activity h1 = pf.createActivityFromCoord("home", home);
            h1.setEndTime(7*3600 + 30*60 + rnd.nextInt(1800)); // 07:30 ±30 min
            plan.addActivity(h1);

            Leg ptOut = pf.createLeg("pt");
            plan.addLeg(ptOut);

            Activity work = pf.createActivityFromCoord("work", dest);
            work.setMaximumDuration(9 * 3600);
            plan.addActivity(work);

            Leg ptBack = pf.createLeg("pt");
            plan.addLeg(ptBack);

            Activity h2 = pf.createActivityFromCoord("home", home);
            plan.addActivity(h2);
        }

        new PopulationWriter(pop).write(outputDir + "dummy_population.xml.gz");

        // ============================
        // PRINT ACCESS/EGRESS LINKS AT THE END
        // ============================
        Controller controller = new Controler(scenario);
        //
        // Override module to bind walk-specific TravelTime
        /*
        controller.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // Bind custom TravelTime for walk mode
                bind(TravelTime.class).annotatedWith(Names.named(TransportMode.walk)).toInstance(new WalkTravelTime(1.38889));  // Adjust speed as needed

                // Optionally, bind a custom TravelDisutility if needed (default is fine for constant speed)
            }
        });

         */

        // To use the deterministic pt simulation (Part 1 of 2):
        // controller.addOverridingModule(new SBBTransitModule());

        // To use the fast pt router (Part 1 of 1)
        controller.addOverridingModule(new SwissRailRaptorModule());

        // To use the deterministic pt simulation (Part 2 of 2):
        /*
        controller.configureQSimComponents(components -> {
            new SBBTransitEngineQSimModule().configure(components);

            // if you have other extensions that provide QSim components, call their configure-method here
        });
         */

        // In your overriding module: bind custom travel time
        // Bind TravelTime (and optionally disutility) for walk and access_walk
        controller.addOverridingModule(new AbstractModule() {
            @Override
            public void install() {
                // For standard walk (required by intermodal when "walk" mode is in parameter sets)
                bind(TravelTime.class).annotatedWith(Names.named(TransportMode.walk)).toInstance(new WalkTravelTime(1.38889));  // ~5 km/h

                // For your custom access_walk
                bind(TravelTime.class).annotatedWith(Names.named("access_walk")).toInstance(new WalkTravelTime(1.38889));

                // Optional: bind TravelDisutility if you want custom costs (default is time-based, usually fine)
                // bind(TravelDisutility.class).annotatedWith(Names.named(TransportMode.walk)).to(WalkDisutility.class);
            }
        });

        controller.addOverridingModule(new SimWrapperModule());

        controller.run();
    }
}