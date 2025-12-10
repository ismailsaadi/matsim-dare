package org.example.pt;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.config.groups.RoutingConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.controler.Controller;
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting;
import org.matsim.core.controler.events.ShutdownEvent;
import org.matsim.core.controler.listener.ShutdownListener;
import org.matsim.core.population.io.PopulationWriter;
import org.matsim.core.replanning.strategies.DefaultPlanStrategiesModule;
import org.matsim.core.scenario.ScenarioUtils;
import org.matsim.pt.utils.CreateVehiclesForSchedule;
import org.matsim.simwrapper.SimWrapperModule;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

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
        config.controller().setLastIteration(20);
        config.controller().setOutputDirectory(outputDir);
        config.controller().setOverwriteFileSetting(OverwriteFileSetting.overwriteExistingFiles);
        config.controller().setWriteEventsInterval(20);
        config.controller().setWritePlansInterval(20);

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
        config.routing().setAccessEgressType(RoutingConfigGroup.AccessEgressType.walkConstantTimeToLink);

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
        // CORRECTED: Use addControlerListener with a ShutdownListener

        controller.addOverridingModule(new SimWrapperModule());

        controller.run();
    }
}